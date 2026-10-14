package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;

/** 손님 주문 창 옆 판: 원하는 커피(가진 잔 수 / 원하는 잔 수)와 판매 합계 */
public final class BaristaPanel {

    private BaristaPanel() {}

    private static final int ROW = 13;
    /** 상자형 창 너비(바닐라) */
    private static final int CONTAINER_W = 176;

    public static void render(DrawContext ctx, Screen screen) {
        if (!DdingMate.config().expert.baristaPanel) return;
        List<Barista.Line> lines = Barista.order();
        if (lines.isEmpty()) return;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String title = "손님 주문";
        String sum = String.format("판매 합계 %,dG", Barista.total(lines));
        String full = String.format("다 갖추면 %,dG", Barista.fullTotal(lines));
        int w = Math.max(Theme.boldWidth(tr, title), Math.max(Theme.width(tr, sum), Theme.width(tr, full)));
        for (Barista.Line l : lines) w = Math.max(w, 14 + Theme.width(tr, l.coffee()) + 8 + Theme.boldWidth(tr, l.have() + "/" + l.want()));
        w += 12;
        int h = 16 + lines.size() * ROW + 26;
        // 상자 창 오른쪽에, 자리가 없으면 왼쪽에
        int right = screen.width / 2 + CONTAINER_W / 2 + 4;
        int x = right + w <= screen.width - 2 ? right : Math.max(2, screen.width / 2 - CONTAINER_W / 2 - 4 - w);
        int y = Math.max(2, screen.height / 2 - 83);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 300);
        Theme.window(ctx, x, y, w, h);
        Theme.boldText(ctx, tr, title, x + 6, y + 5, Theme.INK, false);
        int cy = y + 17;
        for (Barista.Line l : lines) {
            ItemStack icon = IconCache.forMaterial(l.coffee());
            Theme.item(ctx, icon == null ? new ItemStack(Items.FLOWER_POT) : icon, x + 5, cy - 1, 10);
            Theme.text(ctx, tr, l.coffee(), x + 18, cy, Theme.TEXT);
            String n = l.have() + "/" + l.want();
            Theme.boldText(ctx, tr, n, x + w - 6 - Theme.boldWidth(tr, n), cy, l.have() >= l.want() ? Theme.GREEN : Theme.RED, false);
            cy += ROW;
        }
        Theme.text(ctx, tr, sum, x + 6, cy + 2, Theme.GREEN_DARK);
        Theme.text(ctx, tr, full, x + 6, cy + 13, Theme.TEXT_SOFT);
        ctx.getMatrices().pop();
    }
}
