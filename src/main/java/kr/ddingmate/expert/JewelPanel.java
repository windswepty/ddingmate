package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.ui.SidePanel;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/**
 * 보석함 판매 창 옆 판: 귀중품마다 칸에 적힌 가격("가격 : 10,971골드", 귀중품 가치·전문가 배율 반영)을 모아
 * 보석함에 넣은 것 합계와 갖고 있는 귀중품 전부의 합계를 따로 보여 준다.
 */
public final class JewelPanel {

    private JewelPanel() {}

    private static final int ROW = 11;
    private static final int MAX_ROWS = 10;

    public static void render(DrawContext ctx, Screen screen, int mouseX, int mouseY) {
        List<ShopPrices.Jewel> jewels = ShopPrices.jewels();
        if (jewels.isEmpty() || !DdingMate.config().expert.shopPanel) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        SidePanel.Palette p = SidePanel.KITCHEN;
        String title = "귀중품 가격";
        int rows = Math.min(MAX_ROWS, jewels.size());
        long box = 0, all = 0;
        int boxCount = 0, allCount = 0;
        for (ShopPrices.Jewel j : jewels) {
            all += j.total();
            allCount += j.count();
            if (j.inBox()) {
                box += j.total();
                boxCount += j.count();
            }
        }
        int w = Math.max(146, Theme.boldWidth(tr, title) + 26);
        for (int i = 0; i < rows; i++) {
            ShopPrices.Jewel j = jewels.get(i);
            w = Math.max(w, 19 + Theme.itemNameWidth(tr, j.name() + " ×" + j.count()) + 12
                    + Theme.boldWidth(tr, ShopPrices.money(j.total(), j.total())) + 7);
        }
        w = Math.min(w, SidePanel.maxWidth(screen.width));
        int h = SidePanel.HEAD_H + 5 + rows * ROW + (jewels.size() > rows ? ROW : 0) + 5 + 2 * ROW + 4;
        int[] at = SidePanel.place(screen.width, screen.height, w, h);
        int x = at[0], y = at[1];
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        SidePanel.frame(ctx, tr, x, y, w, h, p, new ItemStack(Items.CHEST), title);
        int cy = y + SidePanel.HEAD_H + 5;
        String tip = null;
        for (int i = 0; i < rows; i++) {
            ShopPrices.Jewel j = jewels.get(i);
            if (j.inBox()) SidePanel.rowBg(ctx, x + 3, cy - 2, w - 6, ROW, p, 0, true);
            ItemStack icon = IconCache.forMaterial(j.name());
            Theme.item(ctx, icon == null ? new ItemStack(Items.PAPER) : icon, x + 6, cy - 1, 10);
            String right = ShopPrices.money(j.total(), j.total());
            int rw = Theme.boldWidth(tr, right);
            Theme.Drawn d = Theme.itemName(ctx, tr, "", j.name() + " ×" + j.count(), x + 19, cy + 1, w - 6 - 13 - rw - 12, p.ink());
            if (d.cut() && mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= cy - 1 && mouseY < cy - 1 + ROW) tip = j.name() + " ×" + j.count();
            Theme.boldText(ctx, tr, right, x + w - 7 - rw, cy + 1, p.accent(), false);
            cy += ROW;
        }
        if (jewels.size() > rows) {
            Theme.text(ctx, tr, "그 밖에 " + (jewels.size() - rows) + "개", x + 6, cy + 1, p.soft());
            cy += ROW;
        }
        SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
        cy += 5;
        // 보석함에 넣은 것(창 위쪽 칸)만 / 내 인벤토리까지 전부
        String boxSum = ShopPrices.money(box, box);
        Theme.boldText(ctx, tr, "보석함에 넣은 것 " + boxCount + "개", x + 6, cy + 1, p.ink(), false);
        Theme.boldText(ctx, tr, boxSum, x + w - 7 - Theme.boldWidth(tr, boxSum), cy + 1, p.good(), false);
        cy += ROW;
        String allSum = ShopPrices.money(all, all);
        Theme.text(ctx, tr, "가진 귀중품 전부 " + allCount + "개", x + 6, cy + 1, p.soft());
        Theme.boldText(ctx, tr, allSum, x + w - 7 - Theme.boldWidth(tr, allSum), cy + 1, p.accent(), false);
        ctx.getMatrices().pop();
        if (tip != null) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0, 0, 310);
            ctx.drawTooltip(tr, net.minecraft.text.Text.literal(tip), mouseX, mouseY);
            ctx.getMatrices().pop();
        }
    }
}
