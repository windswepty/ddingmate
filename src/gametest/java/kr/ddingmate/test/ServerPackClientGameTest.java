package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 띵타이쿤 서버 리소스팩을 켜고 찍어 보는 시험(DDINGMATE_SERVER_PACK 이 있을 때만).
 * 값이 파일 경로면 그 파일, "1"이면 Modrinth 띵타이쿤 테스트 프로필의 downloads 에서 가장 최근에 받은 서버 리소스팩을 쓴다.
 * 리소스팩은 이 시험 실행 폴더(build/run)에만 복사하고 저장소에는 넣지 않는다(서버 소유 자료).
 * 찍는 것: 서버 그림 글자가 섞인 실제 채팅(게임 전체 글꼴 끔/켬), 전문가 탭의 스킬 보석·세이지 도구 아이콘.
 */
public class ServerPackClientGameTest implements FabricClientGameTest {

    private static final String PACK_FILE = "ddingtycoon-server.zip";
    private static final String PACK_ID = "file/" + PACK_FILE;

    /** 실제 로그(2026-10)의 채팅 그대로: 앞의 그림·간격 글자 포함 */
    private static final String[] CHAT = {
            "󏿻ꀂ󏿿ꀁ󏼡𑙢 오늘 스태미나 3,000 소모 보상으로 빙고 티켓 10개를 지급받았습니다!",
            "󏿻ꀂ󏿿ꀁ󏼡𑙡 광석 골렘이 등장했습니다!",
            "󏿻ꀂ󐀁ꀇ󐀂ꀄ [달토끼] 망령 : 네 두리한테 가서 수리하면되요",
            "󏿻ꀂ󏿿ꀁ󏼡ꀓ󐀂[질문]󐀃JJung69 : rpg 할때 무기 바꿀라고 하는데",
    };

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String env = System.getenv("DDINGMATE_SERVER_PACK");
        if (env == null || env.isBlank()) return;
        Path source = findPack(env);
        if (source == null) throw new AssertionError("서버 리소스팩을 못 찾음: " + env);

        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            DdingMateConfig.FontStyle[] keepFont = new DdingMateConfig.FontStyle[1];
            boolean[] keep = new boolean[3];
            ctx.runOnClient(c -> {
                try {
                    Path dir = c.getResourcePackDir();
                    Files.createDirectories(dir);
                    Path dest = dir.resolve(PACK_FILE);
                    if (!Files.exists(dest) || Files.size(dest) != Files.size(source)) Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    throw new AssertionError("서버 리소스팩 복사 실패", e);
                }
                ResourcePackManager m = c.getResourcePackManager();
                m.scanPacks();
                if (!m.enable(PACK_ID)) throw new AssertionError("서버 리소스팩을 켜지 못함: " + m.getIds());
                c.options.refreshResourcePacks(m);
                DdingMateConfig cfg = DdingMate.config();
                keepFont[0] = cfg.font;
                keep[0] = cfg.fontEverywhere;
                keep[1] = cfg.opened;
                keep[2] = cfg.tutorialDone;
                cfg.opened = true;
                cfg.tutorialDone = true;
                c.getWindow().setWindowedSize(1920, 1080);
                c.options.getGuiScale().setValue(3);
                c.onResolutionChanged();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 20 * 120);
            ctx.waitTicks(20);
            ctx.runOnClient(c -> {
                for (String line : CHAT) c.inGameHud.getChatHud().addMessage(Text.literal(line));
            });
            // 게임 전체 글꼴: 끔(서버 글꼴 그대로) → 레디오 볼펜체 → 베리려원. 칭호·아이콘 그림이 그대로여야 한다
            for (Object[] f : new Object[][]{{false, DdingMateConfig.FontStyle.GALMURI, "off"},
                    {true, DdingMateConfig.FontStyle.REDIO, "redio"}, {true, DdingMateConfig.FontStyle.BERRY, "berry"}}) {
                ctx.runOnClient(c -> {
                    DdingMate.config().fontEverywhere = (Boolean) f[0];
                    DdingMate.config().font = (DdingMateConfig.FontStyle) f[1];
                    c.setScreen(new net.minecraft.client.gui.screen.ChatScreen(""));
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("serverpack-chat-" + f[2]);
            }
            // 전문가 탭: 스킬 판매가(보석 아이콘), 판매 시세(세이지 도구)
            ctx.runOnClient(c -> {
                DdingMate.config().fontEverywhere = false;
                DdingMate.config().font = DdingMateConfig.FontStyle.GALMURI;
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
                s.jumpTo("스킬 판매가");
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("serverpack-expert-skill-icons");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).jumpTo("판매 시세"));
            ctx.waitTicks(10);
            ctx.takeScreenshot("serverpack-expert-sage-tools");

            // 원래대로: 리소스팩 끄기, 설정·창 크기 되돌리기
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                cfg.font = keepFont[0];
                cfg.fontEverywhere = keep[0];
                cfg.opened = keep[1];
                cfg.tutorialDone = keep[2];
                cfg.save();
                ResourcePackManager m = c.getResourcePackManager();
                m.disable(PACK_ID);
                c.options.refreshResourcePacks(m);
                kr.ddingmate.hud.HudLayout.bounds().clear();
                c.getWindow().setWindowedSize(854, 480);
                c.options.getGuiScale().setValue(0);
                c.onResolutionChanged();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 20 * 120);
        }
    }

    /** 경로면 그 파일, "1"이면 띵타이쿤 테스트 프로필 downloads 의 가장 최근 서버 리소스팩 */
    private static Path findPack(String env) {
        if (!env.equals("1")) {
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
}
