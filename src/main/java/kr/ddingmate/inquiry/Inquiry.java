package kr.ddingmate.inquiry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * /띵메이트 [문의] 탭: 분류·내용과 플레이어 정보를 개발자의 문의 중계(Cloudflare Worker, 저장소 relay/)로 보낸다.
 * 중계가 디스코드 채널에 [답변 보내기] 단추와 함께 올리고, 개발자가 답하면 {@link Replies} 가 받아 와 내 화면에만 보여 준다.
 * 사용자가 [문의 보내기]를 눌렀을 때만 보내고, 무엇이 함께 가는지는 탭에 적어 둔다. 게임 서버로는 아무것도 보내지 않는다.
 * 중계 주소는 저장소에 올리지 않는 assets/ddingmate/inquiry.properties 의 relay 에 둔다(없으면 보내기를 막는다).
 */
public final class Inquiry {

    private Inquiry() {}

    public enum Category {
        BUG("버그 제보"), FEATURE("기능 추가 요청"), TEXT("표기·계산 오류"), QUESTION("질문"), OTHER("기타");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 1500;
    /** 한 번 보낸 뒤 다시 보낼 수 있을 때까지(같은 문의를 여러 번 누르는 것·도배 막기). 중계도 같은 주소는 60초에 한 번만 받는다 */
    public static final long COOLDOWN_MS = 60_000;

    /** 보낸 결과: ok 면 보냄, 아니면 message 에 이유 */
    public record Result(boolean ok, String message) {}

    /** 함께 보내는 플레이어 정보 */
    /** nick: 띵타이쿤 한글 닉네임(모르면 빈 글). 서버가 보낸 귓속말·내 채팅에서 배운 것 */
    public record Info(String name, String uuid, String modVersion, String gameVersion, String server, String place, String nick) {

        /** "windswepty(멜트)" — 한글 닉네임을 모르면 계정 이름만 */
        public String displayName() {
            return nick == null || nick.isEmpty() ? name : name + "(" + nick + ")";
        }
    }

    /** 중계 응답 */
    public record Http(int status, String body) {}

    private static long lastSentAt;
    private static boolean sending;
    private static String endpoint;
    private static boolean endpointLoaded;
    /** 시험용: 정해 두면 인터넷으로 보내지 않고 이 함수에 (경로, 보낼 JSON)을 넘겨 응답을 받는다 */
    static BiFunction<String, String, Http> testSender;

