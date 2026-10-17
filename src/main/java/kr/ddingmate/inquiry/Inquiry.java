package kr.ddingmate.inquiry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kr.ddingmate.DdingMate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * /띵메이트 [문의] 탭: 분류·내용과 플레이어 정보를 개발자의 디스코드 채널(웹후크)로 보낸다.
 * 사용자가 [문의 보내기]를 눌렀을 때만 보내고, 무엇이 함께 가는지는 탭에 적어 둔다. 게임 서버로는 아무것도 보내지 않는다.
 * 보낼 곳 주소는 저장소에 올리지 않는 assets/ddingmate/inquiry.properties 의 url 에 둔다(없으면 보내기를 막는다).
 */
public final class Inquiry {

    private Inquiry() {}

    public enum Category {
        BUG("버그 제보", 0xE0524D), FEATURE("기능 추가 요청", 0x4FB54A), TEXT("표기·계산 오류", 0xF2B531),
        QUESTION("질문", 0x3A6FD8), OTHER("기타", 0x7C8390);

        public final String label;
        final int color;

        Category(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 1500;
    /** 한 번 보낸 뒤 다시 보낼 수 있을 때까지(같은 문의를 여러 번 누르는 것·도배 막기) */
    public static final long COOLDOWN_MS = 60_000;

    /** 보낸 결과: ok 면 보냄, 아니면 message 에 이유 */
    public record Result(boolean ok, String message) {}

    /** 함께 보내는 플레이어 정보 */
    public record Info(String name, String uuid, String modVersion, String gameVersion, String server, String place) {}

    private static long lastSentAt;
    private static boolean sending;
    private static String endpoint;
    /** 문의가 오면 멘션할 디스코드 사용자 ID(없으면 멘션 안 함) */
    private static String mentionId;
    private static boolean endpointLoaded;
    /** 시험용: 정해 두면 인터넷으로 보내지 않고 이 함수에 보낼 내용(JSON)을 넘겨 HTTP 상태 번호를 받는다 */
    private static Function<String, Integer> testSender;

    /** 보낼 곳(디스코드 웹후크 주소). 없으면 null */
    public static String endpoint() {
        if (!endpointLoaded) {
            endpointLoaded = true;
            try (InputStream in = Inquiry.class.getResourceAsStream("/assets/ddingmate/inquiry.properties")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                    String url = p.getProperty("url", "").trim();
                    if (url.startsWith("https://")) endpoint = url;
                    // 문의가 오면 개발자를 멘션해 알림이 가게(디스코드 사용자 ID, 숫자만)
                    String id = p.getProperty("mention", "").trim();
                    if (id.matches("\\d{15,21}")) mentionId = id;
                }
            } catch (Exception e) {
                DdingMate.LOG.warn("문의 보낼 곳을 읽지 못했어요", e);
            }
        }
        return endpoint;
    }

    /** 지금 보낼 수 있나(보낼 곳이 있고, 보내는 중이 아니고, 쉬는 시간이 지났나) */
    public static boolean available() {
        return (endpoint() != null || testSender != null) && !sending;
    }

    /** 다시 보낼 수 있을 때까지 남은 초(0 이면 지금 가능) */
    public static long cooldownLeftSec() {
        long left = lastSentAt + COOLDOWN_MS - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 내용이 보낼 만한가. 괜찮으면 null, 아니면 알려 줄 말 */
    public static String validate(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() < MIN_LENGTH) return "내용을 " + MIN_LENGTH + "글자 이상 적어 주세요";
        if (t.length() > MAX_LENGTH) return "내용이 너무 길어요(" + MAX_LENGTH + "글자까지)";
        return null;
    }

