package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 내 글꼴(2026-10-24 사용자 요청): 내 컴퓨터의 글꼴 파일(.ttf·.otf)을 config/ddingmate/fonts 에 넣으면 띵메이트 글꼴로 고를 수 있다.
 * <p>
 * 마인크래프트 글꼴은 리소스팩에서만 읽혀서, 게임이 리소스팩을 찾을 때마다(시작·다시 읽기) 그 폴더로
 * config/ddingmate/fontpack 에 작은 리소스팩(글꼴 파일 복사본 + body·bold·title 글꼴 정의)을 만들어 늘 켜 둔다.
 * 이 팩은 ddingmate:user/… 이름만 더해서 다른 리소스팩(띵타이쿤 서버 팩 등)의 그림·글꼴은 건드리지 않는다.
 * 내 컴퓨터에서만 쓰이고 서버나 다른 플레이어에게는 아무것도 보내지 않는다(다른 사람 화면은 그대로).
 * <p>
 * {@link #GAME}: "띵타이쿤 글꼴" = 게임 기본 글꼴(minecraft:default). 띵타이쿤 서버 리소스팩이나 내가 낀 글꼴 리소스팩이 바꾼 글꼴을
 * 띵메이트 창·HUD 도 그대로 따라간다.
 */
public final class UserFonts {

    public static final String GAME = "@game";
    private static final String USER = "user:";
    private static final String PACK_ID = "ddingmate_user_fonts";
    /** 1.21.4 리소스팩 형식 */
    private static final int PACK_FORMAT = 46;
    /** 너무 큰 파일(모든 언어가 든 글꼴 등)은 메모리를 많이 먹어서 막는다 */
    public static final long MAX_BYTES = 40L * 1024 * 1024;

    /** 넣은 글꼴 하나: 고를 때 쓰는 키(user:이름표) · 보여 줄 이름(파일 이름) */
    public record Entry(String key, String name) {}

    /** 마지막으로 리소스팩에 넣은 글꼴(= 지금 게임에 읽혀 있는 글꼴) */
    private static volatile List<Entry> loaded = List.of();

    private UserFonts() {}

    public static Path folder() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ddingmate").resolve("fonts");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            DdingMate.LOG.warn("내 글꼴 폴더를 만들지 못했어요: {}", dir, e);
        }
        return dir;
    }

    static Path pack() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate").resolve("fontpack");
    }

    /** 지금 읽혀 있는 내 글꼴 */
    public static List<Entry> entries() {
        return loaded;
    }

    // ---------------------------------------------------------------- 고르기

    /** 글꼴 목록의 키(띵메이트 글꼴은 FontStyle 이름) → 보여 줄 이름 */
    public static String label(String key) {
        if (GAME.equals(key)) return "띵타이쿤 글꼴";
        if (key != null && key.startsWith(USER)) {
            for (Entry e : loaded) if (e.key().equals(key)) return "내 글꼴 · " + e.name();
            return "내 글꼴(없음)";
        }
        try {
            return DdingMateConfig.FontStyle.valueOf(key).label;
        } catch (RuntimeException e) {
            return String.valueOf(key);
        }
    }

    /** 글꼴 목록에서 고른 키를 설정에 */
    public static void choose(DdingMateConfig cfg, String key) {
        if (GAME.equals(key) || (key != null && key.startsWith(USER))) {
            cfg.customFont = key;
            return;
        }
        try {
            cfg.font = DdingMateConfig.FontStyle.valueOf(key);
            cfg.customFont = "";
        } catch (RuntimeException ignored) {
            // 모르는 키는 무시
        }
    }

    /**
     * 설정의 내 글꼴로 그릴 글꼴 이름(kind = body·bold·title). 내 글꼴을 안 골랐거나 아직 읽히지 않았으면 null(띵메이트 글꼴 7종으로)
     */
    public static Identifier fontId(DdingMateConfig cfg, String kind) {
        String k = cfg == null ? null : cfg.customFont;
        if (k == null || k.isEmpty()) return null;
        if (GAME.equals(k)) return GAME_FONT;
        if (!k.startsWith(USER)) return null;
        for (Entry e : loaded) {
            // 글자를 그릴 때마다 불려서 이름은 만들어 둔 것을 쓴다
            if (e.key().equals(k)) return IDS.computeIfAbsent(k + "|" + kind,
                    x -> Identifier.of(DdingMate.MOD_ID, "user/" + k.substring(USER.length()) + "_" + kind));
        }
        return null;
    }

    private static final Identifier GAME_FONT = Identifier.ofVanilla("default");
    private static final java.util.Map<String, Identifier> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 넣기

    /** 윈도우 파일 고르기 창(여러 개). 고르는 동안 게임이 멈추지 않게 따로 띄운다 */
    public static void pick(MinecraftClient client, Consumer<List<Path>> done) {
        Runnable ask = () -> {
            List<Path> out = new ArrayList<>();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer filters = stack.mallocPointer(4);
                for (String f : new String[]{"*.ttf", "*.otf", "*.TTF", "*.OTF"}) filters.put(stack.UTF8(f));
                filters.flip();
                Path windows = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts");
                Path start = Files.isDirectory(windows) ? windows : client.runDirectory.toPath();
                String picked = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("띵메이트에 넣을 글꼴",
                        start.toAbsolutePath() + java.io.File.separator, filters, "글꼴 (.ttf, .otf)", true);
                if (picked != null) for (String f : picked.split("\\|")) if (!f.isBlank()) out.add(Path.of(f));
            } catch (Throwable t) {
                DdingMate.LOG.warn("글꼴 고르기 창을 열지 못했어요", t);
            }
            client.execute(() -> done.accept(out));
        };
        if (MinecraftClient.IS_SYSTEM_MAC) ask.run();
        else {
            Thread t = new Thread(ask, "ddingmate-font-picker");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 고른 파일을 내 글꼴 폴더로 복사. 아무것도 안 골랐으면 null, 아니면 결과 안내 */
    public static String add(List<Path> picked) {
        if (picked == null || picked.isEmpty()) return null;
        Path dir = folder();
        int ok = 0;
        String problem = null;
        for (Path p : picked) {
            String bad = check(p);
            if (bad != null) {
                problem = bad;
                continue;
            }
            try {
                Files.copy(p, dir.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                ok++;
            } catch (IOException e) {
                DdingMate.LOG.warn("글꼴을 복사하지 못했어요: {}", p, e);
                problem = p.getFileName() + " 을(를) 복사하지 못했어요";
            }
        }
        if (ok == 0) return problem != null ? problem : "넣은 글꼴이 없어요";
        return "글꼴 " + ok + "개를 넣었어요" + (problem != null ? " · " + problem : "") + " · 글꼴 목록에서 '내 글꼴'을 골라 주세요";
    }

    /** 넣을 수 없는 파일이면 그 이유 */
    public static String check(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ttf") && !name.endsWith(".otf")) return p.getFileName() + " 은(는) 글꼴 파일(.ttf · .otf)이 아니에요";
        try {
            if (!Files.isRegularFile(p)) return p.getFileName() + " 을(를) 찾을 수 없어요";
            if (Files.size(p) > MAX_BYTES) return p.getFileName() + " 은(는) 너무 커요(40MB 까지)";
        } catch (IOException e) {
            return p.getFileName() + " 을(를) 읽을 수 없어요";
        }
        return null;
    }

    /** 리소스를 다시 읽는다(그때 폴더로 리소스팩을 다시 만든다). 끝나면 after */
    public static void rebuildAndReload(MinecraftClient client, Runnable after) {
        client.reloadResources().thenRun(() -> client.execute(after));
    }

    // ---------------------------------------------------------------- 리소스팩 만들기

    /** 파일 이름 → 글꼴 이름표(영문 소문자·숫자·_ + 겹치지 않게 짧은 해시). 한글 파일 이름도 된다 */
    static String slug(String fileName) {
        String base = fileName.replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.length() > 24) base = base.substring(0, 24);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(fileName.getBytes(StandardCharsets.UTF_8));
        return (base.isEmpty() ? "font" : base) + "_" + String.format("%08x", crc.getValue());
    }

    /** 내 글꼴 폴더 → config/ddingmate/fontpack (예전 것은 지우고 새로) */
    public static synchronized List<Entry> rebuild() {
        Path pack = pack();
        Path fontDir = pack.resolve("assets").resolve(DdingMate.MOD_ID).resolve("font").resolve("user");
        List<Entry> out = new ArrayList<>();
        try {
            if (Files.exists(pack)) {
                try (Stream<Path> walk = Files.walk(pack)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
            Files.createDirectories(fontDir);
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {\"pack_format\": " + PACK_FORMAT
                    + ", \"description\": \"띵메이트 내 글꼴(이 컴퓨터에서만 쓰여요)\"}}\n", StandardCharsets.UTF_8);
            List<Path> files;
            try (Stream<Path> list = Files.list(folder())) {
                files = list.filter(p -> check(p) == null).sorted().toList();
            }
            DdingMateConfig cfg = DdingMate.config();
            float scale = (cfg == null ? 100 : cfg.userFontSize) / 100f;
            for (Path f : files) {
                String name = f.getFileName().toString();
                String slug = slug(name);
                Files.copy(f, fontDir.resolve(slug + ".ttf"), StandardCopyOption.REPLACE_EXISTING);
                // 크기·높이는 G마켓 산스(본문 7.4 · 제목 10.4)에 맞춘 값. 글꼴마다 다르면 '내 글꼴 크기'로 맞춘다
                Files.writeString(fontDir.resolve(slug + "_body.json"), json(slug, 7.4f * scale, -0.8f), StandardCharsets.UTF_8);
                Files.writeString(fontDir.resolve(slug + "_bold.json"), json(slug, 7.4f * scale, -0.8f), StandardCharsets.UTF_8);
                Files.writeString(fontDir.resolve(slug + "_title.json"), json(slug, 10.2f * scale, -1.15f), StandardCharsets.UTF_8);
                out.add(new Entry(USER + slug, name.replaceFirst("\\.[^.]+$", "")));
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("내 글꼴 리소스팩을 만들지 못했어요", e);
        }
        loaded = List.copyOf(out);
        return loaded;
    }

    static String json(String slug, float size, float shift) {
        return "{\n  \"providers\": [\n"
                + String.format(Locale.ROOT, "    {\"type\": \"ttf\", \"file\": \"%s:user/%s.ttf\", \"size\": %.2f, \"oversample\": 8.0, \"shift\": [0.0, %.2f]},%n",
                DdingMate.MOD_ID, slug, size, shift)
                + "    {\"type\": \"reference\", \"id\": \"minecraft:default\"}\n  ]\n}\n";
    }

    /** 게임 리소스팩 목록에 '띵메이트 내 글꼴' 팩을 늘 켜진 채로 더한다(찾을 때마다 폴더로 다시 만든다) */
    public static final class Provider implements ResourcePackProvider {
        @Override
        public void register(Consumer<ResourcePackProfile> consumer) {
            rebuild();
            ResourcePackInfo info = new ResourcePackInfo(PACK_ID, Text.literal("띵메이트 내 글꼴"), ResourcePackSource.BUILTIN, Optional.empty());
            ResourcePackProfile profile = ResourcePackProfile.create(info, new DirectoryResourcePack.DirectoryBackedFactory(pack()),
                    ResourceType.CLIENT_RESOURCES, new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, false));
            if (profile != null) consumer.accept(profile);
            else DdingMate.LOG.warn("내 글꼴 리소스팩을 게임에 넣지 못했어요(pack.mcmeta)");
        }
    }
}