    /** 중계 주소(https://…workers.dev, 끝의 / 없이). 없으면 null */
    public static String endpoint() {
        if (!endpointLoaded) {
            endpointLoaded = true;
            try (InputStream in = Inquiry.class.getResourceAsStream("/assets/ddingmate/inquiry.properties")) {
                if (in != null) {
                    Properties p = new Properties();
                    p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                    String url = p.getProperty("relay", "").trim();
                    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                    if (url.startsWith("https://")) endpoint = url;
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
     * (남의 닉네임을 적어 사칭하지 못하게). 받는 쪽에서 닉네임과 UUID 가 같은 계정인지 NameMC 로 확인할 수 있다
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
        String place = area == null || server == null ? "모름" : area.label + (ch > 0 ? " " + ch + "채널" : "");
        String nick = kr.ddingmate.game.Nickname.get();
        return new Info(name, uuid, mod, game, where, place, nick == null ? "" : nick);
    }

    /** 탭에 보여 줄 "함께 보내는 정보" 한 줄 */
    public static String infoLine(Info i) {
        return i.displayName() + " · 띵메이트 " + i.modVersion() + " · 마인크래프트 " + i.gameVersion() + " · " + i.server() + " · " + i.place();
    }

    // ---------------------------------------------------------------- 이미지 첨부

    public static final int MAX_FILES = 3;
    /** 디스코드 첨부 한도(서버 부스트 없는 기본 8MB)에 맞춘 한 장 크기 */
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

    // ---------------------------------------------------------------- 보내기

    /**
     * 중계로 보낼 JSON. 디스코드 메시지 모양(임베드·멘션·단추)은 중계가 만든다(아무 내용이나 채널에 올리지 못하게).
     * token: 이 문의의 답변을 받아 갈 비밀 번호표(중계는 해시만 저장)
     */
    public static String payload(Category category, String text, Info i, String token) {
        JsonObject root = new JsonObject();
        root.addProperty("category", category.name());
        root.addProperty("text", text.strip());
        root.addProperty("token", token);
        JsonObject info = new JsonObject();
        info.addProperty("name", cut(i.name(), 100));
        info.addProperty("uuid", cut(i.uuid(), 100));
        info.addProperty("modVersion", cut(i.modVersion(), 100));
        info.addProperty("gameVersion", cut(i.gameVersion(), 100));
        info.addProperty("server", cut(i.server(), 100));
        info.addProperty("place", cut(i.place(), 100));
        if (i.nick() != null && !i.nick().isEmpty()) info.addProperty("nick", cut(i.nick(), 16));
        root.add("info", info);
        return root.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 답변을 받아 갈 비밀 번호표: 무작위 32바이트(이 컴퓨터의 config/ddingmate-inquiries.json 에만 남는다) */
    static String newToken() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
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
        String token = newToken();
        String body = payload(category, text, info(client), token);
        sending = true;
        if (testSender != null) {
            debugLastFiles = java.util.List.copyOf(names);
            Http res = testSender.apply("/inquiry", body);
            finish(res, null, category, text, token, done);
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return images.isEmpty() ? null : multipart(body, images, names);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).thenCompose(multi -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint() + "/inquiry")).timeout(Duration.ofSeconds(images.isEmpty() ? 15 : 60));
            if (multi == null) {
                b.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                b.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY).POST(HttpRequest.BodyPublishers.ofByteArray(multi));
            }
            return http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }).whenComplete((res, err) -> client.execute(() ->
                finish(res == null ? null : new Http(res.statusCode(), res.body()), err, category, text, token, done)));
    }

    private static HttpClient http;

    /** 문의 보내기·답변 확인이 함께 쓰는 연결 하나(보낼 때마다 새로 만들면 10분마다 묻는 동안 연결 스레드가 쌓인다) */
    public static synchronized HttpClient http() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return http;
    }

    private static final String BOUNDARY = "ddingmate-" + Long.toHexString(Double.doubleToLongBits(Math.random()));

    /** 파일 첨부: payload_json + files[n] (디스코드와 같은 모양) */
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

    private static void finish(Http res, Throwable err, Category category, String text, String token, Consumer<Result> done) {
        sending = false;
        int status = res == null ? -1 : res.status();
        if (status >= 200 && status < 300) {
            lastSentAt = System.currentTimeMillis();
            String id = "";
            try {
                id = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
            } catch (RuntimeException e) {
                DdingMate.LOG.warn("문의 번호를 읽지 못했어요: {}", res.body());
            }
            Replies.add(id, token, category, text);
            done.accept(new Result(true, "문의를 보냈어요. 답변은 채팅·알림으로 와요"));
            return;
        }
        if (err != null) DdingMate.LOG.warn("문의를 보내지 못했어요", err);
        else DdingMate.LOG.warn("문의를 보내지 못했어요: HTTP {} {}", status, res == null ? "" : res.body());
        done.accept(new Result(false, status == 429 ? "잠시 뒤에 다시 보내 주세요(보낸 문의가 많아요)"
                : err != null ? "보내지 못했어요. 인터넷 연결을 확인해 주세요"
                : status >= 500 ? "문의 받는 곳이 잠시 안 돼요. 조금 뒤에 다시 보내 주세요"
                : "보내지 못했어요(오류 " + status + ")"));
    }

    /** 시험용: (경로, JSON) → 응답. null 이면 실제로 보낸다 */
    public static void debugSender(BiFunction<String, String, Http> sender) {
        testSender = sender;
        lastSentAt = 0;
        sending = false;
    }
}
