package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.ui.Theme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 글꼴 스위치(0.3.2): 글꼴마다(갈무리·온글잎 레디오 볼펜체·베리려원) 글꼴 이름이 바뀌고 글자 폭이 달라지는지, 그리고
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
                // 게임 전체에 적용: 기본 글꼴로 그리는 글자(바닐라 Text)도 고른 글꼴로, 끄면 원래대로. 다른 글꼴(uniform 등)은 그대로
                boolean keepEverywhere = cfg.fontEverywhere;
                cfg.font = DdingMateConfig.FontStyle.BERRY;
                String sample = "조약돌 뭉치 ×7 [3] 50분 20초";
                cfg.fontEverywhere = false;
                int vanillaW = c.textRenderer.getWidth(sample);
                cfg.fontEverywhere = true;
                int everywhereW = c.textRenderer.getWidth(sample);
                if (everywhereW == vanillaW || everywhereW != Theme.width(c.textRenderer, sample))
                    throw new AssertionError("게임 전체 글꼴이 안 바뀜: 기본 " + vanillaW + " / 적용 " + everywhereW + " / 베리려원 " + Theme.width(c.textRenderer, sample));
                net.minecraft.util.Identifier uniform = net.minecraft.util.Identifier.ofVanilla("uniform");
                if (Theme.gameFont(uniform) != uniform) throw new AssertionError("기본 글꼴이 아닌 글꼴까지 바꿈");
                cfg.fontEverywhere = false;
                if (c.textRenderer.getWidth(sample) != vanillaW) throw new AssertionError("게임 전체 글꼴을 꺼도 원래대로 안 돌아옴");
                cfg.fontEverywhere = keepEverywhere;
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
            // 게임 전체에 적용: 채팅·인벤토리(아이템 이름)까지 고른 글꼴로
            for (DdingMateConfig.FontStyle f : new DdingMateConfig.FontStyle[]{DdingMateConfig.FontStyle.REDIO, DdingMateConfig.FontStyle.BERRY}) {
                ctx.runOnClient(c -> {
                    DdingMate.config().font = f;
                    DdingMate.config().fontEverywhere = true;
                    c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("[초보모험가] 멜트 : 게임 전체 글꼴 시험이에요 1234 ABC"));
                    c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("오늘 스태미나 3,000 소모 보상으로 빙고 티켓 10개를 지급받았습니다!"));
                    c.player.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE));
                    c.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(c.player));
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-everywhere-" + f.name().toLowerCase());
            }
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMate.config().fontEverywhere = false;
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
