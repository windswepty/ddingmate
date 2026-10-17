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
                DdingMate.config().tutorialDone = false; // 처음 쓰는 사람처럼
                DdingMate.config().opened = false;
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
            // 아직 /띵메이트 를 열지 않았으면 HUD 가 하나도 없다
            ctx.runOnClient(c -> {
                if (!kr.ddingmate.hud.HudLayout.bounds().isEmpty()) throw new AssertionError("창을 열기 전인데 HUD 가 그려짐");
            });
            ctx.takeScreenshot("ddingmate-0-before-open");
            ctx.runOnClient(c -> DdingMate.config().opened = true);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                if (kr.ddingmate.hud.HudLayout.bounds().isEmpty()) throw new AssertionError("창을 연 뒤에도 HUD 가 없음");
            });
            ctx.takeScreenshot("ddingmate-1-hud");

            ctx.runOnClient(c -> Notifier.test());
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-2-test-alert");

            // 서버 시스템 메시지에 키워드가 들어온 상황을 흉내 낸다.
            ctx.runOnClient(c -> KeywordWatcher.onMessage(Text.literal("[알림] 오션오더가 도착했습니다!"), null));
            ctx.waitTicks(30);
            ctx.takeScreenshot("ddingmate-3-keyword-alert");

            // 처음 /띵메이트 → 튜토리얼이 자동으로 시작: 다음 단계로 가면 해당 탭이 열리고, 마지막에 끝나면 다시 나오지 않는다
            ctx.runOnClient(c -> {
                DdingMate.config().opened = false;
                c.player.networkHandler.sendChatCommand("띵메이트");
            });
            ctx.waitTicks(10);
            ctx.runOnClient(c -> {
                if (!DdingMate.config().opened) throw new AssertionError("/띵메이트 를 열었는데 HUD 켜짐 표시가 안 됨");
                if (!(c.currentScreen instanceof DdingMateScreen s)) throw new AssertionError("/띵메이트 로 창이 열리지 않음");
                if (!s.tutorialActive() || s.tutorialStep() != 0) throw new AssertionError("처음 열었는데 튜토리얼이 시작되지 않음");
            });
            ctx.takeScreenshot("tutorial-1-welcome");
            // 모든 단계에서 설명할 자리(탭 단추나 설정 줄)를 찾아 강조하고, 설명 상자가 그 자리를 가리지 않는지
            for (int i = 1; i < DdingMateScreen.tutorialSteps(); i++) {
                int step = i + 1;
                ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
                ctx.waitTicks(2);
                ctx.runOnClient(c -> {
                    DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                    int[] hl = s.tutorialHighlight();
                    if (hl == null) throw new AssertionError(step + "단계에서 강조할 설정을 찾지 못함");
                    int sh = c.getWindow().getScaledHeight();
                    if (hl[1] < 0 || hl[1] + hl[3] > sh) throw new AssertionError(step + "단계 강조 자리가 화면 밖: y=" + hl[1]);
                    int[] p = s.tutorialPanel();
                    if (p == null) throw new AssertionError(step + "단계 설명 상자가 그려지지 않음");
                    if (p[1] < 0 || p[1] + p[3] > sh) throw new AssertionError(step + "단계 설명 상자가 화면 밖: y=" + p[1]);
                    boolean hit = p[0] < hl[0] + hl[2] && hl[0] < p[0] + p[2] && p[1] < hl[1] + hl[3] && hl[1] < p[1] + p[3];
                    if (hit) throw new AssertionError(step + "단계 설명 상자가 강조 자리를 가림");
                });
            }
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                // 처음으로 돌아가 재료 탭 첫 단계(17단계)까지
                for (int i = 0; i < 40; i++) s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, 0, 0);
                if (s.tutorialStep() != 0) throw new AssertionError("처음 단계로 돌아가지 않음");
                for (int i = 0; i < 16; i++) s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
                if (s.tutorialStep() != 16 || !s.tabTitle().equals("재료")) {
                    throw new AssertionError("17단계(재료 탭)가 아님: 단계 " + s.tutorialStep() + ", 탭 " + s.tabIndex());
                }
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("tutorial-2-counter-step");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
            ctx.waitTicks(3);
            ctx.takeScreenshot("tutorial-3-count-step");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, 0, 0));
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, 0, 0);
                if (s.tutorialStep() != 15 || !s.tabTitle().equals("키워드")) throw new AssertionError("이전 단계로 가지 않음");
                // 튜토리얼 중에는 아래 창이 눌리지 않는다(탭 단추를 눌러도 그대로)
                s.mouseClicked(1, 1, 0);
                if (s.tutorialStep() != 15) throw new AssertionError("튜토리얼 밖을 눌렀는데 단계가 바뀜");
                for (int i = 0; i < 60 && s.tutorialActive(); i++) s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
                if (s.tutorialActive() || !DdingMate.config().tutorialDone || s.tabIndex() != 0) {
                    throw new AssertionError("튜토리얼을 끝까지 넘겼는데 끝나지 않음");
                }
                c.currentScreen.close();
            });
            ctx.waitTicks(3);
            // 건너뛰기: Esc 를 누르면 창은 그대로 두고 튜토리얼만 끝난다
            ctx.runOnClient(c -> {
                DdingMate.config().tutorialDone = false;
                c.setScreen(new DdingMateScreen(null));
            });
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                DdingMateScreen s = (DdingMateScreen) c.currentScreen;
                if (!s.tutorialActive()) throw new AssertionError("다시 처음처럼 열었는데 튜토리얼이 없음");
                s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                if (s.tutorialActive() || !DdingMate.config().tutorialDone) throw new AssertionError("건너뛰기가 되지 않음");
                if (c.currentScreen != s) throw new AssertionError("건너뛰기를 눌렀는데 창까지 닫힘");
                // 홈의 '튜토리얼 다시 보기'
                s.startTutorial();
                if (!s.tutorialActive()) throw new AssertionError("튜토리얼 다시 보기가 되지 않음");
                s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                c.currentScreen.close();
            });
            ctx.waitTicks(3);

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
            String[] tabs = {"notify", "events", "keywords"};
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