    /**
     * 지금 플레이어 정보. 사용자가 적는 칸은 없고, 모두 게임이 로그인한 계정(마이크로소프트 인증)에서 그대로 읽는다
     * (남의 닉네임을 적어 사칭하지 못하게). 받는 쪽에서 닉네임과 UUID 가 같은 계정인지 확인할 수 있게 NameMC 주소도 붙인다
     */
    public static Info info(MinecraftClient client) {
        com.mojang.authlib.GameProfile profile = client.player != null ? client.player.getGameProfile() : null;
        String name = profile != null ? profile.getName() : client.getSession() != null ? client.getSession().getUsername() : "?";
        String uuid = profile != null && profile.getId() != null ? profile.getId().toString() : "?";
        String mod = FabricLoader.getInstance().getModContainer(DdingMate.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String game = SharedConstants.getGameVersion().getName();
        ServerInfo server = client.getCurrentServerEntry();
        String where = server == null ? (client.isInSingleplayer() ? "혼자 하기" : "접속 안 함") : server.address;
        kr.ddingmate.game.ServerHud.Area area = kr.ddingmate.game.ServerHud.area();
        int ch = kr.ddingmate.game.ServerHud.channel();
        String place = area == null ? "모름" : area.label + (ch > 0 ? " " + ch + "채널" : "");
        return new Info(name, uuid, mod, game, where, place);
    }

    /** 탭에 보여 줄 "함께 보내는 정보" 한 줄 */
    public static String infoLine(Info i) {
        return i.name() + " · 띵메이트 " + i.modVersion() + " · 마인크래프트 " + i.gameVersion() + " · " + i.server() + " · " + i.place();
    }

    // ---------------------------------------------------------------- 이미지 첨부

    public static final int MAX_FILES = 3;
    /** 디스코드 웹후크 첨부 한도(서버 부스트 없는 기본 8MB)에 맞춘 한 장 크기 */
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final java.util.Set<String> IMAGE_EXT = java.util.Set.of("png", "jpg", "jpeg", "gif", "webp");

    private static String ext(java.nio.file.Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 첨부할 수 있는 이미지인가. 괜찮으면 null, 아니면 알려 줄 말 */
    public static String checkImage(java.nio.file.Path p) {
        try {
            if (p == null || !java.nio.file.Files.isRegularFile(p)) return "파일을 찾을 수 없어요";
            if (!IMAGE_EXT.contains(ext(p))) return "이미지(png·jpg·gif·webp)만 첨부할 수 있어요";
            if (java.nio.file.Files.size(p) > MAX_FILE_BYTES) return p.getFileName() + " 가 8MB 보다 커요";
            return null;
        } catch (java.io.IOException e) {
            return "파일을 읽지 못했어요";
        }
    }

    /**
     * 이미지 고르기: 윈도우 파일 고르기 창을 스크린샷 폴더에서 연다(여러 장 고르기 가능). 고르는 동안 게임이 멈추지 않게 따로 띄우고
     * (맥은 화면 스레드에서만 띄울 수 있어 그대로), 고른 파일은 게임 스레드에서 done 으로 넘긴다. 취소하면 빈 목록
     */
    public static void pickImages(MinecraftClient client, Consumer<java.util.List<java.nio.file.Path>> done) {
        Runnable ask = () -> {
            java.util.List<java.nio.file.Path> out = new java.util.ArrayList<>();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer filters = stack.mallocPointer(5);
                for (String f : new String[]{"*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"}) filters.put(stack.UTF8(f));
                filters.flip();
                java.nio.file.Path shots = client.runDirectory.toPath().resolve("screenshots");
                String start = (java.nio.file.Files.isDirectory(shots) ? shots : client.runDirectory.toPath()).toAbsolutePath() + java.io.File.separator;
                String picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("문의에 첨부할 이미지", start, filters, "이미지", true);
                if (picked != null) for (String f : picked.split("\\|")) if (!f.isBlank()) out.add(java.nio.file.Path.of(f));
            } catch (Throwable t) {
                DdingMate.LOG.warn("이미지 고르기 창을 열지 못했어요", t);
            }
            client.execute(() -> done.accept(out));
        };
        if (MinecraftClient.IS_SYSTEM_MAC) ask.run();
        else {
            Thread t = new Thread(ask, "ddingmate-image-picker");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 디스코드 웹후크로 보낼 JSON. 멘션(@everyone 등)은 울리지 않게 막는다 */
    public static String payload(Category category, String text, Info i) {
        return payload(category, text, i, java.util.List.of());
    }

    /** files: 첨부할 이름(image1.png …). 첫 장은 메시지 안에 크게 보이게 */
    public static String payload(Category category, String text, Info i, java.util.List<String> files) {
        JsonObject root = new JsonObject();
        root.addProperty("username", "띵메이트 문의");
        // 멘션은 개발자 한 사람만 울린다: 문의 내용에 @everyone·다른 사람 멘션이 있어도 울리지 않게 parse 는 비우고 users 만 허락
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        if (mentionId != null) {
            JsonArray users = new JsonArray();
            users.add(mentionId);
            mentions.add("users", users);
            root.addProperty("content", "<@" + mentionId + "> 새 문의가 들어왔어요 · " + category.label);
        }
        root.add("allowed_mentions", mentions);
        JsonObject embed = new JsonObject();
        embed.addProperty("title", cut("[" + category.label + "] " + i.name(), 256));
        embed.addProperty("description", cut(text.strip(), 4000));
        embed.addProperty("color", category.color);
        embed.addProperty("timestamp", Instant.now().toString());
        if (!files.isEmpty()) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://" + files.get(0));
            embed.add("image", image);
        }
        JsonArray fields = new JsonArray();
        fields.add(field("플레이어", i.name() + "\n" + i.uuid()
                + (realAccount(i.uuid()) ? "\n[계정 확인](https://namemc.com/profile/" + i.uuid() + ")" : "\n오프라인 계정(확인 불가)")));
        fields.add(field("버전", "띵메이트 " + i.modVersion() + "\n마인크래프트 " + i.gameVersion()));
        fields.add(field("접속", i.server() + "\n" + i.place()));
        embed.add("fields", fields);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root.toString();
    }

    /**
     * 마이크로소프트(정품) 계정의 UUID 인가. 정품 계정은 무작위 UUID(4판), 오프라인·시험 계정은 이름으로 만든 UUID(3판)라
     * NameMC 에서 찾을 수 없다(2026-10-17 실제: 시험 계정 Player0 링크가 404)
     */
    static boolean realAccount(String uuid) {
        try {
            return java.util.UUID.fromString(uuid).version() == 4;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject field(String name, String value) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", cut(value, 1000));
        f.addProperty("inline", true);
        return f;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * 보낸다(게임이 멈추지 않게 따로 보내고, 결과는 게임 스레드에서 done 으로 알려 준다).
     * 내용이 맞지 않거나 쉬는 시간 중이면 바로 실패로 알려 준다
     */
    public static void send(MinecraftClient client, Category category, String text, Consumer<Result> done) {
        send(client, category, text, java.util.List.of(), done);
    }

    public static void send(MinecraftClient client, Category category, String text, java.util.List<java.nio.file.Path> images,
                            Consumer<Result> done) {
        String problem = validate(text);
        if (problem == null && images.size() > MAX_FILES) problem = "이미지는 " + MAX_FILES + "장까지 첨부할 수 있어요";
        if (problem == null) for (java.nio.file.Path p : images) if ((problem = checkImage(p)) != null) break;
        if (problem != null) {
            done.accept(new Result(false, problem));
            return;
        }
        if (cooldownLeftSec() > 0) {
            done.accept(new Result(false, cooldownLeftSec() + "초 뒤에 다시 보낼 수 있어요"));
            return;
        }
        if (!available()) {
            done.accept(new Result(false, sending ? "보내는 중이에요" : "문의 받을 곳이 아직 정해지지 않았어요"));
            return;
        }
        // 첨부 이름은 image1.png 처럼(원래 파일 이름·폴더 경로는 보내지 않는다)
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int k = 0; k < images.size(); k++) names.add("image" + (k + 1) + "." + ext(images.get(k)));
        String body = payload(category, text, info(client), names);
        sending = true;
        if (testSender != null) {
            debugLastFiles = java.util.List.copyOf(names);
            finish(client, testSender.apply(body), null, done);
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return images.isEmpty() ? null : multipart(body, images, names);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).thenCompose(multi -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint())).timeout(Duration.ofSeconds(images.isEmpty() ? 15 : 60));
            if (multi == null) {
                b.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                b.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY).POST(HttpRequest.BodyPublishers.ofByteArray(multi));
            }
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .sendAsync(b.build(), HttpResponse.BodyHandlers.discarding());
        }).whenComplete((res, err) -> client.execute(() -> finish(client, res == null ? -1 : res.statusCode(), err, done)));
    }

