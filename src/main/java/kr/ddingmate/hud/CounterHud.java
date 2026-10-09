package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.IconCache;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 핀으로 고른 레시피의 재료 현황. 일정 HUD 와 같은 구석이면 그 아래(아래쪽 구석이면 위)에 붙고,
 * 길어지면 채팅창·핫바를 가리지 않도록 옆 칸으로 넘어간다.
 */
public final class CounterHud {

    private static final int ICON = 10;
    private static final int ROW_H = 14;
    private static final int GAP = 8;
    /** 왼쪽 아래 채팅창 자리(기본 높이)와 겹치지 않도록 이만큼은 비워 둔다 */
    private static final int CHAT_ZONE = 135;
    private static final int COLUMN_GAP = 4;
    private static final ItemStack CHEST = new ItemStack(Items.CHEST);

    private CounterHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        AlarmConfig all = DdingMate.config();
        AlarmConfig.Counter cfg = all.counter;
        List<MaterialCounter.PinView> views = MaterialCounter.views();
        if (!cfg.enabled || views.isEmpty() || AlarmHud.hidden(client)) return;
        String note = cfg.includeBags ? bagNote() : null;
        int stack = AlarmHud.drawnCorner == cfg.corner ? AlarmHud.drawnHeight : 0;
        if (all.hudStyle == AlarmConfig.HudStyle.CLASSIC) {
            drawClassic(ctx, client.textRenderer, cfg, views, note, stack);
        } else {
            drawPanels(ctx, client.textRenderer, cfg, views, note, stack, all.hudOpacity * 255 / 100,
                    all.hudStyle == AlarmConfig.HudStyle.DARK);
        }
    }

    // ---------------------------------------------------------------- 띵타이쿤풍 판

    private static void drawPanels(DrawContext ctx, TextRenderer tr, AlarmConfig.Counter cfg,
                                   List<MaterialCounter.PinView> views, String note, int stack, int alpha, boolean dark) {
        int sh = ctx.getScaledWindowHeight();
        boolean top = cfg.corner == AlarmConfig.Corner.TOP_LEFT || cfg.corner == AlarmConfig.Corner.TOP_RIGHT;
        boolean left = cfg.corner == AlarmConfig.Corner.TOP_LEFT || cfg.corner == AlarmConfig.Corner.BOTTOM_LEFT;
        int limit = top
                ? (left ? sh - CHAT_ZONE : sh - 30) - (AlarmHud.MARGIN + stack)
                : sh - AlarmHud.BOTTOM_GAP - stack - AlarmHud.MARGIN;
        limit = Math.max(limit, 60);

        List<List<MaterialCounter.PinView>> columns = new ArrayList<>();
        List<MaterialCounter.PinView> col = new ArrayList<>();
        int colH = 4;
        for (MaterialCounter.PinView v : views) {
            int h = pinHeight(v);
            if (!col.isEmpty() && colH + h > limit) {
                columns.add(col);
                col = new ArrayList<>();
                colH = 4;
            }
            col.add(v);
            colH += h;
        }
        columns.add(col);

        int offset = 0;
        for (int i = 0; i < columns.size(); i++) {
            String colNote = i == columns.size() - 1 ? note : null;
            List<MaterialCounter.PinView> pins = columns.get(i);
            int boxW = columnWidth(tr, cfg, pins, colNote);
            int boxH = columnHeight(pins, colNote);
            int x = left ? AlarmHud.MARGIN + offset : ctx.getScaledWindowWidth() - AlarmHud.MARGIN - offset - boxW;
            int y = AlarmHud.cornerY(ctx, cfg.corner, boxH, stack);
            drawColumn(ctx, tr, cfg, pins, colNote, x, y, boxW, boxH, alpha, dark);
            offset += boxW + COLUMN_GAP;
        }
    }

    private static int pinHeight(MaterialCounter.PinView v) {
        return AlarmHud.HEAD_H + 2 + v.lines().size() * ROW_H + 2;
    }

    private static int columnHeight(List<MaterialCounter.PinView> pins, String note) {
        int h = 3;
        for (MaterialCounter.PinView v : pins) h += pinHeight(v);
        return h + (note != null ? 12 : 0);
    }

    private record Widths(int name, int count, int extra) {}

    private static Widths widths(TextRenderer tr, AlarmConfig.Counter cfg, List<MaterialCounter.PinView> pins) {
        int nameW = 0, countW = 0, extraW = 0;
        for (MaterialCounter.PinView v : pins) {
            for (MaterialCounter.Line l : v.lines()) {
                nameW = Math.max(nameW, Theme.width(tr, l.name()));
                countW = Math.max(countW, Theme.boldWidth(tr, count(l)));
                if (cfg.includeBags && l.bags() > 0) extraW = Math.max(extraW, Theme.width(tr, "가방 " + l.bags()));
            }
        }
        return new Widths(nameW, countW, extraW);
    }

    private static int columnWidth(TextRenderer tr, AlarmConfig.Counter cfg, List<MaterialCounter.PinView> pins, String note) {
        Widths w = widths(tr, cfg, pins);
        int headW = 0;
        for (MaterialCounter.PinView v : pins) {
            headW = Math.max(headW, 16 + Theme.boldWidth(tr, title(v)) + GAP + chipWidth(tr, v));
        }
        int rowInner = ICON + 4 + w.name() + GAP + w.count() + (w.extra() > 0 ? GAP + w.extra() : 0);
        int innerW = Math.max(rowInner, headW);
        if (note != null) innerW = Math.max(innerW, Theme.width(tr, note));
        return innerW + 12;
    }

    private static int chipWidth(TextRenderer tr, MaterialCounter.PinView v) {
        return Theme.boldWidth(tr, progress(v)) + 8;
    }

    private static void drawColumn(DrawContext ctx, TextRenderer tr, AlarmConfig.Counter cfg,
                                   List<MaterialCounter.PinView> views, String note,
                                   int x, int y, int boxW, int boxH, int alpha, boolean dark) {
        Widths w = widths(tr, cfg, views);
        AlarmHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        int text = dark ? 0xFFE8ECF2 : Theme.TEXT;
        int soft = dark ? 0xFF9AA3B2 : Theme.TEXT_SOFT;
        int cy = y;
        for (MaterialCounter.PinView v : views) {
            ItemStack headIcon = v.recipe().materials.isEmpty() ? CHEST : IconCache.forMaterial(v.recipe().materials.get(0).name);
            AlarmHud.header(ctx, tr, x, cy, boxW, title(v), headIcon == null ? CHEST : headIcon, dark, alpha);
            // 진행 꼬리표
            String p = progress(v);
            int chipW = chipWidth(tr, v);
            int chipX = x + boxW - 4 - chipW;
            ctx.fill(chipX, cy + 2, chipX + chipW, cy + 12, v.done() ? Theme.GREEN : Theme.BLUE_MID);
            ctx.fill(chipX, cy + 2, chipX + chipW, cy + 3, v.done() ? 0xFF7DD379 : Theme.BLUE);
            Theme.boldText(ctx, tr, p, chipX + 4, cy + 3, Theme.WHITE, false);
            cy += AlarmHud.HEAD_H + 2;
            for (MaterialCounter.Line l : v.lines()) {
                int rx = x + 6;
                ItemStack icon = IconCache.forMaterial(l.name());
                if (icon != null) Theme.item(ctx, icon, rx, cy + 1, ICON);
                else Theme.unknownIcon(ctx, tr, rx, cy + 1, ICON);
                int color = l.done() ? (dark ? 0xFF8BE08A : Theme.GREEN_DARK) : l.have() == 0 ? soft : text;
                Theme.text(ctx, tr, l.name(), rx + ICON + 4, cy + 1, color);
                String c = count(l);
                int countRight = rx + ICON + 4 + w.name() + GAP + w.count();
                Theme.boldText(ctx, tr, c, countRight - Theme.boldWidth(tr, c), cy + 1, color, false);
                if (cfg.includeBags && l.bags() > 0) {
                    Theme.text(ctx, tr, "가방 " + l.bags(), countRight + GAP, cy + 1, soft);
                }
                int barX = rx + ICON + 4;
                Theme.progress(ctx, barX, cy + 10, countRight - barX, 2, (double) l.have() / Math.max(1, l.need()), alpha, dark);
                cy += ROW_H;
            }
            cy += 2;
        }
        if (note != null) Theme.text(ctx, tr, note, x + 6, cy + 1, soft);
    }

    // ---------------------------------------------------------------- 예전 방식(반투명 검은 상자)

    private record Cell(String left, String right, String extra, int color) {}

    private static void drawClassic(DrawContext ctx, TextRenderer tr, AlarmConfig.Counter cfg,
                                    List<MaterialCounter.PinView> views, String note, int stack) {
        int pad = 3, lineH = 10;
        List<Cell> rows = new ArrayList<>();
        for (MaterialCounter.PinView v : views) {
            rows.add(new Cell(title(v), progress(v), "", v.done() ? 0xFF7CFC7C : 0xFFFFE066));
            for (MaterialCounter.Line l : v.lines()) {
                int color = l.done() ? 0xFF7CFC7C : l.have() == 0 ? 0xFFAAAAAA : 0xFFFFFFFF;
                String extra = cfg.includeBags && l.bags() > 0 ? "가방 " + l.bags() : "";
                rows.add(new Cell(" " + l.name(), count(l), extra, color));
            }
        }
        int leftW = 0, rightW = 0, extraW = 0;
        for (Cell c : rows) {
            leftW = Math.max(leftW, tr.getWidth(c.left()));
            rightW = Math.max(rightW, tr.getWidth(c.right()));
            extraW = Math.max(extraW, tr.getWidth(c.extra()));
        }
        int contentW = leftW + GAP + rightW + (extraW > 0 ? GAP + extraW : 0);
        if (note != null) contentW = Math.max(contentW, tr.getWidth(note));
        int lines = rows.size() + (note != null ? 1 : 0);
        int boxW = contentW + pad * 2;
        int boxH = lines * lineH + pad * 2 - 1;
        int x = AlarmHud.cornerX(ctx, cfg.corner, boxW);
        int y = AlarmHud.cornerY(ctx, cfg.corner, boxH, stack);
        ctx.fill(x, y, x + boxW, y + boxH, 0x80000000);
        int ty = y + pad;
        for (Cell c : rows) {
            ctx.drawTextWithShadow(tr, c.left(), x + pad, ty, c.color());
            ctx.drawTextWithShadow(tr, c.right(), x + pad + leftW + GAP + rightW - tr.getWidth(c.right()), ty, c.color());
            if (!c.extra().isEmpty()) ctx.drawTextWithShadow(tr, c.extra(), x + pad + leftW + GAP + rightW + GAP, ty, 0xFF9A9A9A);
            ty += lineH;
        }
        if (note != null) ctx.drawTextWithShadow(tr, note, x + pad, ty, 0xFF9A9A9A);
    }

    // ---------------------------------------------------------------- 글자

    private static String title(MaterialCounter.PinView v) {
        return v.recipe().displayName() + " ×" + v.pin().crafts;
    }

    private static String progress(MaterialCounter.PinView v) {
        return v.done() ? "준비 완료" : v.doneCount() + "/" + v.lines().size();
    }

    private static String count(MaterialCounter.Line l) {
        return l.have() + "/" + l.need();
    }

    private static String bagNote() {
        if (BagTracker.openState() == BagTracker.OpenState.UNKNOWN_BAG) {
            return "열린 가방 번호를 몰라 기록하지 않아요";
        }
        int n = BagTracker.recordedCount();
        if (n == 0) return "가방을 한 번씩 열면 가방 재료도 합산돼요";
        return "가방 " + n + "개 기록 · 가장 오래된 기록 " + ago(BagTracker.oldestSeenAt());
    }

    static String ago(long ms) {
        long min = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        if (min < 60 * 24) return (min / 60) + "시간 전";
        return (min / (60 * 24)) + "일 전";
    }
}
