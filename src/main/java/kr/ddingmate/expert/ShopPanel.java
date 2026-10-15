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
 * 판매품 상점(로니·샤키·포스·픽스·카이) 창 옆 판: 이 상점이 사 주는 물건 중 보유한 것과 팔면 받는 금액.
 * 가격은 이 창에 적힌 값(본 적 있으면), 아니면 위키 표. 품질에 따라 값이 다른 공예품은 범위로.
 */
public final class ShopPanel {

    private ShopPanel() {}

    private static final int ROW = 11;
    private static final int MAX_ROWS = 9;

    public static void render(DrawContext ctx, Screen screen) {
        String shop = ShopPrices.openShop();
        if (shop == null || !DdingMate.config().expert.shopPanel) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        SidePanel.Palette p = SidePanel.KITCHEN;
        List<ShopPrices.Owned> owned = ShopPrices.owned(client, null, shop);
        String title = shop + " · 보유 판매품";
        int rows = Math.min(MAX_ROWS, owned.size());
        int w = 146;
        int h = SidePanel.HEAD_H + 5 + Math.max(1, rows) * ROW + (owned.size() > rows ? ROW : 0) + 5 + 12 + 4 + 10;
        int[] at = SidePanel.place(screen.width, screen.height, w, h);
        int x = at[0], y = at[1];
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        SidePanel.frame(ctx, tr, x, y, w, h, p, new ItemStack(Items.GOLD_INGOT), title);
        int cy = y + SidePanel.HEAD_H + 5;
        if (owned.isEmpty()) {
            Theme.text(ctx, tr, "이 상점에 팔 물건이 없어요", x + 6, cy + 1, p.soft());
            cy += ROW;
        }
        for (int i = 0; i < rows; i++) {
            ShopPrices.Owned o = owned.get(i);
            ItemStack icon = IconCache.forMaterial(o.ware().name());
            Theme.item(ctx, icon == null ? new ItemStack(Items.PAPER) : icon, x + 6, cy - 1, 10);
            String right = ShopPrices.money(o.low(), o.high());
            int rw = Theme.boldWidth(tr, right);
            String bonus = o.bonus() > 0 ? " +" + o.bonus() + "%" : "";
            int bw = Theme.width(tr, bonus);
            String name = Theme.ellipsize(tr, o.ware().name() + " ×" + o.count(), w - 6 - 13 - rw - 12 - bw);
            Theme.text(ctx, tr, name, x + 19, cy + 1, p.ink());
            if (!bonus.isEmpty()) Theme.text(ctx, tr, bonus, x + 19 + Theme.width(tr, name), cy + 1, p.good());
            Theme.boldText(ctx, tr, right, x + w - 7 - rw, cy + 1, o.fromShop() || o.ware().fixed() ? p.accent() : p.soft(), false);
            cy += ROW;
        }
        if (owned.size() > rows) {
            Theme.text(ctx, tr, "그 밖에 " + (owned.size() - rows) + "종", x + 6, cy + 1, p.soft());
            cy += ROW;
        }
        SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
        long[] t = ShopPrices.total(owned);
        String sum = ShopPrices.money(t[0], t[1]);
        Theme.boldText(ctx, tr, "모두 팔면", x + 6, cy + 6, p.ink(), false);
        Theme.boldText(ctx, tr, sum, x + w - 7 - Theme.boldWidth(tr, sum), cy + 6, p.good(), false);
        Theme.text(ctx, tr, "나의 판매가(전문가 스킬 반영) 기준", x + 6, cy + 17, p.soft());
        ctx.getMatrices().pop();
    }
}
