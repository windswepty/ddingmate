package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.ui.HudFrame;
import kr.ddingmate.ui.HudTheme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/**
 * 보여 주기용 촬영(DDINGMATE_SHOWCASE=1 일 때만): 실제 화면 크기(1920×1080, GUI 3)에 띵타이쿤 리소스팩을 켜고 HUD 를
 * 실제 플레이처럼 채운 뒤 테마 6종 · 테두리 장식 6종 · 글꼴 7종 · 설정 창을 찍는다. 끝나면 설정을 되돌린다.
 */
public class ShowcaseClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getenv("DDINGMATE_SHOWCASE") == null) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            DdingMateConfig.FontStyle[] keepFont = new DdingMateConfig.FontStyle[1];
            HudTheme[] keepTheme = new HudTheme[1];
            HudFrame[] keepFrame = new HudFrame[1];
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                keepFont[0] = cfg.font;
                keepTheme[0] = HudTheme.current();
                keepFrame[0] = cfg.hudFrame;
                cfg.opened = true;
                cfg.tutorialDone = true;
                cfg.guideSeen = true;
                cfg.hudScale = 100;
                cfg.hudOpacity = 85;
                cfg.fontEverywhere = false;
                c.getWindow().setWindowedSize(1920, 1080);
                c.options.getGuiScale().setValue(3);
                c.onResolutionChanged();
                c.inGameHud.getChatHud().clear(false);
                // 인벤토리: 세이지 곡괭이·내구도 지켜보는 다이아 곡괭이·재료(실제 서버 모양)
                var inv = c.player.getInventory();
                for (int i = 0; i < inv.size(); i++) inv.setStack(i, ItemStack.EMPTY);
                inv.setStack(0, kr.ddingmate.skill.SkillIcons.sageTool("pickaxe"));
                ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
                pick.set(DataComponentTypes.DAMAGE, 1400);
                inv.setStack(1, pick);
                inv.setStack(2, TestItems.server("조약돌 뭉치", 3));
                inv.setStack(3, new ItemStack(Items.COBBLESTONE, 64));
                inv.setStack(4, TestItems.server("코룸", 40));
                inv.setStack(5, TestItems.server("강화 횃불", 1));
                inv.setStack(6, new ItemStack(Items.COPPER_BLOCK, 10));
                inv.setStack(7, kr.ddingmate.skill.SkillIcons.sageTool("rod"));
                inv.setStack(8, kr.ddingmate.skill.SkillIcons.sageTool("hoe"));
                inv.selectedSlot = 0;
                kr.ddingmate.gear.DurabilityTracker.setTracked("v:minecraft:diamond_pickaxe", "다이아몬드 곡괭이", true);
                // 핀: 하급 라이프스톤 2번
                var lifestone = kr.ddingmate.counter.RecipeBook.recipeFor("하급 라이프스톤");
                if (lifestone != null) {
                    cfg.counter.pins.get(0).recipe = lifestone.id;
                    cfg.counter.pins.get(0).crafts = 2;
                }
                // 일정 HUD 에 보일 것들: 제작·항해·작물·골렘·청소
                long now = System.currentTimeMillis();
                kr.ddingmate.craft.CraftTimers.debugClear();
                for (int k = 1; k <= 3; k++)
                    kr.ddingmate.craft.CraftTimers.debugAddJob("조약돌 뭉치", 7, now, now + k * 95_000L, "보여주기 대기열");
                kr.ddingmate.craft.CraftTimers.debugAddJob("강화 횃불", 2, now, now + 12 * 60_000L, "보여주기 대기열2");
                kr.ddingmate.expert.CropTimer.start(now - 4 * 60_000L);
                kr.ddingmate.expert.OreGolem.debugReset();
                kr.ddingmate.expert.OreGolem.debugHandle("광석 골렘이 등장했습니다!", now - 90_000L);
                kr.ddingmate.game.ServerHud.debugClean("[청소] 5분 후 떨어진 아이템이 전부 청소됩니다.", now - 60_000L);
                kr.ddingmate.skill.SkillTimers.debugChat("곡괭이 나가신다! 스킬을 활성화했습니다.", now - 3000);
                cfg.save();
                c.setScreen(null);
            });
            ctx.waitTicks(40);

            // 1) 테마 6종(장식 없음)
            for (HudTheme t : HudTheme.values()) {
                ctx.runOnClient(c -> {
                    DdingMate.config().hudTheme = t;
                    DdingMate.config().hudStyle = t.style;
                    DdingMate.config().hudFrame = HudFrame.NONE;
                });
                ctx.waitTicks(6);
                ctx.takeScreenshot("show-theme-" + t.name().toLowerCase());
            }
            // 2) 테두리 장식 6종(띵타이쿤 파랑·라이트에서 하나씩)
            for (HudFrame f : HudFrame.values()) {
                for (HudTheme t : new HudTheme[]{HudTheme.BLUE, HudTheme.PASTEL}) {
                    ctx.runOnClient(c -> {
                        DdingMate.config().hudTheme = t;
                        DdingMate.config().hudStyle = t.style;
                        DdingMate.config().hudFrame = f;
                    });
                    ctx.waitTicks(6);
                    ctx.takeScreenshot("show-frame-" + f.name().toLowerCase() + "-" + t.name().toLowerCase());
                }
            }
            // 3) 글꼴 7종: HUD + 띵메이트 창(알림 방식 탭)
            for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                ctx.runOnClient(c -> {
                    DdingMate.config().hudTheme = HudTheme.BLUE;
                    DdingMate.config().hudStyle = HudTheme.BLUE.style;
                    DdingMate.config().hudFrame = HudFrame.NONE;
                    DdingMate.config().font = f;
                    c.setScreen(null);
                });
                ctx.waitTicks(6);
                ctx.takeScreenshot("show-font-hud-" + f.name().toLowerCase());
                ctx.runOnClient(c -> {
                    DdingMateScreen s = new DdingMateScreen(null);
                    c.setScreen(s);
                    s.openTabNamed("알림 방식");
                    s.jumpTo("HUD 모양");
                });
                ctx.waitTicks(8);
                ctx.takeScreenshot("show-font-window-" + f.name().toLowerCase());
            }
            // 4) 전문가 탭: 스킬 줄·스킬 판매가(스킬마다 자기 그림), 판매 시세(세이지 도구)
            ctx.runOnClient(c -> {
                DdingMate.config().font = DdingMateConfig.FontStyle.GALMURI;
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
                s.jumpTo("스킬");
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("show-expert-skills");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).jumpTo("스킬 판매가"));
            ctx.waitTicks(8);
            ctx.takeScreenshot("show-expert-price-skills");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).jumpTo("판매 시세"));
            ctx.waitTicks(8);
            ctx.takeScreenshot("show-expert-sales");

            // 5) 게임 전체에 적용(레디오 볼펜체) — 채팅·인벤토리까지
            ctx.runOnClient(c -> {
                DdingMate.config().font = DdingMateConfig.FontStyle.REDIO;
                DdingMate.config().fontEverywhere = true;
                c.inGameHud.getChatHud().addMessage(Text.literal("[초보모험가] 멜트 : 게임 전체 글꼴을 켰어요! 1234 ABC"));
                c.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(c.player));
            });
            ctx.waitTicks(8);
            ctx.takeScreenshot("show-everywhere-redio");

            // 되돌리기
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                cfg.font = keepFont[0];
                cfg.hudTheme = keepTheme[0];
                cfg.hudStyle = keepTheme[0].style;
                cfg.hudFrame = keepFrame[0];
                cfg.fontEverywhere = false;
                cfg.save();
                kr.ddingmate.expert.CropTimer.cancel();
                kr.ddingmate.craft.CraftTimers.debugClear();
                kr.ddingmate.hud.HudLayout.bounds().clear();
                c.getWindow().setWindowedSize(854, 480);
                c.options.getGuiScale().setValue(0);
                c.onResolutionChanged();
            });
        }
    }
}
