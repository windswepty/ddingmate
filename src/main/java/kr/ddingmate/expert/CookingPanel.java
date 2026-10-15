package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.ui.SidePanel;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 밀키 요리 상점 창 옆 부엌 판: 효율 좋은 요리 3개, 비싼 요리 3개, 인벤토리에 든 요리를 지금 팔면 받는 금액.
 * 가격은 이 창에서 방금 읽은 값(Cooking)만 쓴다.
 */
public final class CookingPanel {

    private CookingPanel() {}

    private static final int ROW = 11;
    private static final int TOP = 3;

    private record Item(ItemStack icon, String rank, String left, String right, int leftColor, int rightColor, boolean bold,
                        boolean divider) {
        static Item line() {
            return new Item(null, null, "", "", 0, 0, false, true);
        }

        static Item label(String text, int color) {
            return new Item(null, null, text, "", color, 0, true, false);
        }
    }

    public static void render(DrawContext ctx, Screen screen) {
        if (!DdingMate.config().expert.cookingPanel || !Cooking.shopOpen()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        SidePanel.Palette p = SidePanel.KITCHEN;
        List<Item> items = items(client, p);
        if (items.isEmpty()) return;
        String title = "요리 판매 도우미";
        int w = Theme.boldWidth(tr, title) + 26;
        for (Item it : items) {
            if (it.divider()) continue;
            int lw = (it.icon() != null ? 13 : 0) + (it.rank() != null ? 12 : 0)
                    + (it.bold() ? Theme.boldWidth(tr, it.left()) : Theme.width(tr, it.left()));
            int rw = it.right().isEmpty() ? 0 : Theme.boldWidth(tr, it.right()) + 8;
            w = Math.max(w, lw + rw + 14);
        }
        w = Math.max(128, Math.min(150, w));
        int h = SidePanel.HEAD_H + 6 + 4;
        for (Item it : items) h += it.divider() ? 5 : ROW;
        int[] at = SidePanel.place(screen.width, screen.height, w, h);
        int x = at[0], y = at[1];

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        SidePanel.frame(ctx, tr, x, y, w, h, p, new ItemStack(Items.BOWL), title);
        int cy = y + SidePanel.HEAD_H + 5;
        for (Item it : items) {
            if (it.divider()) {
                SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
                cy += 5;
                continue;
            }
            int tx = x + 6;
            if (it.rank() != null) {
                boolean first = it.rank().equals("1");
                ctx.fill(tx, cy, tx + 9, cy + 9, first ? p.accent() : Theme.withAlpha(p.soft(), 200));
                Theme.boldText(ctx, tr, it.rank(), tx + 5 - Theme.boldWidth(tr, it.rank()) / 2, cy + 1, 0xFFFFFFFF, false);
                tx += 12;
            }
            if (it.icon() != null) {
                Theme.item(ctx, it.icon(), tx, cy - 1, 10);
                tx += 13;
            }
            int rw = it.right().isEmpty() ? 0 : Theme.boldWidth(tr, it.right());
            String left = Theme.ellipsize(tr, it.left(), x + w - 8 - rw - 4 - tx, it.bold());
            if (it.bold()) Theme.boldText(ctx, tr, left, tx, cy + 1, it.leftColor(), false);
            else Theme.text(ctx, tr, left, tx, cy + 1, it.leftColor());
            if (rw > 0) Theme.boldText(ctx, tr, it.right(), x + w - 7 - rw, cy + 1, it.rightColor(), false);
            cy += ROW;
        }
        ctx.getMatrices().pop();
    }

    private static List<Item> items(MinecraftClient client, SidePanel.Palette p) {
        List<Item> out = new ArrayList<>();
        boolean byTime = DdingMate.config().expert.cookingByTime;
        List<Cooking.Rank> ranks = Cooking.ranking(byTime);
        if (ranks.isEmpty()) return out;
        out.add(Item.label(byTime ? "효율 좋은 요리 · 1분에" : "효율 좋은 요리 · 재료 1개당", p.ink()));
        for (int i = 0; i < Math.min(TOP, ranks.size()); i++) {
            Cooking.Rank r = ranks.get(i);
            String v = byTime ? String.format("%,.0fG", r.perMinute()) : String.format("%.1fG", r.perItem());
            out.add(new Item(icon(r), String.valueOf(i + 1), r.recipe().name, v, p.ink(), i == 0 ? p.good() : p.accent(), false, false));
        }
        out.add(Item.line());
        out.add(Item.label("비싼 요리", p.ink()));
        List<Cooking.Rank> price = Cooking.byPrice();
        for (int i = 0; i < Math.min(TOP, price.size()); i++) {
            Cooking.Rank r = price.get(i);
            out.add(new Item(icon(r), String.valueOf(i + 1), r.recipe().name, String.format("%,dG", r.price()), p.ink(),
                    i == 0 ? p.good() : p.accent(), false, false));
        }
        // 가진 요리를 지금 팔면
        Map<String, Integer> mine = Cooking.inventoryDishes(client);
        long total = 0;
        List<Item> mineRows = new ArrayList<>();
        for (var e : mine.entrySet()) {
            int each = Cooking.priceOf(e.getKey());
            if (each <= 0) continue;
            total += (long) each * e.getValue();
            ItemStack ic = IconCache.forMaterial(e.getKey());
            mineRows.add(new Item(ic == null ? new ItemStack(Items.BOWL) : ic, null, e.getKey() + " ×" + e.getValue(),
                    String.format("%,dG", (long) each * e.getValue()), p.ink(), p.accent(), false, false));
        }
        out.add(Item.line());
        if (mineRows.isEmpty()) {
            out.add(new Item(null, null, "인벤토리에 팔 요리가 없어요", "", p.soft(), 0, false, false));
        } else {
            out.add(new Item(null, null, "보유 요리 팔면", String.format("%,dG", total), p.ink(), p.good(), true, false));
            mineRows.sort((a, b) -> Long.compare(parse(b.right()), parse(a.right())));
            out.addAll(mineRows.subList(0, Math.min(4, mineRows.size())));
            if (mineRows.size() > 4) out.add(new Item(null, null, "그 밖에 " + (mineRows.size() - 4) + "종", "", p.soft(), 0, false, false));
        }
        ZonedDateTime next = Cooking.nextChange(ZonedDateTime.now(ScheduleEngine.KST));
        out.add(new Item(null, null, "다음 가격 변동 " + next.getMonthValue() + "/" + next.getDayOfMonth() + " 03시", "", p.soft(), 0, false, false));
        return out;
    }

    private static long parse(String g) {
        try {
            return Long.parseLong(g.replace(",", "").replace("G", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ItemStack icon(Cooking.Rank r) {
        ItemStack s = IconCache.forRecipe(r.recipe());
        return s == null ? new ItemStack(Items.BOWL) : s;
    }
}
