package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.gui.RecipePickerScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 재료 카운터: 인벤토리 자동 집계, 단축어(ㄱ3)로 연 가방 기록, 번호 없이 다시 연 가방의 칸 비교,
 * 가방이 아닌 창 무시, 다 모였을 때 알림을 싱글 월드에서 확인한다.
 * 서버 가방 창은 흉내 낼 수 없어서 같은 종류의 54칸 창을 내 화면에만 띄워 대신한다.
 */
public class CounterClientGameTest implements FabricClientGameTest {

    private static final String LIFESTONE = "강화 제작 시설/하급 라이프스톤";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();

            ctx.runOnClient(c -> {
                DdingMate.config().tutorialDone = true;
                DdingMate.config().opened = true;
                BagTracker.clearCurrent();
                DdingMateConfig.Counter cc = DdingMate.config().counter;
                cc.enabled = true;
                cc.includeBags = true;
                cc.pins.get(0).recipe = LIFESTONE;
                cc.pins.get(0).crafts = 12;
                cc.pins.get(1).recipe = "강화 제작 시설/어빌리티 스톤";
                cc.pins.get(1).crafts = 1;
                cc.pins.get(2).recipe = "";
            });
            sp.getServer().runCommand("give @a minecraft:copper_block 12");
            sp.getServer().runCommand("give @a minecraft:redstone_block 36");
            sp.getServer().runCommand("give @a minecraft:iron_ingot[minecraft:custom_name='\"코룸 주괴\"'] 3");
            ctx.waitTicks(30);
            ctx.runOnClient(c -> {
                expect("구리 블록", 12, 0, 96);
                expect("레드스톤 블록", 36, 0, 36);
                expect("코룸 주괴", 3, 0, 12);
                expect("조약돌 뭉치", 0, 0, 24);
            });
            ctx.takeScreenshot("counter-1-inventory-only");

