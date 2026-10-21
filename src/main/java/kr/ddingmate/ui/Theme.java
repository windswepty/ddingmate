package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 띵타이쿤 창 디자인을 따라 그리는 도구.
 *
 * <p>서버 창(설정·가방 등)에서 읽어 낸 규칙: 검은 외곽선의 둥근 회청색 창(#D4D9E0), 하늘색 줄무늬 머리 띠,
 * 한 단계 어두운 둥근 줄(#A4ABB8)과 아래쪽 흰 반사선, 파란 켬 단추(#639BFF)와 회색 끔 단추, 눌린 탭은 짙은 남색,
 * 본문 글꼴 갈무리9 · 제목 글꼴 갈무리11 Bold.
 * 서버 리소스팩 그림은 쓰지 않고 색 면으로 직접 그린다. 글꼴은 OFL 로 배포되는 갈무리 원본을 모드에 넣었다.
 */
public final class Theme {

    // 창
    public static final int OUTLINE = 0xFF000000;
    public static final int WINDOW = 0xFFD9DEE5;
    public static final int WINDOW_LIGHT = 0xFFEAEEF3;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int ROW = 0xFFBCC3CE;
    public static final int ROW_HOVER = 0xFFCAD0D9;
    public static final int ROW_SHADE = 0xFF8F96A4;
    public static final int SLATE = 0xFF7C8390;
    public static final int SLATE_DARK = 0xFF656C79;
    public static final int INK = 0xFF23272D;
    public static final int SKY_EDGE = 0xFF9FB8D8;
    public static final int SKY_MID = 0xFFC9D8EA;

    // 파랑(켬·선택)
    public static final int BLUE = 0xFF3A6FD8;
    public static final int BLUE_LIGHT = 0xFF6B97EC;
    public static final int BLUE_MID = 0xFF2F5FB8;
    public static final int BLUE_DARK = 0xFF1F3F78;
    public static final int BLUE_DEEP = 0xFF14284F;

    // 회색 단추(끔·일반)
    public static final int GRAY_FACE = 0xFFC9CED6;
    public static final int GRAY_OUT = 0xFF515B70;
    public static final int GRAY_SHADE = 0xFF6A7488;
    /** 회색 단추 위 글자(OFF 등) */
    public static final int GRAY_TEXT = 0xFF3F4757;
    public static final int TRACK = 0xFF626774;

    // 상태색
    public static final int GREEN = 0xFF4FB54A;
    public static final int GREEN_DARK = 0xFF237A30;
    public static final int GOLD = 0xFFF2B531;
    public static final int RED = 0xFFE0524D;

    // 상태색: 어두운 판(HUD)용과 밝은 판용을 따로 둬서 어느 쪽에서도 대비 4.5:1 이상
    public static final int RED_ON_DARK = 0xFFFF7A75;
    public static final int GOLD_ON_DARK = 0xFFF2B531;
    public static final int BLUE_ON_DARK = 0xFF9FB8D8;
    public static final int GREEN_ON_DARK = 0xFF8BE08A;
    public static final int RED_ON_LIGHT = 0xFFB42A25;
    public static final int GOLD_ON_LIGHT = 0xFF855400;
    public static final int BLUE_ON_LIGHT = 0xFF2B55A4;
    public static final int GREEN_ON_LIGHT = 0xFF1F6B24;
    public static final int TEXT_ON_DARK = 0xFFEEF1F5;
    public static final int SOFT_ON_DARK = 0xFFA7B0BE;

    public static int red(boolean dark) {
        return dark ? RED_ON_DARK : RED_ON_LIGHT;
    }

    public static int gold(boolean dark) {
        return dark ? GOLD_ON_DARK : GOLD_ON_LIGHT;
    }

    public static int blue(boolean dark) {
        return dark ? BLUE_ON_DARK : BLUE_ON_LIGHT;
    }

    public static int green(boolean dark) {
        return dark ? GREEN_ON_DARK : GREEN_ON_LIGHT;
    }

    public static int text(boolean dark) {
        return dark ? TEXT_ON_DARK : TEXT;
    }

    public static int soft(boolean dark) {
        return dark ? SOFT_ON_DARK : TEXT_SOFT;
    }

    // 글자
    public static final int TEXT = INK;
    public static final int TEXT_SOFT = 0xFF474D59;
    public static final int TEXT_LIGHT = 0xFFFFFFFF;
    public static final int TEXT_DISABLED = 0xFF666C77;

    // 글꼴(assets/ddingmate/font): 설정에서 고른 글꼴의 body·bold·title(갈무리·온글잎 레디오 볼펜체·베리려원)
    private static final java.util.Map<String, Identifier> FONT_IDS = new java.util.HashMap<>();

    private static Identifier font(String kind) {
        kr.ddingmate.config.DdingMateConfig cfg = DdingMate.config();
        String suffix = cfg == null || cfg.font == null ? "" : cfg.font.suffix;
        return FONT_IDS.computeIfAbsent(kind + suffix, k -> Identifier.of(DdingMate.MOD_ID, k));
    }

    public static Identifier bodyFont() {
        return font("body");
    }

    private Theme() {}

    // ---------------------------------------------------------------- 글꼴

    private static boolean galmuri() {
        // 늘 갈무리: 모든 칸 너비를 갈무리 기준으로 맞춰 두어서, 바닐라 글꼴로 그리면 글자가 겹치고 잘린다(2026-10-16 인게임).
        // 예전 설정 파일의 galmuriFont=false 도 무시한다
        return true;
    }

    public static MutableText body(String s) {
        MutableText t = Text.literal(s);
        return galmuri() ? t.setStyle(Style.EMPTY.withFont(font("body"))) : t;
    }

    public static MutableText bold(String s) {
        MutableText t = Text.literal(s);
        return galmuri() ? t.setStyle(Style.EMPTY.withFont(font("bold"))) : t.setStyle(Style.EMPTY.withBold(true));
    }

    public static MutableText title(String s) {
        MutableText t = Text.literal(s);
        return galmuri() ? t.setStyle(Style.EMPTY.withFont(font("title"))) : t.setStyle(Style.EMPTY.withBold(true));
    }

    public static int width(TextRenderer tr, String s) {
        return tr.getWidth(body(s));
    }

    public static int boldWidth(TextRenderer tr, String s) {
        return tr.getWidth(bold(s));
    }

    public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, body(s), x, y, color, false);
    }

    public static void textShadow(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        ctx.drawText(tr, body(s), x, y, color, true);
    }

    /** 어두운 판 위에서만 그림자를 넣어 글자를 또렷하게 */
    public static void textShadow(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, boolean shadow) {
        ctx.drawText(tr, body(s), x, y, color, shadow);
    }

    public static void boldText(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, boolean shadow) {
        ctx.drawText(tr, bold(s), x, y, color, shadow);
    }

    /** 글자가 칸보다 길면 뒤를 … 로 줄인다(본문 글꼴 기준) */
    public static String ellipsize(TextRenderer tr, String s, int maxWidth) {
        return ellipsize(tr, s, maxWidth, false);
    }

    public static String ellipsize(TextRenderer tr, String s, int maxWidth, boolean boldFont) {
        java.util.function.ToIntFunction<String> w = boldFont ? x -> boldWidth(tr, x) : x -> width(tr, x);
        if (w.applyAsInt(s) <= maxWidth) return s;
        int end = s.length();
        while (end > 0 && w.applyAsInt(s.substring(0, end) + "…") > maxWidth) end--;
        return s.substring(0, end) + "…";
    }

    // ---------------------------------------------------------------- 아이템 이름(좁은 판)

    /** 서버 아이템 이름 앞의 등급 "(Legendary) 실바니움 만년필", "(Common)넬트 천체관측기" */
    private static final java.util.regex.Pattern GRADE =
            java.util.regex.Pattern.compile("^\\((Common|Normal|Rare|Epic|Legendary|Mythic)\\)\\s*");
    /** 등급의 짧은 표기(위키의 요리·물고기 등급 표기와 같은 말) */
    private static final java.util.Map<String, String> GRADE_KO = java.util.Map.of(
            "Common", "커먼", "Normal", "노멀", "Rare", "레어", "Epic", "에픽", "Legendary", "전설", "Mythic", "신화");
    private static final java.util.Map<String, Integer> GRADE_COLOR = java.util.Map.of(
            "커먼", 0xFF5E6573, "노멀", 0xFF2E7D32, "레어", 0xFF1F5FBF, "에픽", 0xFF7B3FB5, "전설", 0xFFB06400, "신화", 0xFFC0392B);

    /** "(Legendary) 실바니움 만년필" → "전설 실바니움 만년필"(등급이 없으면 그대로). 판 제목처럼 한 색으로 쓸 때 */
    public static String compactGrade(String name) {
        java.util.regex.Matcher m = GRADE.matcher(name);
        return m.find() ? GRADE_KO.get(m.group(1)) + " " + name.substring(m.end()) : name;
    }

    /** 그린 결과: 끝난 x 와 … 로 줄였는지(줄였으면 마우스를 올렸을 때 전체 이름을 보여 준다) */
    public record Drawn(int end, boolean cut) {}

    /**
     * 좁은 판의 아이템 이름: 앞의 등급을 짧은 색 글자(레어·전설…)로 바꿔 그리고, 그래도 길면 뒤를 … 로 줄인다.
     * prefix 는 이름 앞에 같은 색으로 붙이는 글("[핀] ")
     */
    public static Drawn itemName(DrawContext ctx, TextRenderer tr, String prefix, String name, int x, int y, int maxW, int color) {
        java.util.regex.Matcher m = GRADE.matcher(name);
        String grade = null, rest = name;
        if (m.find()) {
            grade = GRADE_KO.get(m.group(1));
            rest = name.substring(m.end());
        }
        int cx = x;
        if (!prefix.isEmpty()) {
            text(ctx, tr, prefix, cx, y, color);
            cx += width(tr, prefix);
        }
        if (grade != null) {
            text(ctx, tr, grade, cx, y, GRADE_COLOR.get(grade));
            cx += width(tr, grade) + 3;
        }
        String shown = ellipsize(tr, rest, Math.max(0, x + maxW - cx));
        text(ctx, tr, shown, cx, y, color);
        return new Drawn(cx + width(tr, shown), !shown.equals(rest));
    }

    /** itemName 으로 그렸을 때의 너비(줄이지 않았을 때) */
    public static int itemNameWidth(TextRenderer tr, String name) {
        java.util.regex.Matcher m = GRADE.matcher(name);
        return m.find() ? width(tr, GRADE_KO.get(m.group(1))) + 3 + width(tr, name.substring(m.end())) : width(tr, name);
    }

    /** 글을 너비에 맞춰 여러 줄로(띄어쓰기에서 끊고, 한 낱말이 너무 길면 글자 단위로, 줄바꿈 글자는 그대로 줄을 나눈다) */
    public static java.util.List<String> wrap(TextRenderer tr, String s, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String para : s.split("\n")) out.addAll(wrapLine(tr, para, maxWidth));
        return out;
    }

    private static java.util.List<String> wrapLine(TextRenderer tr, String s, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String cand = line.isEmpty() ? word : line + " " + word;
            if (width(tr, cand) <= maxWidth) {
                line.setLength(0);
                line.append(cand);
                continue;
            }
            if (!line.isEmpty()) out.add(line.toString());
            line.setLength(0);
            // 낱말 하나가 줄보다 길면 글자 단위로 자른다
            for (int cp : word.codePoints().toArray()) {
                String next = line + new String(Character.toChars(cp));
                if (width(tr, next) > maxWidth && !line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line.appendCodePoint(cp);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ---------------------------------------------------------------- 창

    /** 둥근 모서리(2칸) 외곽선 */
    public static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 2, y, x + w - 2, y + 1, color);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, color);
        ctx.fill(x, y + 2, x + 1, y + h - 2, color);
        ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, color);
        ctx.fill(x + 1, y + 1, x + 2, y + 2, color);
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, color);
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, color);
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color);
    }

    /** 1칸만 깎은 작은 둥근 외곽선(단추·줄) */
    public static void outlineSmall(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x + 1, y, x + w - 1, y + 1, color);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 창 몸통: 외곽선 + 회청색 + 위 반사선 + 아래 두 줄 테두리 */
    public static void window(DrawContext ctx, int x, int y, int w, int h) {
        outline(ctx, x, y, w, h, OUTLINE);
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, WINDOW);
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, WINDOW);
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, WINDOW);
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, WHITE);
        ctx.fill(x + 2, y + h - 4, x + w - 2, y + h - 3, SLATE);
        ctx.fill(x + 2, y + h - 3, x + w - 2, y + h - 1, 0xFFC0C6D0);
    }

    /** 창 위쪽 하늘색 머리 띠: 가장자리는 하늘색, 가운데는 거의 흰색. 세로 줄무늬로 결을 준다 */
    public static void skyHeader(DrawContext ctx, int x, int y, int w, int h) {
        for (int i = 0; i < w; i++) {
            double t = Math.abs((i + 0.5) / w - 0.5) * 2; // 가운데 0, 가장자리 1
            double k = Math.min(1, Math.max(0, (t - 0.35) / 0.65));
            int c = lerp(WINDOW_LIGHT, SKY_EDGE, k);
            if ((i / 2) % 3 == 0) c = lerp(c, WHITE, 0.25 * k);
            ctx.fill(x + i, y, x + i + 1, y + h, c);
        }
        ctx.fill(x, y + h, x + w, y + h + 1, SLATE_DARK);
    }

    /** 목록의 한 줄: 회청색 둥근 줄 + 위 그림자 + 아래 흰 반사선 */
    public static void row(DrawContext ctx, int x, int y, int w, int h, boolean hovered) {
        ctx.fill(x + 1, y, x + w - 1, y + h, hovered ? ROW_HOVER : ROW);
        ctx.fill(x, y + 1, x + 1, y + h - 1, hovered ? ROW_HOVER : ROW);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, hovered ? ROW_HOVER : ROW);
        ctx.fill(x + 1, y, x + w - 1, y + 1, ROW_SHADE);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, WINDOW_LIGHT);
    }

    public enum Tile { BLUE, BLUE_PRESSED, GRAY, SLATE }

    /** 단추 판. BLUE=주요 동작·선택, BLUE_PRESSED=눌린 탭, GRAY=일반, SLATE=비활성 */
    public static void tile(DrawContext ctx, int x, int y, int w, int h, Tile style, boolean hovered) {
        int out, face, top, bottom;
        switch (style) {
            case BLUE -> { out = BLUE_DEEP; face = hovered ? 0xFF4A7EE4 : BLUE; top = hovered ? 0xFF7FA6EF : BLUE_LIGHT; bottom = BLUE_MID; }
            case BLUE_PRESSED -> { out = BLUE_DEEP; face = BLUE_DARK; top = BLUE_DEEP; bottom = 0xFF2A4F8F; }
            case GRAY -> { out = GRAY_OUT; face = hovered ? 0xFFD4D9E0 : GRAY_FACE; top = WHITE; bottom = GRAY_SHADE; }
            default -> { out = 0xFF2B2F38; face = 0xFF5A6374; top = INK; bottom = 0xFF6B7487; }
        }
        outlineSmall(ctx, x, y, w, h, out);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, top);
        ctx.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, bottom);
    }

    /** 서버 설정 창의 ON/OFF 스위치 모양: 어두운 홈 위에 켜지면 오른쪽 파란 칸, 꺼지면 왼쪽 회색 칸 */
    public static void onOff(DrawContext ctx, TextRenderer tr, int x, int y, boolean on, boolean hovered) {
        int w = 34, h = 14;
        outlineSmall(ctx, x, y, w, h, 0xFF4A4E58);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, TRACK);
        int kw = 19;
        int kx = on ? x + w - kw - 1 : x + 1;
        tile(ctx, kx, y, kw, h, on ? Tile.BLUE : Tile.GRAY, hovered);
        String label = on ? "ON" : "OFF";
        int lw = boldWidth(tr, label);
        boldText(ctx, tr, label, kx + (kw - lw) / 2, y + 3, on ? TEXT_LIGHT : GRAY_TEXT, on);
    }

    /** 진행 막대. 다 차면 초록, 아니면 파랑 */
    public static void progress(DrawContext ctx, int x, int y, int w, int h, double ratio, int alpha, boolean dark) {
        ratio = Math.max(0, Math.min(1, ratio));
        ctx.fill(x, y, x + w, y + h, withAlpha(dark ? 0xFF2A2E35 : SLATE, alpha));
        int fw = (int) Math.round(w * ratio);
        if (fw > 0) ctx.fill(x, y, x + fw, y + h, withAlpha(ratio >= 1 ? GREEN : BLUE, alpha));
    }

    /** 입력 칸 바탕 */
    public static void field(DrawContext ctx, int x, int y, int w, int h, boolean focused) {
        outlineSmall(ctx, x, y, w, h, focused ? BLUE_MID : SLATE_DARK);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, WINDOW_LIGHT);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFC0C6D0);
        if (focused) ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, BLUE);
    }

    /** 내용 칸(창 안쪽의 한 단계 들어간 판) */
    public static void inset(DrawContext ctx, int x, int y, int w, int h) {
        outlineSmall(ctx, x, y, w, h, SLATE);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFD2D7DF);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, ROW_SHADE);
    }

    // ---------------------------------------------------------------- HUD

    /** 서버 HUD 처럼 어두운 반투명 판 + 얇은 테두리 + 위쪽 파란 선 */
    public static void hudDark(DrawContext ctx, int x, int y, int w, int h, int alpha) {
        outlineSmall(ctx, x, y, w, h, withAlpha(0xFF000000, alpha));
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(0xFF14171C, alpha));
        outlineSmall(ctx, x + 1, y + 1, w - 2, h - 2, withAlpha(0xFF3C4250, alpha));
    }

    /** 창처럼 밝은 HUD 판 */
    public static void hudLight(DrawContext ctx, int x, int y, int w, int h, int alpha) {
        outline(ctx, x, y, w, h, withAlpha(OUTLINE, alpha));
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, withAlpha(WINDOW, alpha));
        ctx.fill(x + 1, y + 2, x + w - 1, y + h - 2, withAlpha(WINDOW, alpha));
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, withAlpha(WHITE, alpha));
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, withAlpha(ROW_SHADE, alpha));
    }

    // ---------------------------------------------------------------- 아이콘

    /** 아이템 아이콘을 원하는 크기(픽셀)로 그린다. 16 이 원래 크기 */
    public static void item(DrawContext ctx, ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) return;
        if (size == 16) {
            ctx.drawItem(stack, x, y);
            return;
        }
        float s = size / 16f;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x, y, 0);
        ctx.getMatrices().scale(s, s, 1);
        ctx.drawItem(stack, 0, 0);
        ctx.getMatrices().pop();
    }

    /** 아이콘이 없을 때 쓰는 슬롯 + 물음표 */
    public static void unknownIcon(DrawContext ctx, TextRenderer tr, int x, int y, int size) {
        ctx.fill(x, y, x + size, y + size, ROW_SHADE);
        ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, ROW);
        int qw = boldWidth(tr, "?");
        boldText(ctx, tr, "?", x + (size - qw) / 2, y + (size - 8) / 2 + 1, WHITE, false);
    }

    /** 슬롯(아이콘 받침) */
    public static void slot(DrawContext ctx, int x, int y, int size) {
        outlineSmall(ctx, x, y, size, size, ROW_SHADE);
        ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, ROW);
        ctx.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, WINDOW_LIGHT);
    }

    // ---------------------------------------------------------------- 색

    public static int withAlpha(int argb, int alpha) {
        int a = ((argb >>> 24) * Math.max(0, Math.min(255, alpha))) / 255;
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
