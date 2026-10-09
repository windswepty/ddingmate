package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.notify.KeywordWatcher;
import kr.ddingmate.notify.Notifier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** 싱글 월드를 만들어 HUD·/띵메이트·알림·설정 화면이 실제로 동작하는지 화면으로 확인한다. */
public class DdingMateClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();

            ctx.runOnClient(c -> {
                DdingMate.config().villageChannel = 7;
                DdingMate.config().hudLines = 5;
                DdingMate.rebuildRules();
                // 명령어는 /띵메이트 하나뿐
                var root = ClientCommandManager.getActiveDispatcher().getRoot();
                if (root.getChild("띵메이트") == null) throw new AssertionError("/띵메이트 가 없음");
                if (root.getChild("알리미") != null || root.getChild("ddmate") != null) {
                    throw new AssertionError("예전 명령어가 남아 있음");
                }
                if (!root.getChild("띵메이트").getChildren().isEmpty()) throw new AssertionError("/띵메이트 하위 명령이 남아 있음");
            });
            ctx.waitTicks(40);
            ctx.takeScreenshot("ddingmate-1-hud");

            ctx.runOnClient(c -> Notifier.test());
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-2-test-alert");

            // 서버 시스템 메시지에 키워드가 들어온 상황을 흉내 낸다.
            ctx.runOnClient(c -> KeywordWatcher.onMessage(Text.literal("[알림] 오션오더가 도착했습니다!"), null));
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-3-keyword-alert");

            // /띵메이트 를 입력하면 띵메이트 창(홈 탭)이 열린다.
            ctx.runOnClient(c -> c.player.networkHandler.sendChatCommand("띵메이트"));
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                if (!(c.currentScreen instanceof DdingMateScreen s)) throw new AssertionError("/띵메이트 로 창이 열리지 않음");
                s.openTab(0);
            });
            ctx.waitTicks(25);
            ctx.takeScreenshot("ddingmate-4-home");
            ctx.runOnClient(c -> c.currentScreen.close());
            ctx.waitTicks(5);

            // Mod Menu 의 설정 버튼과 같은 경로(ModMenu.getConfigScreen)로도 열린다.
            ctx.runOnClient(c -> {
                try {
                    Screen s = (Screen) Class.forName("com.terraformersmc.modmenu.ModMenu")
                            .getMethod("getConfigScreen", String.class, Screen.class)
                            .invoke(null, "ddingmate", null);
                    if (!(s instanceof DdingMateScreen)) throw new AssertionError("Mod Menu 설정 화면 연동 실패: " + s);
                    c.setScreen(s);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            ctx.waitTicks(10);
            String[] tabs = {"notify", "events", "channels", "keywords"};
            for (int i = 0; i < tabs.length; i++) {
                int index = i + 1;
                ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).openTab(index));
                ctx.waitTicks(5);
                ctx.takeScreenshot("ddingmate-5-settings-" + tabs[i]);
            }
            ctx.runOnClient(c -> c.currentScreen.close());
            ctx.waitTicks(5);

            ctx.runOnClient(c -> {
                if (c.currentScreen != null) throw new AssertionError("설정 화면이 닫히지 않음");
                if (DdingMate.rules().stream().noneMatch(r -> r.id().equals("village_reboot"))) {
                    throw new AssertionError("마을 채널 재시작 일정이 만들어지지 않음");
                }
            });
        }
    }
}