    private static final String BOUNDARY = "ddingmate-" + Long.toHexString(Double.doubleToLongBits(Math.random()));

    /** 디스코드 웹후크 파일 첨부: payload_json + files[n] */
    static byte[] multipart(String json, java.util.List<java.nio.file.Path> images, java.util.List<String> names) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        for (int k = 0; k < images.size(); k++) {
            String type = switch (ext(images.get(k))) {
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                default -> "image/jpeg";
            };
            String part = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files[" + k + "]\"; filename=\"" + names.get(k) + "\"\r\n"
                    + "Content-Type: " + type + "\r\n\r\n";
            out.write(part.getBytes(StandardCharsets.UTF_8));
            out.write(java.nio.file.Files.readAllBytes(images.get(k)));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** 시험용: 마지막으로 보낸(시험) 첨부 이름 */
    public static java.util.List<String> debugLastFiles = java.util.List.of();

    private static void finish(MinecraftClient client, int status, Throwable err, Consumer<Result> done) {
        sending = false;
        if (status >= 200 && status < 300) {
            lastSentAt = System.currentTimeMillis();
            done.accept(new Result(true, "문의를 보냈어요. 고마워요!"));
            return;
        }
        if (err != null) DdingMate.LOG.warn("문의를 보내지 못했어요", err);
        else DdingMate.LOG.warn("문의를 보내지 못했어요: HTTP {}", status);
        done.accept(new Result(false, status == 429 ? "잠시 뒤에 다시 보내 주세요(보낸 문의가 많아요)"
                : err != null ? "보내지 못했어요. 인터넷 연결을 확인해 주세요" : "보내지 못했어요(오류 " + status + ")"));
    }

    /** 시험용: 멘션할 사용자 ID 를 바꾼다(null 이면 멘션 안 함), 원래 값을 돌려준다 */
    public static String debugMention(String id) {
        endpoint();
        String old = mentionId;
        mentionId = id;
        return old;
    }

    /** 시험용 */
    public static void debugSender(Function<String, Integer> sender) {
        testSender = sender;
        lastSentAt = 0;
        sending = false;
    }
}
