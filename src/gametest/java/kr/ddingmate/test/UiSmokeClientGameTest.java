package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.gui.RecipePickerScreen;
import kr.ddingmate.hud.HudLayout;
import kr.ddingmate.ui.Widgets;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 화면 전수 점검: HUD 스타일·크기·서버 HUD 피하기·구석의 모든 조합에서 HUD 가 화면 안에, 서로 겹치지 않게,
 * 서버 HUD 자리를 피해 그려지는지. 설정 화면 9개 탭의 모든 스위치·선택 단추를 눌러도 오류가 없는지.
 * 레시피 고르기에서 모든 시설로 거를 수 있는지. 마지막으로 한국어 게임에서 화면을 찍는다.
 */
public class UiSmokeClientGameTest implements FabricClientGameTest {

    private static final String[] IDS = {HudLayout.SCHEDULE, HudLayout.TODO, HudLayout.COUNTER, HudLayout.DURABILITY};

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            sp.getServer().runCommand("give @a minecraft:diamond_pickaxe[minecraft:damage=300]");
            sp.getServer().runCommand("give @a minecraft:copper_block 20");
            ctx.waitTicks(10);
            String[] snapshot = new String[1];
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                cfg.tutorialDone = true;
                cfg.opened = true;
                cfg.hudPos.clear();
                cfg.hudSizes.clear();
                cfg.hudEnabled = true;
                cfg.hudLines = 4;
                cfg.todo.hudEnabled = true;
                cfg.counter.enabled = true;
                cfg.counter.pins.get(0).recipe = "강화 제작 시설/하급 라이프스톤";
                cfg.counter.pins.get(1).recipe = "";
                cfg.counter.pins.get(2).recipe = "";
                cfg.durability.enabled = true;
                cfg.durability.tracked.clear();
                DurabilityTracker.setTracked("v:minecraft:diamond_pickaxe", "다이아몬드 곡괭이", true);
                cfg.save();
                snapshot[0] = read(configPath());
            });
            ctx.waitTicks(15);

            // 1) 기본 자리(일정·재료: 왼쪽 위, 할 일: 오른쪽 위, 내구도: 오른쪽 아래)에서 모든 스타일·크기·여백 조합
            for (kr.ddingmate.ui.HudTheme theme : kr.ddingmate.ui.HudTheme.values()) {
                DdingMateConfig.HudStyle style = theme.style;
                for (int scale : new int[]{50, 70, 100}) {
                    for (boolean avoid : new boolean[]{true, false}) {
                        ctx.runOnClient(c -> {
                            DdingMateConfig cfg = DdingMate.config();
                            cfg.hudTheme = theme;
                            cfg.hudStyle = style;
                            cfg.hudScale = scale;
                            cfg.avoidServerHud = avoid;
                            cfg.hudCorner = DdingMateConfig.Corner.TOP_LEFT;
                            cfg.counter.corner = DdingMateConfig.Corner.TOP_LEFT;
                            cfg.todo.corner = DdingMateConfig.Corner.TOP_RIGHT;
                            cfg.durability.corner = DdingMateConfig.Corner.BOTTOM_RIGHT;
                        });
                        ctx.waitTicks(2);
                        String label = theme + " " + scale + "% 피하기=" + avoid;
                        ctx.runOnClient(c -> checkLayout(c, label, avoid, true));
                    }
                }
            }
            ctx.runOnClient(c -> {
                DdingMate.config().hudTheme = kr.ddingmate.ui.HudTheme.LIGHT;
                DdingMate.config().hudStyle = DdingMateConfig.HudStyle.LIGHT;
                DdingMate.config().hudScale = 100;
                DdingMate.config().avoidServerHud = true;
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("smoke-1-light-style");
            // 테마 × 테두리 장식: 장식이 있어도 HUD 배치는 그대로, 테마마다 한 장씩 찍는다
            kr.ddingmate.ui.HudFrame[] frames = kr.ddingmate.ui.HudFrame.values();
            kr.ddingmate.ui.HudTheme[] themes = kr.ddingmate.ui.HudTheme.values();
            for (int i = 0; i < themes.length; i++) {
                kr.ddingmate.ui.HudTheme theme = themes[i];
                kr.ddingmate.ui.HudFrame frame = frames[(i + 1) % frames.length];
                ctx.runOnClient(c -> {
                    DdingMate.config().hudTheme = theme;
                    DdingMate.config().hudStyle = theme.style;
                    DdingMate.config().hudFrame = frame;
                });
                ctx.waitTicks(2);
                ctx.runOnClient(c -> checkLayout(c, theme + "+" + frame, true, true));
                ctx.takeScreenshot("smoke-theme-" + theme.name().toLowerCase() + "-" + frame.name().toLowerCase());
            }
            ctx.runOnClient(c -> {
                DdingMate.config().hudTheme = kr.ddingmate.ui.HudTheme.LIGHT;
                DdingMate.config().hudStyle = DdingMateConfig.HudStyle.LIGHT;
                DdingMate.config().hudFrame = kr.ddingmate.ui.HudFrame.NONE;
            });

            // 2) 네 HUD 를 한 구석에 몰아도 화면 밖으로 나가거나 오류가 나지 않는다(작은 창에서는 겹칠 수 있음)
            for (DdingMateConfig.Corner corner : DdingMateConfig.Corner.values()) {
                ctx.runOnClient(c -> {
                    DdingMateConfig cfg = DdingMate.config();
                    cfg.hudScale = 50;
                    cfg.hudCorner = corner;
                    cfg.counter.corner = corner;
                    cfg.todo.corner = corner;
                    cfg.durability.corner = corner;
                });
                ctx.waitTicks(2);
                ctx.runOnClient(c -> checkLayout(c, "한 구석 " + corner, true, false));
            }

            // 3) 설정 화면 9개 탭: 모든 스위치는 두 번(원래대로), 선택 단추는 한 번 눌러 보고, 탭마다 화면을 그린다
            ctx.runOnClient(c -> c.setScreen(new DdingMateScreen(null)));
            ctx.waitTicks(3);
            int[] pressed = new int[1];
            for (int tab = 0; tab < 10; tab++) {
                int t = tab;
                ctx.runOnClient(c -> {
                    ((DdingMateScreen) c.currentScreen).openTab(t);
                    for (ClickableWidget w : widgets(c.currentScreen)) {
                        if (w instanceof Widgets.Toggle toggle) {
                            toggle.onPress();
                            toggle.onPress();
                            pressed[0] += 2;
                        } else if (w instanceof Widgets.Cycle<?> cycle) {
                            cycle.onPress();
                            pressed[0]++;
                        } else if (w instanceof Widgets.Button b && b.getMessage().getString().equals("테스트 알림")) {
                            b.onPress();
                            pressed[0]++;
                        }
                    }
                });
                ctx.waitTicks(2);
            }
            ctx.runOnClient(c -> {
                check(pressed[0] > 60, "눌러 본 단추가 너무 적음: " + pressed[0]);
                c.currentScreen.close();
            });
            ctx.waitTicks(2);

            // 4) 레시피 고르기: 시설 거르기를 한 바퀴 돌며 시설마다 레시피가 나오는지
            ctx.runOnClient(c -> c.setScreen(new RecipePickerScreen(null, 0)));
            ctx.waitTicks(3);
            ctx.runOnClient(c -> {
                Widgets.Cycle<?> filter = (Widgets.Cycle<?>) widgets(c.currentScreen).stream()
                        .filter(w -> w instanceof Widgets.Cycle<?>).findFirst().orElseThrow();
                ElementListWidget<?> list = (ElementListWidget<?>) c.currentScreen.children().stream()
                        .filter(e -> e instanceof ElementListWidget<?>).findFirst().orElseThrow();
                int all = list.children().size();
                check(all == 386, "전체 시설에서 레시피가 386개가 아님: " + all);
                int sum = 0;
                for (int i = 0; i < 17; i++) {
                    filter.onPress();
                    int n = list.children().size();
                    check(n > 0, "레시피가 없는 시설이 있음 (" + (i + 1) + "번째)");
                    sum += n;
                }
                check(sum == all, "시설별 레시피 합이 전체와 다름: " + sum);
                filter.onPress();
                check(list.children().size() == all, "한 바퀴 돌았는데 전체로 돌아오지 않음");
                c.currentScreen.close();
            });
            ctx.waitTicks(2);

            // 설정을 점검 전으로 되돌림
            ctx.runOnClient(c -> {
                write(configPath(), snapshot[0]);
                DdingMate.reloadConfig();
            });
            ctx.waitTicks(2);

            // 5) 한국어 게임에서 홈 화면과 HUD
            ctx.runOnClient(c -> {
                c.options.language = "ko_kr";
                c.getLanguageManager().setLanguage("ko_kr");
                c.reloadResources();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 1200);
            ctx.waitTicks(10);
            ctx.takeScreenshot("smoke-2-korean-hud");
            ctx.runOnClient(c -> c.setScreen(new DdingMateScreen(null)));
            ctx.waitTicks(5);
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).openTabNamed("장비"));
            ctx.waitTicks(5);
            ctx.takeScreenshot("smoke-3-korean-gear-tab");
            ctx.runOnClient(c -> {
                c.currentScreen.close();
                c.options.language = "en_us";
                c.getLanguageManager().setLanguage("en_us");
                c.reloadResources();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 1200);
        }
    }

    /** HUD 가 화면 안에 있고, (겹침 검사 시) 서로 겹치지 않으며, 서버 HUD 자리를 피하는지 */
    private static void checkLayout(MinecraftClient c, String label, boolean avoid, boolean noOverlap) {
        int sw = c.getWindow().getScaledWidth();
        int sh = c.getWindow().getScaledHeight();
        Map<String, HudLayout.Rect> b = HudLayout.bounds();
        for (String id : IDS) {
            HudLayout.Rect r = b.get(id);
            check(r != null, label + ": " + id + " HUD 가 그려지지 않음");
            check(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= sw && r.y() + r.h() <= sh,
                    label + ": " + id + " 가 화면 밖으로 나감 " + r);
        }
        if (avoid) {
            for (String id : IDS) {
                HudLayout.Rect r = b.get(id);
                boolean overlapsTopBand = r.y() < 16 && (r.x() < sw * 0.45 || r.x() + r.w() > sw * 0.55);
                check(!overlapsTopBand || r.y() + r.h() <= 0, label + ": " + id + " 가 서버 재화 바·날짜 줄 자리를 가림 " + r);
            }
        }
        if (noOverlap) {
            for (int i = 0; i < IDS.length; i++) {
                for (int j = i + 1; j < IDS.length; j++) {
                    HudLayout.Rect p = b.get(IDS[i]), q = b.get(IDS[j]);
                    boolean hit = p.x() < q.x() + q.w() && q.x() < p.x() + p.w() && p.y() < q.y() + q.h() && q.y() < p.y() + p.h();
                    check(!hit, label + ": " + IDS[i] + " 와 " + IDS[j] + " 가 겹침 " + p + " " + q);
                }
            }
        }
    }

    /** 화면 안의 모든 위젯(목록 줄 안쪽까지) */
    static List<ClickableWidget> widgets(Element root) {
        List<ClickableWidget> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Element e, List<ClickableWidget> out) {
        if (e instanceof ClickableWidget w) out.add(w);
        if (e instanceof ParentElement p) for (Element child : List.copyOf(p.children())) collect(child, out);
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate.json");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(Path p, String s) {
        try {
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
