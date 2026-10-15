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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 바리스타 창 옆 카페 판: 기억해 둔 모든 손님의 주문(지금 창의 손님은 강조), 더 만들어야 할 커피,
 * 지금 가진 커피로 팔 수 있는 금액과 남은 주문 합계. 어느 바리스타 창(손님·인스턴트 커피)을 열어도 같은 판을 보여 준다.
 */
public final class BaristaPanel {

    private BaristaPanel() {}

    private static final int ROW = 11;
    /** 화면 너비 480(1080p · GUI 4배)에서도 손님 창 오른쪽에 들어가는 너비 */
    private static final int MIN_W = 128;
    private static final int MAX_W = 146;

    /** 판에 그릴 한 줄 */
    private record Item(ItemStack icon, String left, String right, int leftColor, int rightColor, boolean bold,
                        boolean highlight, String chip, int chipColor, boolean divider) {
        static Item line() {
            return new Item(null, "", "", 0, 0, false, false, null, 0, true);
        }
    }

    public static void render(DrawContext ctx, Screen screen) {
        if (!DdingMate.config().expert.baristaPanel) return;
        if (Barista.kind() == Barista.Kind.NONE) return;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer tr = client.textRenderer;
        SidePanel.Palette p = SidePanel.CAFE;
        List<Item> items = items(client, p);
        String title = "오늘의 주문";

        int w = Theme.boldWidth(tr, title) + 26;
        for (Item it : items) {
            if (it.divider()) continue;
            int lw = (it.icon() != null ? 14 : 0) + (it.chip() != null ? Theme.width(tr, it.chip()) + 9 : 0)
                    + (it.bold() ? Theme.boldWidth(tr, it.left()) : Theme.width(tr, it.left()));
            int rw = it.right().isEmpty() ? 0 : Theme.boldWidth(tr, it.right()) + 8;
            w = Math.max(w, lw + rw + 14);
        }
        w = Math.max(MIN_W, Math.min(MAX_W, w));
        int h = SidePanel.HEAD_H + 6;
        for (Item it : items) h += it.divider() ? 5 : ROW;
        h += 4;

        int[] at = SidePanel.place(screen.width, screen.height, w, h);
        int x = at[0], y = at[1];

        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        ItemStack cup = IconCache.forMaterial("아메리카노");
        SidePanel.frame(ctx, tr, x, y, w, h, p, cup == null ? new ItemStack(Items.FLOWER_POT) : cup, title);
        int cy = y + SidePanel.HEAD_H + 5;
        int index = 0;
        for (Item it : items) {
            if (it.divider()) {
                SidePanel.divider(ctx, x + 5, cy + 2, w - 10, p);
                cy += 5;
                continue;
            }
            if (it.highlight()) SidePanel.rowBg(ctx, x + 3, cy - 1, w - 6, ROW, p, index, true);
            int tx = x + 6;
            if (it.chip() != null) tx += SidePanel.chip(ctx, tr, tx, cy, it.chip(), it.chipColor(), 0xFFFFFFFF) + 3;
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
            index++;
        }
        ctx.getMatrices().pop();
    }

