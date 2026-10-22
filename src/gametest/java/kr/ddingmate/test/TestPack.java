package kr.ddingmate.test;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 시험용 띵타이쿤 서버 리소스팩. 저장소에는 넣지 않고(서버 소유 자료) 시험 실행 폴더(build/run)에만 복사해 켠다.
 * DDINGMATE_SERVER_PACK: 없거나 "1"이면 Modrinth 띵타이쿤 테스트 프로필 downloads 의 가장 최근 서버 리소스팩, 경로면 그 파일,
 * "0"이면 끔.
 */
final class TestPack {

    private TestPack() {}

    static final String FILE = "ddingtycoon-server.zip";
    static final String ID = "file/" + FILE;

    /** 켜야 하나("0"이 아니면) */
    static boolean wanted() {
        return !"0".equals(System.getenv("DDINGMATE_SERVER_PACK"));
    }

    /** 경로를 직접 줬나(그러면 못 찾을 때 시험 실패) */
    static boolean required() {
        String env = System.getenv("DDINGMATE_SERVER_PACK");
        return env != null && !env.isBlank() && !env.equals("0");
    }

    static Path find() {
        String env = System.getenv("DDINGMATE_SERVER_PACK");
        if (env != null && !env.isBlank() && !env.equals("1") && !env.equals("0")) {
            Path p = Path.of(env);
            return Files.isRegularFile(p) ? p : null;
        }
        String appdata = System.getenv("APPDATA");
        if (appdata == null) return null;
        Path downloads = Path.of(appdata, "ModrinthApp", "profiles", "띵타이쿤-띵메이트-테스트", "downloads");
        if (!Files.isDirectory(downloads)) return null;
        try (Stream<Path> s = Files.walk(downloads, 2)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 복사해서 켠다(다시 읽기는 refreshResourcePacks 가 시작). 켰으면 true */
    static boolean enable(MinecraftClient c) {
        Path source = find();
        if (source == null) return false;
        try {
            Path dir = c.getResourcePackDir();
            Files.createDirectories(dir);
            Path dest = dir.resolve(FILE);
            if (!Files.exists(dest) || Files.size(dest) != Files.size(source)) Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new AssertionError("서버 리소스팩 복사 실패", e);
        }
        ResourcePackManager m = c.getResourcePackManager();
        m.scanPacks();
        if (m.getEnabledIds().contains(ID)) return true;
        if (!m.enable(ID)) throw new AssertionError("서버 리소스팩을 켜지 못함: " + m.getIds());
        c.options.refreshResourcePacks(m);
        return true;
    }

    static boolean active(MinecraftClient c) {
        return c.getResourcePackManager().getEnabledIds().contains(ID);
    }
}