            // ㄱ3 을 보낸 직후 가방 창이 열린 상황
            ctx.runOnClient(c -> {
                BagTracker.onOutgoing("ㄱ3");
                c.setScreen(bagScreen(c, "모험 가방", 28));
            });
            ctx.waitTicks(20);
            ctx.runOnClient(c -> {
                check(BagTracker.openState() == BagTracker.OpenState.BAG && BagTracker.openBagNumber() == 3,
                        "ㄱ3 뒤에 열린 창이 3번 가방으로 인식되지 않음: " + BagTracker.openState());
            });
            ctx.takeScreenshot("counter-2-bag-open");
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                expect("구리 블록", 12, 28, 96);
                expect("조약돌 뭉치", 0, 2, 24);
                check(BagTracker.recordedCount() == 1, "가방 기록 수가 1이 아님");
            });

            // 번호 없이 같은 가방을 다시 연 상황(구리 블록만 2개 늘어남) → 칸 비교로 3번 가방
            ctx.runOnClient(c -> c.setScreen(bagScreen(c, "모험 가방", 30)));
            ctx.waitTicks(20);
            ctx.runOnClient(c -> check(BagTracker.openBagNumber() == 3,
                    "번호 없이 다시 연 가방을 3번으로 찾지 못함: " + BagTracker.openState()));
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                expect("구리 블록", 12, 30, 96);
                check(BagTracker.recordedCount() == 1, "같은 가방이 두 번 기록됨");
            });

            // 가방이 아닌 창(상점 등)은 세지 않는다.
            ctx.runOnClient(c -> c.setScreen(bagScreen(c, "상점", 64)));
            ctx.waitTicks(20);
            ctx.runOnClient(c -> check(BagTracker.openState() == BagTracker.OpenState.NONE, "상점 창을 가방으로 봄"));
            ctx.runOnClient(c -> c.setScreen(null));
            // 단축어를 보냈어도 기억한 가방 제목과 다른 창(구매 안내 등)이면 가방이 아니다.
            ctx.runOnClient(c -> {
                BagTracker.onOutgoing("/가방 5");
                c.setScreen(bagScreen(c, "가방 구매", 64));
            });
            ctx.waitTicks(20);
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                expect("구리 블록", 12, 30, 96);
                check(BagTracker.recordedCount() == 1, "가방이 아닌 창이 기록됨");
            });

            // 띵타이쿤 리소스팩처럼 창 제목에 가방 글리프(가방 그림 U+A001 + 눌린 7번 단추 U+B007)가 있으면
            // 단축어 없이도 7번 가방으로 기록한다. 가방 목록 창(U+A000)은 가방이 아니다.
            ctx.runOnClient(c -> c.setScreen(bagScreen(c, glyphTitle('ꀁ', '뀇'), 5)));
            ctx.waitTicks(20);
            ctx.runOnClient(c -> check(BagTracker.openBagNumber() == 7, "제목 글리프로 7번 가방을 알아내지 못함"));
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.runOnClient(c -> c.setScreen(bagScreen(c, glyphTitle('ꀀ', '뀂'), 64)));
            ctx.waitTicks(20);
            ctx.runOnClient(c -> check(BagTracker.openState() == BagTracker.OpenState.NONE, "가방 목록 창을 가방으로 봄"));
            ctx.runOnClient(c -> c.setScreen(null));
            ctx.waitTicks(15);
            ctx.runOnClient(c -> {
                expect("구리 블록", 12, 35, 96);
                check(BagTracker.recordedCount() == 2, "가방 기록 수가 2가 아님: " + BagTracker.recordedCount());
            });

            // 3개 목표: 조약돌 뭉치만 모자람(필요 6, 가방 3번·7번에 2개씩) → 2개를 더 얻으면 완료 알림
            ctx.runOnClient(c -> DdingMate.config().counter.pins.get(0).crafts = 3);
            ctx.waitTicks(15);
            ctx.runOnClient(c -> check(!MaterialCounter.views().get(0).done(), "3개 목표가 벌써 완료로 나옴"));
            sp.getServer().runCommand("give @a minecraft:paper[minecraft:custom_name='\"조약돌 뭉치\"'] 2");
            ctx.waitTicks(25);
            ctx.runOnClient(c -> check(MaterialCounter.views().get(0).done(), "재료를 다 모았는데 완료로 안 나옴"));
            ctx.takeScreenshot("counter-3-ready-toast");

            // 설정 화면의 재료 카운터 영역과 레시피 고르기 화면
            ctx.runOnClient(c -> {
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTab(5);
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("counter-4-settings");
            ctx.runOnClient(c -> c.currentScreen.children().stream()
                    .filter(e -> e instanceof ElementListWidget<?>)
                    .forEach(e -> ((ElementListWidget<?>) e).setScrollY(10000)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("counter-4b-settings-bottom");
            ctx.runOnClient(c -> c.setScreen(new RecipePickerScreen(c.currentScreen, 2)));
            ctx.waitTicks(5);
            ctx.runOnClient(c -> c.currentScreen.children().stream()
                    .filter(e -> e instanceof TextFieldWidget f && f.getWidth() > 100) // 검색 칸만
                    .forEach(e -> ((TextFieldWidget) e).setText("라이프")));
            ctx.waitTicks(5);
            ctx.takeScreenshot("counter-5-recipe-picker");
            ctx.runOnClient(c -> c.currentScreen.close());
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                c.currentScreen.close();
                DdingMate.config().counter.pins.get(1).recipe = "";
            });
            ctx.waitTicks(5);
        }
    }

    /** 서버 가방 창 제목처럼 글꼴이 지정된 글리프 + "모험 가방" 글자 */
    private static Text glyphTitle(char main, char button) {
        String ns = "e94a3c2f-10aa-4d92-9ed0-c73f74fef20e";
        return Text.empty()
                .append(Text.literal(String.valueOf(main)).setStyle(Style.EMPTY.withFont(Identifier.of(ns, "bag/ui/main"))))
                .append(Text.literal("ꀁꀂ" + button).setStyle(Style.EMPTY.withFont(Identifier.of(ns, "bag/ui/button"))))
                .append(Text.literal("모험 가방").setStyle(Style.EMPTY.withFont(Identifier.of(ns, "bag/ui/text/title"))));
    }

    private static GenericContainerScreen bagScreen(MinecraftClient c, String title, int copper) {
        return bagScreen(c, Text.literal(title), copper);
    }

    /** 54칸 창을 내 화면에만 띄운다. 구리 블록·조약돌·조약돌 뭉치(이름 붙인 종이)를 넣어 둔다. */
    private static GenericContainerScreen bagScreen(MinecraftClient c, Text title, int copper) {
        SimpleInventory inv = new SimpleInventory(54);
        inv.setStack(0, new ItemStack(Items.COPPER_BLOCK, copper));
        inv.setStack(1, new ItemStack(Items.COBBLESTONE, 10));
        ItemStack bundle = new ItemStack(Items.PAPER, 2);
        bundle.set(DataComponentTypes.CUSTOM_NAME, Text.literal("조약돌 뭉치"));
        inv.setStack(5, bundle);
        inv.setStack(9, new ItemStack(Items.DIAMOND_PICKAXE));
        inv.setStack(20, new ItemStack(Items.GLASS_BOTTLE, 3));
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(77, c.player.getInventory(), inv);
        return new GenericContainerScreen(h, c.player.getInventory(), title);
    }

    private static void expect(String material, int inventory, int bags, int need) {
        MaterialCounter.Line line = MaterialCounter.views().get(0).lines().stream()
                .filter(l -> l.name().equals(material)).findFirst()
                .orElseThrow(() -> new AssertionError("재료 줄 없음: " + material));
        if (line.inventory() != inventory || line.bags() != bags || line.need() != need) {
            throw new AssertionError(material + " 기대 인벤 " + inventory + "·가방 " + bags + "·필요 " + need
                    + ", 실제 " + line);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
