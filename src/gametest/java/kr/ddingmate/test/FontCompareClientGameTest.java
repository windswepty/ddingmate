package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.ui.Theme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 글꼴 스위치(0.3.2): 갈무리·프리텐다드·네오둥근모 마다 글꼴 이름이 바뀌고 글자 폭이 달라지는지, 그리고
 * 1920×1080 GUI 3 에서 띵메이트 창·HUD 를 찍는다(DDINGMATE_FONT_COMPARE=1 일 때만 찍음).
 */
public class FontCompareClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            DdingMateConfig.FontStyle[] keep = new DdingMateConfig.FontStyle[1];
            boolean[] keepOpened = new boolean[2];
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                keep[0] = cfg.font;
                java.util.Set<String> ids = new java.util.HashSet<>();
                java.util.Set<Integer> widths = new java.util.HashSet<>();
                for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                    cfg.font = f;
                    ids.add(Theme.bodyFont().toString());
                    widths.add(Theme.width(c.textRenderer, "조약돌 뭉치 ×7 [3] 50분 20초"));
                    // 글꼴 파일이 실제로 읽혀야 한다(없으면 마인크래프트 기본 글꼴로 대신 그려 폭이 갈무리와 같아지지 않는다)
                }
                if (ids.size() != DdingMateConfig.FontStyle.values().length) throw new AssertionError("글꼴마다 다른 글꼴 이름이 아님: " + ids);
                if (widths.size() < DdingMateConfig.FontStyle.values().length) throw new AssertionError("글꼴을 바꿔도 글자 폭이 같음(글꼴 파일을 못 읽음?): " + widths);
                cfg.font = keep[0];
            });
            if (System.getenv("DDINGMATE_FONT_COMPARE") == null) return;
            ctx.runOnClient(c -> {
                c.getWindow().setWindowedSize(1920, 1080);
                keepOpened[0] = DdingMate.config().opened;
                keepOpened[1] = DdingMate.config().tutorialDone;
                DdingMate.config().opened = true;
            });
            ctx.waitTicks(10);
            for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                ctx.runOnClient(c -> {
                    DdingMate.config().font = f;
                    c.options.getGuiScale().setValue(3);
                    c.onResolutionChanged();
                    c.setScreen(null);
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-hud-" + f.name().toLowerCase());
                ctx.runOnClient(c -> {
                    DdingMateScreen s = new DdingMateScreen(null);
                    c.setScreen(s);
                    s.openTabNamed("재료");
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-screen-" + f.name().toLowerCase());
            }
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMate.config().font = keep[0];
                // 다음 시험은 '처음 설치' 상태로 시작해야 한다
                DdingMate.config().opened = keepOpened[0];
                DdingMate.config().tutorialDone = keepOpened[1];
                DdingMate.config().save();
                kr.ddingmate.hud.HudLayout.bounds().clear(); // 여기서 그린 HUD 자리가 다음 시험의 '아직 HUD 없음' 확인에 남지 않게
                c.getWindow().setWindowedSize(854, 480);
                c.options.getGuiScale().setValue(0);
                c.onResolutionChanged();
            });
        }
    }
}
