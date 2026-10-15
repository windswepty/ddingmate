package kr.ddingmate.hud;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
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
    private static final int COLUMN_GAP = 4;
    private static final ItemStack CHEST = new ItemStack(Items.CHEST);

    private CounterHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (ScheduleHud.hidden(MinecraftClient.getInstance())) return;
        HudLayout.push(ctx, HudLayout.COUNTER);
        try {
            draw(ctx);
        } finally {
            HudLayout.pop(ctx);
        }
    }

    private static void draw(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig all = DdingMate.config();
        DdingMateConfig.Counter cfg = all.counter;
        List<MaterialCounter.PinView> views = MaterialCounter.views();
        if (ScheduleHud.hidden(client)) return;
        if (!cfg.enabled || views.isEmpty()) {
            HudLayout.placeholder(ctx, HudLayout.COUNTER, cfg.corner, CHEST, cfg.enabled ? "핀한 레시피가 없어요" : "꺼져 있어요");
            return;
        }
        String note = cfg.includeBags || cfg.includeStorage ? bagNote(cfg) : null;
        int stack = HudLayout.stacked(cfg.corner);
        if (all.hudStyle == DdingMateConfig.HudStyle.CLASSIC) {
            drawClassic(ctx, client.textRenderer, cfg, views, note, stack);
        } else {
            drawPanels(ctx, client.textRenderer, cfg, views, note, stack, all.hudOpacity * 255 / 100,
                    all.hudStyle == DdingMateConfig.HudStyle.DARK);
        }
    }

    // ---------------------------------------------------------------- 띵타이쿤풍 판

    private static void drawPanels(DrawContext ctx, TextRenderer tr, DdingMateConfig.Counter cfg,
                                   List<MaterialCounter.PinView> views, String note, int stack, int alpha, boolean dark) {
        int sh = HudLayout.vh(ctx);
        boolean top = cfg.corner == DdingMateConfig.Corner.TOP_LEFT || cfg.corner == DdingMateConfig.Corner.TOP_RIGHT;
        boolean left = cfg.corner == DdingMateConfig.Corner.TOP_LEFT || cfg.corner == DdingMateConfig.Corner.BOTTOM_LEFT;
        int limit;
        int customY = HudLayout.customTop(ctx, HudLayout.COUNTER, 60);
        int fixedH = HudLayout.heightLimit(HudLayout.COUNTER);
        if (fixedH > 0) {
            limit = fixedH; // 편집 화면에서 정한 높이: 넘치는 줄은 옆 칸으로
        } else if (customY >= 0) {
            limit = sh - customY - 4; // 옮긴 자리 아래로 남은 높이
        } else {
            int safe = top ? sh - HudLayout.bottomGap(left ? DdingMateConfig.Corner.BOTTOM_LEFT : DdingMateConfig.Corner.BOTTOM_RIGHT)
                    - HudLayout.topGap(cfg.corner) : sh - HudLayout.bottomGap(cfg.corner) - HudLayout.topGap();
            limit = safe - stack;
            // 첫 핀도 안 들어가면 어차피 옆 줄로 넘어가므로 화면 높이 전체를 쓴다
            if (stack > 0 && pinHeight(views.get(0)) + 4 > limit) limit = safe;
        }
        limit = Math.max(limit, fixedH > 0 ? HudLayout.MIN_HEIGHT : 60);

        // 높이를 정해 두었을 때만 핀 안의 줄까지 옆 칸으로 나눈다(기본은 핀 단위로만 옆 칸). 화면 밖으로 나가면 칸을 조금씩 키운다
        List<List<Segment>> columns;
        int n;
        int[] ws, hs;
        int totalW, tallest;
        int vw = HudLayout.vw(ctx);
        while (true) {
            columns = layout(views, limit, fixedH > 0);
            n = columns.size();
            ws = new int[n];
            hs = new int[n];
            totalW = 0;
            tallest = 0;
            for (int i = 0; i < n; i++) {
                String colNote = i == n - 1 ? note : null;
                ws[i] = columnWidth(tr, cfg, columns.get(i), colNote);
                hs[i] = columnHeight(columns.get(i), colNote);
                totalW += ws[i] + (i > 0 ? COLUMN_GAP : 0);
                tallest = Math.max(tallest, hs[i]);
            }
            if (fixedH <= 0 || n <= 1 || totalW <= vw - 4 || limit > 2000) break;
            limit += ROW_H * 2;
        }
        // 편집 화면에서 가로로 늘렸으면 마지막 칸을 그만큼 넓힌다
        int wide = HudLayout.widen(HudLayout.COUNTER, totalW);
        if (wide > totalW) {
            ws[n - 1] += wide - totalW;
            totalW = wide;
        }
        int[] o = HudLayout.place(ctx, HudLayout.COUNTER, cfg.corner, totalW, tallest);
        boolean bottomAlign = !top && !HudLayout.custom(HudLayout.COUNTER);
        int x = o[0];
        for (int i = 0; i < n; i++) {
            int h = fixedH > 0 ? tallest : hs[i];
            int y = bottomAlign ? o[1] + tallest - h : o[1];
            drawColumn(ctx, tr, cfg, columns.get(i), i == n - 1 ? note : null, x, y, ws[i], h, alpha, dark);
            x += ws[i] + COLUMN_GAP;
        }
    }

    /**
     * 칸 하나에 들어가는 핀 조각: 핀의 from~to 번째 줄. continued 면 앞 칸에서 이어지는 조각(제목에 "이어서")
     */
    public record Segment(MaterialCounter.PinView view, int from, int to, boolean continued) {
        public int height() {
            return ScheduleHud.HEAD_H + 2 + (to - from) * ROW_H + 2;
        }
    }

    /**
     * 핀들을 칸에 나눠 담는다. 핀이 통째로 들어가면 그대로, 남은 자리에 안 들어가면 새 칸에서 시작하고,
     * 빈 칸에도 안 들어갈 만큼 길면(높이를 줄여 둔 경우) 줄 단위로 잘라 옆 칸으로 이어 붙인다.
     */
    public static List<List<Segment>> layout(List<MaterialCounter.PinView> views, int limit, boolean splitRows) {
        List<List<Segment>> columns = new ArrayList<>();
        List<Segment> col = new ArrayList<>();
        int colH = 3;
        int perHead = ScheduleHud.HEAD_H + 4;
        for (MaterialCounter.PinView v : views) {
            int rows = shown(v).size();
            int whole = perHead + rows * ROW_H;
            if (!col.isEmpty() && colH + whole > limit && 3 + whole <= limit) {
                columns.add(col);
                col = new ArrayList<>();
                colH = 3;
            }
            if (!splitRows) {
                // 기본: 핀은 통째로(길면 그 칸에서 아래로 넘쳐도 그대로)
                if (!col.isEmpty() && colH + whole > limit) {
                    columns.add(col);
                    col = new ArrayList<>();
                    colH = 3;
                }
                Segment seg = new Segment(v, 0, rows, false);
                col.add(seg);
                colH += seg.height();
                continue;
            }
            int from = 0;
            boolean continued = false;
            while (from < rows || (rows == 0 && from == 0)) {
                int room = (limit - colH - perHead) / ROW_H;
                if (room < 1 && !col.isEmpty()) {
                    columns.add(col);
                    col = new ArrayList<>();
                    colH = 3;
                    room = (limit - colH - perHead) / ROW_H;
                }
                int take = Math.max(1, Math.min(rows - from, room));
                if (rows == 0) take = 0;
                Segment seg = new Segment(v, from, from + take, continued);
                col.add(seg);
                colH += seg.height();
                from += take;
                continued = true;
                if (rows == 0) break;
            }
        }
        columns.add(col);
        return columns;
    }

    /** HUD 한 줄: 재료 또는 그 아래 펼친 원재료(sub) */
    private record Shown(MaterialCounter.Line line, boolean sub) {}

    private static List<Shown> shown(MaterialCounter.PinView v) {
        List<Shown> out = new ArrayList<>();
        for (MaterialCounter.Line l : v.lines()) {
            out.add(new Shown(l, false));
            for (MaterialCounter.Line s : l.subs()) out.add(new Shown(s, true));
        }
        return out;
    }

    private static final int SUB_INDENT = 9;

    private static int pinHeight(MaterialCounter.PinView v) {
        return ScheduleHud.HEAD_H + 2 + shown(v).size() * ROW_H + 2;
    }

    private static int columnHeight(List<Segment> segs, String note) {
        int h = 3;
        for (Segment s : segs) h += s.height();
        return h + (note != null ? 12 : 0);
    }

    private record Widths(int name, int count, int extra) {}

    private static Widths widths(TextRenderer tr, DdingMateConfig.Counter cfg, List<Segment> segs) {
        int nameW = 0, countW = 0, extraW = 0;
        for (Segment sg : segs) {
            List<Shown> all = shown(sg.view());
            for (Shown sh : all.subList(sg.from(), sg.to())) {
                MaterialCounter.Line l = sh.line();
                nameW = Math.max(nameW, Theme.width(tr, l.name()) + (sh.sub() ? SUB_INDENT : 0));
                countW = Math.max(countW, Theme.boldWidth(tr, count(l)));
                extraW = Math.max(extraW, Theme.width(tr, extra(cfg, l)));
            }
        }
        return new Widths(nameW, countW, extraW);
    }

    private static int columnWidth(TextRenderer tr, DdingMateConfig.Counter cfg, List<Segment> segs, String note) {
        Widths w = widths(tr, cfg, segs);
        int headW = 0;
        for (Segment sg : segs) {
            headW = Math.max(headW, 16 + Theme.boldWidth(tr, headTitle(sg)) + GAP + chipWidth(tr, sg.view()));
        }
        int rowInner = ICON + 4 + w.name() + GAP + w.count() + (w.extra() > 0 ? GAP + w.extra() : 0);
        int innerW = Math.max(rowInner, headW);
        if (note != null) innerW = Math.max(innerW, Theme.width(tr, note));
        return innerW + 12;
    }

    private static int chipWidth(TextRenderer tr, MaterialCounter.PinView v) {
        return Theme.boldWidth(tr, progress(v)) + 8;
    }

    /** 칸 조각의 제목: 앞 칸에서 이어지면 "(이어서)" */
    private static String headTitle(Segment sg) {
        return title(sg.view()) + (sg.continued() ? " (이어서)" : "");
    }

    private static void drawColumn(DrawContext ctx, TextRenderer tr, DdingMateConfig.Counter cfg,
                                   List<Segment> segs, String note,
                                   int x, int y, int boxW, int boxH, int alpha, boolean dark) {
        Widths w = widths(tr, cfg, segs);
        ScheduleHud.panel(ctx, x, y, boxW, boxH, alpha, dark);
        int text = Theme.text(dark);
        int soft = Theme.soft(dark);
        int cy = y;
        for (Segment sg : segs) {
            MaterialCounter.PinView v = sg.view();
            ItemStack headIcon = IconCache.forRecipe(v.recipe()); // 제목 옆: 만드는 아이템 모양(첫 재료 아님)
            if (headIcon == null) headIcon = CHEST;
            ScheduleHud.header(ctx, tr, x, cy, boxW, headTitle(sg), headIcon, dark, alpha);
            // 진행 꼬리표
            String p = progress(v);
            int chipW = chipWidth(tr, v);
            int chipX = x + boxW - 4 - chipW;
            ctx.fill(chipX, cy + 2, chipX + chipW, cy + 12, v.done() ? Theme.GREEN_DARK : Theme.BLUE_MID);
            ctx.fill(chipX, cy + 2, chipX + chipW, cy + 3, v.done() ? 0xFF7DD379 : Theme.BLUE);
            Theme.boldText(ctx, tr, p, chipX + 4, cy + 3, Theme.WHITE, false);
            cy += ScheduleHud.HEAD_H + 2;
            for (Shown sh : shown(v).subList(sg.from(), sg.to())) {
                MaterialCounter.Line l = sh.line();
                int base = x + 6;
                int rx = base + (sh.sub() ? SUB_INDENT : 0);
                if (sh.sub()) {
                    // 위 재료에서 내려오는 갈래 선
                    int lc = Theme.withAlpha(soft, 200);
                    ctx.fill(base + 4, cy - 3, base + 5, cy + 6, lc);
                    ctx.fill(base + 4, cy + 5, rx - 1, cy + 6, lc);
                }
                ItemStack icon = IconCache.forMaterial(l.name());
                if (icon != null) Theme.item(ctx, icon, rx, cy + 1, ICON);
                else Theme.unknownIcon(ctx, tr, rx, cy + 1, ICON);
                int color = l.done() ? Theme.green(dark) : l.have() == 0 ? soft : text;
                if (sh.sub() && !l.done()) color = soft;
                Theme.textShadow(ctx, tr, l.name(), rx + ICON + 4, cy + 1, color, dark);
                String c = count(l);
                int countRight = base + ICON + 4 + w.name() + GAP + w.count();
                Theme.boldText(ctx, tr, c, countRight - Theme.boldWidth(tr, c), cy + 1, color, false);
                String ex = extra(cfg, l);
                if (!ex.isEmpty()) Theme.textShadow(ctx, tr, ex, countRight + GAP, cy + 1, soft, dark);
                int barX = rx + ICON + 4;
                Theme.progress(ctx, barX, cy + 10, countRight - barX, 2, (double) l.have() / Math.max(1, l.need()), alpha, dark);
                cy += ROW_H;
            }
            cy += 2;
        }
        if (note != null) Theme.textShadow(ctx, tr, note, x + 6, y + boxH - 12, soft, dark);
    }

    // ---------------------------------------------------------------- 예전 방식(반투명 검은 상자)

    private record Cell(String left, String right, String extra, int color) {}

    private static void drawClassic(DrawContext ctx, TextRenderer tr, DdingMateConfig.Counter cfg,
                                    List<MaterialCounter.PinView> views, String note, int stack) {
        int pad = 3, lineH = 10;
        List<Cell> rows = new ArrayList<>();
        for (MaterialCounter.PinView v : views) {
            rows.add(new Cell(title(v), progress(v), "", v.done() ? 0xFF7CFC7C : 0xFFFFE066));
            for (Shown sh : shown(v)) {
                MaterialCounter.Line l = sh.line();
                int color = l.done() ? 0xFF7CFC7C : l.have() == 0 || sh.sub() ? 0xFFAAAAAA : 0xFFFFFFFF;
                rows.add(new Cell((sh.sub() ? "   └ " : " ") + l.name(), count(l), extra(cfg, l), color));
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
        boxW = HudLayout.widen(HudLayout.COUNTER, boxW);
        int[] o = HudLayout.place(ctx, HudLayout.COUNTER, cfg.corner, boxW, boxH);
        int x = o[0], y = o[1];
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
        return MaterialCounter.title(v.pin(), v.recipe());
    }

    private static String progress(MaterialCounter.PinView v) {
        return v.done() ? "준비 완료" : v.doneCount() + "/" + v.lines().size();
    }

    /** 가방·저장소에서 온 개수 표시: "가방 28 · 저장 1200" */
    private static String extra(DdingMateConfig.Counter cfg, MaterialCounter.Line l) {
        String bags = cfg.includeBags && l.bags() > 0 ? "가방 " + amount(l.bags()) : "";
        String store = cfg.includeStorage && l.storage() > 0 ? "저장소 " + amount(l.storage()) : "";
        if (bags.isEmpty()) return store;
        return store.isEmpty() ? bags : bags + " · " + store;
    }

    private static String count(MaterialCounter.Line l) {
        return amount(l.have()) + "/" + amount(l.need());
    }

    /** 한 세트 = 64개 */
    public static final int SET = 64;

    /** 세트 보기를 켰으면 80 → "1세트 16", 128 → "2세트", 40 → "40". 꺼져 있으면 그대로 */
    public static String amount(int n) {
        if (!DdingMate.config().counter.showSets || n < SET) return String.valueOf(n);
        int sets = n / SET, rest = n % SET;
        return rest == 0 ? sets + "세트" : sets + "세트 " + rest;
    }

    private static String bagNote(DdingMateConfig.Counter cfg) {
        if (cfg.includeBags && BagTracker.openState() == BagTracker.OpenState.UNKNOWN_BAG) {
            return "열린 가방 번호를 몰라 기록하지 않아요";
        }
        List<String> parts = new ArrayList<>();
        if (cfg.includeBags) {
            int n = BagTracker.recordedCount();
            parts.add(n == 0 ? "가방을 한 번씩 열면 가방 재료도 합산돼요"
                    : "가방 " + n + "개 기록");
        }
        if (cfg.includeStorage && BagTracker.storageSeenAt() > 0) {
            parts.add("저장소 " + ago(BagTracker.storageSeenAt()));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    public static String ago(long ms) {
        long min = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        if (min < 60 * 24) return (min / 60) + "시간 전";
        return (min / (60 * 24)) + "일 전";
    }
}
