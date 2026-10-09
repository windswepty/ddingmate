package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.notify.KeywordWatcher;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import kr.ddingmate.gui.AlarmConfigScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** 싱글 월드를 만들어 HUD·명령어·알림이 실제로 동작하는지 화면으로 확인한다. */
public class AlarmClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();

            ctx.runOnClient(c -> c.player.networkHandler.sendChatCommand("알리미 채널 마을 7"));
            ctx.runOnClient(c -> c.player.networkHandler.sendChatCommand("알리미 줄수 5"));
            ctx.runOnClient(c -> c.player.networkHandler.sendChatCommand("알리미 목록"));
            ctx.waitTicks(40);
            ctx.takeScreenshot("ddingmate-1-hud-and-list");

            ctx.runOnClient(c -> c.player.networkHandler.sendChatCommand("알리미 테스트"));
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-2-test-alert");

            // 서버 시스템 메시지에 키워드가 들어온 상황을 흉내 낸다.
            ctx.runOnClient(c -> KeywordWatcher.onMessage(Text.literal("[알림] 오션오더가 도착했습니다!"), null));
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-3-keyword-alert");

            // Mod Menu 의 설정 버튼과 같은 경로(ModMenu.getConfigScreen)로 설정 화면을 연다.
            ctx.runOnClient(c -> {
                try {
                    Screen s = (Screen) Class.forName("com.terraformersmc.modmenu.ModMenu")
                            .getMethod("getConfigScreen", String.class, Screen.class)
                            .invoke(null, "ddingmate", null);
                    if (!(s instanceof AlarmConfigScreen)) throw new AssertionError("Mod Menu 설정 화면 연동 실패: " + s);
                    c.setScreen(s);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            ctx.waitTicks(10);
            String[] tabs = {"notify", "events", "channels", "keywords"};
            for (int i = 0; i < tabs.length; i++) {
                int index = i;
                ctx.runOnClient(c -> ((AlarmConfigScreen) c.currentScreen).openTab(index));
                ctx.waitTicks(5);
                ctx.takeScreenshot("ddingmate-4-settings-" + tabs[i]);
            }
            ctx.runOnClient(c -> c.currentScreen.close());
            ctx.waitTicks(5);

            ctx.runOnClient(c -> {
                if (c.currentScreen != null) throw new AssertionError("설정 화면이 닫히지 않음");
                if (DdingMate.config().villageChannel != 7) {
                    throw new AssertionError("채널 명령이 설정에 반영되지 않음");
                }
                if (DdingMate.rules().stream().noneMatch(r -> r.id().equals("village_reboot"))) {
                    throw new AssertionError("마을 채널 재시작 일정이 만들어지지 않음");
                }
            });
        }
    }
}