    private static List<Item> items(MinecraftClient client, SidePanel.Palette p) {
        List<Item> out = new ArrayList<>();
        Map<String, Integer> have = Barista.inventoryCoffee(client);
        // 지금 창이 인스턴트 커피 손님 창이면 맨 위에 인스턴트 커피
        if (Barista.kind() == Barista.Kind.INSTANT) {
            int n = have.getOrDefault(Barista.INSTANT, 0);
            out.add(new Item(icon(Barista.INSTANT), "인스턴트 커피 " + n + "잔", String.format("%,dG", (long) n * Barista.priceOf(Barista.INSTANT)),
                    p.ink(), n > 0 ? p.good() : p.soft(), true, true, null, 0, false));
            out.add(new Item(null, "1잔 20,000G · 제한 없이 팔아요", "", p.soft(), 0, false, false, null, 0, false));
        } else if (Barista.kind() == Barista.Kind.ORDER_ITEMS) {
            for (Barista.Line l : Barista.order()) {
                out.add(new Item(icon(l.coffee()), l.coffee(), l.have() + "/" + l.want(), p.ink(),
                        l.have() >= l.want() ? p.good() : p.bad(), false, true, null, 0, false));
            }
            out.add(new Item(null, "판매 합계", String.format("%,dG", Barista.total(Barista.order())), p.ink(), p.good(), false, false, null, 0, false));
        }
        List<Barista.Order> orders = Barista.orders();
        if (orders.isEmpty()) {
            if (!out.isEmpty()) out.add(Item.line());
            out.add(new Item(null, "손님(샐리·빈센트·웬디·아이비) 창을", "", p.soft(), 0, false, false, null, 0, false));
            out.add(new Item(null, "한 번 열면 주문을 기억해요", "", p.soft(), 0, false, false, null, 0, false));
            return out;
        }
        if (!out.isEmpty()) out.add(Item.line());
        boolean staleShown = false;
        for (Barista.Order o : orders) {
            boolean fresh = Barista.fresh(o);
            if (!fresh && !staleShown) {
                out.add(new Item(null, "03시 전에 읽은 주문 · 다시 열면 맞춰요", "", p.soft(), 0, false, false, null, 0, false));
                staleShown = true;
            }
            boolean here = o.customer.equals(Barista.currentCustomer());
            int ink = fresh ? p.ink() : p.soft();
            out.add(new Item(null, o.customer, o.cupsLeft() == 0 ? "주문 완료" : String.format("%,dG", o.remaining),
                    ink, o.cupsLeft() == 0 ? p.good() : fresh ? p.accent() : p.soft(), true, here, o.grade, gradeColor(o.grade), false));
            for (Barista.OrderLine l : o.lines) {
                int mine = have.getOrDefault(l.coffee, 0);
                String right = l.left() == 0 ? "다 팜" : "보유 " + mine + " · " + l.sold + "/" + l.want;
                int color = l.left() == 0 ? p.good() : !fresh ? p.soft() : mine >= l.left() ? p.good() : p.bad();
                out.add(new Item(icon(l.coffee), l.coffee, right, ink, color, false, here, null, 0, false));
            }
        }
        // 오늘 주문 모아 보기
        List<Barista.Need> needs = Barista.needs(have);
        if (!needs.isEmpty()) {
            out.add(Item.line());
            boolean any = false;
            for (Barista.Need n : needs) {
                if (n.toMake() <= 0) continue;
                any = true;
                out.add(new Item(icon(n.coffee()), n.coffee() + " 만들기", n.toMake() + "잔", p.ink(), p.bad(), false, false, null, 0, false));
            }
            if (!any) out.add(new Item(null, "주문한 커피를 다 갖췄어요", "", p.good(), 0, true, false, null, 0, false));
            out.add(new Item(null, "지금 팔면", String.format("%,dG", Barista.sellableNow(needs)), p.ink(), p.good(), true, false, null, 0, false));
            out.add(new Item(null, "남은 주문 합계", String.format("%,dG", Barista.remainingTotal()), p.soft(), p.accent(), false, false, null, 0, false));
        }
        return out;
    }

    private static ItemStack icon(String coffee) {
        ItemStack s = IconCache.forMaterial(coffee);
        return s == null ? new ItemStack(Items.FLOWER_POT) : s;
    }

    /** 위키의 손님 하트 색: C 빨강, B 갈색, A 검정, S 분홍 */
    public static int gradeColor(String grade) {
        return switch (grade) {
            case "C" -> 0xFFD0453F;
            case "B" -> 0xFF8A5530;
            case "A" -> 0xFF3B3B44;
            case "S" -> 0xFFD8649A;
            default -> 0xFF7A7A7A;
        };
    }
}
