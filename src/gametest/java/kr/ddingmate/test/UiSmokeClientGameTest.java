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
            // 기본 테마 6가지: HUD 배치는 그대로
            for (kr.ddingmate.ui.HudTheme theme : kr.ddingmate.ui.HudTheme.values()) {
                ctx.runOnClient(c -> kr.ddingmate.ui.HudSkins.applyTheme(DdingMate.config(), theme));
                ctx.waitTicks(2);
                ctx.runOnClient(c -> checkLayout(c, theme.name(), true, true));
                ctx.takeScreenshot("smoke-theme-" + theme.name().toLowerCase());
            }
            // NPC 세트 17벌: 그림 세 장(판·제목 줄·친구)이 GUI 아틀라스에 올라왔고, 판 · 친구가 있어도 HUD 배치는 그대로
            ctx.runOnClient(c -> {
                var skins = kr.ddingmate.ui.HudSkins.all();
                // 동물 NPC 17 · 지역 6 · 계절·행사 6 · 전문가 4 · 시간대 4 (사람 NPC 묶음은 2026-10-23 뺐다)
                if (skins.size() != 37) throw new AssertionError("HUD 세트 수: " + skins.size());
                var groups = new java.util.LinkedHashMap<String, Integer>();
                skins.forEach(s -> groups.merge(s.group(), 1, Integer::sum));
                if (!groups.equals(java.util.Map.of("동물 NPC", 17, "지역", 6, "계절·행사", 6, "전문가", 4, "시간대", 4)))
                    throw new AssertionError("묶음별 수가 다름: " + groups);
                // HUD 친구는 동물 NPC 17명만(사람 캐릭터는 2026-10-23 뺐다)
                if (kr.ddingmate.ui.HudSkins.mascots().size() != 17) throw new AssertionError("HUD 친구 수: " + kr.ddingmate.ui.HudSkins.mascots().size());
                var missing = net.minecraft.client.texture.MissingSprite.getMissingSpriteId();
                for (var s : skins) {
                    for (var id : s.hasMascot() ? List.of(s.panel(), s.head(), s.mascot()) : List.of(s.panel(), s.head())) {
                        if (c.getGuiAtlasManager().getSprite(id).getContents().getId().equals(missing))
                            throw new AssertionError("HUD 그림이 없음: " + id);
                    }
                }
            });
            for (var skin : kr.ddingmate.ui.HudSkins.all()) {
                ctx.runOnClient(c -> kr.ddingmate.ui.HudSkins.applySet(DdingMate.config(), skin));
                ctx.waitTicks(2);
                ctx.runOnClient(c -> {
                    checkLayout(c, skin.id(), true, true);
                    if (kr.ddingmate.ui.HudSkins.set() != skin) throw new AssertionError("세트가 안 맞음: " + skin.id());
                    // 친구 자리를 일정 판 위에 잡아 두므로 늘 어딘가에 앉는다
                    if (DdingMate.config().hudEnabled && c.getWindow().getScaledHeight() >= kr.ddingmate.hud.HudMascot.RESERVE_MIN_HEIGHT
                            && kr.ddingmate.hud.HudMascot.debugLastDrawn() == null)
                        throw new AssertionError("HUD 친구가 앉을 판을 못 찾음: " + skin.id());
                });
                ctx.takeScreenshot("smoke-set-" + skin.id());
            }
            // 따로 고르기: 판 · 제목 줄 · 친구를 섞으면 직접 조합
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                kr.ddingmate.ui.HudSkins.applyPanel(cfg, kr.ddingmate.ui.HudSkins.byId("roni"));
                cfg.hudHeader = "sharky";
                cfg.hudMascot = "hani";
                if (kr.ddingmate.ui.HudSkins.set() != null) throw new AssertionError("섞었는데 세트로 보임");
                if (cfg.hudStyle != DdingMateConfig.HudStyle.LIGHT) throw new AssertionError("로니 판은 밝은 판");
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("smoke-set-mixed");
            // 꾸미기 한눈에 보기 창: 탭마다 카드 수, 누르면 바로 바뀜(세트 → 세트가 맞음, 판 → 판만 바뀌어 직접 조합)
            ctx.runOnClient(c -> {
                var look = new kr.ddingmate.gui.HudLookScreen(null);
                c.setScreen(look);
                if (look.debugCards("세트") != 37 || look.debugCards("판 디자인") != 43 || look.debugCards("제목 줄") != 39
                        || look.debugCards("HUD 친구") != 18) throw new AssertionError("꾸미기 카드 수가 다름");
                look.debugChoose("세트", 12); // 샤키
                if (kr.ddingmate.ui.HudSkins.set() == null || !"sharky".equals(kr.ddingmate.ui.HudSkins.set().id()))
                    throw new AssertionError("세트 카드를 눌렀는데 안 바뀜");
                look.debugChoose("판 디자인", 0); // 띵타이쿤 파랑
                if (DdingMate.config().hudPanel != null || kr.ddingmate.ui.HudSkins.set() != null)
                    throw new AssertionError("판 카드를 눌렀는데 판만 안 바뀜");
                look.debugChoose("세트", 12);
            });
            for (String tab : new String[]{"세트", "판 디자인", "제목 줄", "HUD 친구"}) {
                ctx.runOnClient(c -> ((kr.ddingmate.gui.HudLookScreen) c.currentScreen).debugCards(tab));
                ctx.waitTicks(2);
                ctx.takeScreenshot("smoke-look-" + tab.replace(' ', '-'));
            }
            // 글꼴 한눈에 보기 창: 글꼴마다 카드 하나, 누르면 바로 그 글꼴
            ctx.runOnClient(c -> {
                var keepFont = DdingMate.config().font;
                c.setScreen(new kr.ddingmate.gui.FontLookScreen(null));
                if (kr.ddingmate.gui.FontLookScreen.debugCards() != DdingMateConfig.FontStyle.values().length)
                    throw new AssertionError("글꼴 카드 수가 글꼴 수와 다름: " + kr.ddingmate.gui.FontLookScreen.debugCards());
                kr.ddingmate.gui.FontLookScreen.debugChoose(1); // G마켓 산스
                if (DdingMate.config().font != DdingMateConfig.FontStyle.GMARKET) throw new AssertionError("글꼴 카드를 눌렀는데 안 바뀜");
                DdingMate.config().font = keepFont;
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("smoke-font-look");
            ctx.runOnClient(c -> c.setScreen(null));
            // 판 자동 바꾸기(2026-10-23): 계절·행사 날짜, 게임 시각, 지역·장소, 켜진 스킬 → 그 판. 맞는 판이 없으면 고른 판, 글자 밝기도 따라감
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                kr.ddingmate.ui.HudSkins.applySet(cfg, kr.ddingmate.ui.HudSkins.byId("hani"));
                java.util.Map<String, String> dates = java.util.Map.of("2026-10-20", "season_anniv", "2026-09-25", "season_chuseok",
                        "2026-12-25", "season_winter", "2027-02-10", "season_winter", "2026-04-05", "season_spring",
                        "2026-08-01", "season_summer", "2026-11-10", "season_moon", "2026-10-10", "season_moon");
                dates.forEach((d, want) -> {
                    String got = kr.ddingmate.ui.HudAuto.season(java.time.LocalDate.parse(d));
                    if (!want.equals(got)) throw new AssertionError(d + " 계절 판: " + got + " (기대 " + want + ")");
                });
                java.util.Map<Long, String> times = java.util.Map.of(0L, "time_day", 6000L, "time_day", 12000L, "time_sunset",
                        18000L, "time_night", 23500L, "time_dawn", 30000L, "time_day");
                times.forEach((t, want) -> {
                    if (!want.equals(kr.ddingmate.ui.HudAuto.time(t))) throw new AssertionError(t + " 시간대 판: " + kr.ddingmate.ui.HudAuto.time(t));
                });
                // 지역: 아일랜드의 세레니티 → 세레니티 판(밝은 판), 노크틸라 → 어두운 판으로 글자 밝기도
                cfg.hudAuto = "region";
                kr.ddingmate.game.ServerHud.debugPlace(kr.ddingmate.game.ServerHud.Area.ISLAND, "세레니티");
                kr.ddingmate.ui.HudAuto.update(c, java.time.LocalDate.now(), System.currentTimeMillis());
                if (kr.ddingmate.ui.HudSkins.panel() == null || !"region_serenity".equals(kr.ddingmate.ui.HudSkins.panel().id()))
                    throw new AssertionError("세레니티에서 세레니티 판이 아님: " + kr.ddingmate.ui.HudSkins.panel());
                kr.ddingmate.game.ServerHud.debugPlace(kr.ddingmate.game.ServerHud.Area.HUNT, "");
                kr.ddingmate.ui.HudAuto.update(c, java.time.LocalDate.now(), System.currentTimeMillis());
                if (!"region_noctila".equals(kr.ddingmate.ui.HudSkins.panel().id()) || cfg.hudStyle != DdingMateConfig.HudStyle.DARK)
                    throw new AssertionError("노크틸라 판·어두운 글자로 안 바뀜: " + kr.ddingmate.ui.HudSkins.panel().id() + " " + cfg.hudStyle);
                if (kr.ddingmate.ui.HudSkins.set() == null || !"hani".equals(kr.ddingmate.ui.HudSkins.set().id()))
                    throw new AssertionError("자동 판 때문에 고른 세트가 바뀜");
                // 전문가: 켜진 스킬이 없으면 고른 판(하니)
                cfg.hudAuto = "expert";
                kr.ddingmate.ui.HudAuto.update(c, java.time.LocalDate.now(), Long.MAX_VALUE / 2);
                if (kr.ddingmate.ui.HudSkins.panel() == null || !"hani".equals(kr.ddingmate.ui.HudSkins.panel().id())
                        || cfg.hudStyle != DdingMateConfig.HudStyle.LIGHT)
                    throw new AssertionError("켜진 스킬이 없는데 고른 판으로 안 돌아옴: " + kr.ddingmate.ui.HudSkins.panel());
                cfg.hudAuto = "region";
                kr.ddingmate.ui.HudAuto.update(c, java.time.LocalDate.now(), System.currentTimeMillis());
            });
            ctx.waitTicks(2);
            ctx.takeScreenshot("smoke-auto-noctila");
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                cfg.hudAuto = "off";
                kr.ddingmate.game.ServerHud.debugPlace(null, "");
                kr.ddingmate.ui.HudAuto.update(c, java.time.LocalDate.now(), System.currentTimeMillis());
                kr.ddingmate.ui.HudSkins.applyTheme(cfg, kr.ddingmate.ui.HudTheme.LIGHT);
                cfg.hudHeader = kr.ddingmate.ui.HudSkins.AUTO;
                cfg.hudMascot = kr.ddingmate.ui.HudSkins.NONE;
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
