package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버 창(바리스타 손님·밀키 상점·제작 시설) 옆에 붙이는 작은 판. 창마다 어울리는 색 묶음으로 그린다.
 * 카페: 크림색 몸통 + 갈색·크림 줄무늬 차양 머리(손님 창의 차양 그림과 맞춤), 부엌: 크림 + 토마토색, 공방: 띵메이트 기본 회청색.
 *
 * <p>크기(2026-10-23 사용자 요청): 판을 그리는 동안만 행렬을 sidePanelScale 배로 키우고, 판 좌표는 그 배율의
 * "가상 화면"(vw·vh) 기준으로 잡는다. 마우스도 같은 배율로 나눠 클릭·휠이 판과 맞는다. 판 위에서 Ctrl+휠로 바로 바꾼다.
 * 모양: "HUD 꾸미기 따라가기"면 HUD 판(NPC 세트 판·제목 줄·글자색 또는 기본 테마 색)으로 그린다.
 */
public final class SidePanel {

    private SidePanel() {}

    /**
     * @param stripe 머리 띠 줄무늬 두 번째 색(0 이면 줄무늬 없음)
     * @param skin   HUD 꾸미기의 NPC 판 그림으로 그릴 때 그 판(아니면 null)
     */
    public record Palette(int outline, int body, int header, int stripe, int headerText, int ink, int soft,
                          int accent, int good, int bad, int rowA, int rowB, HudSkins.Skin skin) {
        public Palette(int outline, int body, int header, int stripe, int headerText, int ink, int soft,
                       int accent, int good, int bad, int rowA, int rowB) {
            this(outline, body, header, stripe, headerText, ink, soft, accent, good, bad, rowA, rowB, null);
        }
    }

    /** 카페: 에스프레소 갈색 테두리, 라떼 크림 몸통, 캐러멜 강조 */
    public static final Palette CAFE = new Palette(0xFF2E1B10, 0xFFFBF1E3, 0xFF7A4A2A, 0xFFF3DDBE, 0xFFFFF7EA,
            0xFF3A2416, 0xFF6E5140, 0xFF9C5B1E, 0xFF2D6E2A, 0xFFA8322A, 0xFFF1E1CB, 0xFFFBF1E3);
    /** 부엌(밀키 요리 상점): 크림 몸통, 토마토색 머리 */
    public static final Palette KITCHEN = new Palette(0xFF3A1F14, 0xFFFFF7EE, 0xFFC4523A, 0xFFF6C9A8, 0xFFFFFFFF,
            0xFF3A2418, 0xFF6F5242, 0xFF9A5A00, 0xFF2D6E2A, 0xFFA8322A, 0xFFF6E6D4, 0xFFFFF7EE);
    /** 공방(제작 시설): 띵메이트 기본 색 */
    public static final Palette WORKSHOP = new Palette(Theme.OUTLINE, Theme.WINDOW, Theme.BLUE_MID, 0, Theme.WHITE,
            Theme.INK, Theme.TEXT_SOFT, Theme.BLUE_ON_LIGHT, Theme.GREEN_ON_LIGHT, Theme.RED_ON_LIGHT, 0xFFCDD3DC, Theme.WINDOW);

    public static final int HEAD_H = 16;

    // ---------------------------------------------------------------- 크기

    public static final int MIN_SCALE = 50, MAX_SCALE = 150;

    public static float scale() {
        DdingMateConfig cfg = DdingMate.config();
        return cfg == null ? 1f : cfg.sidePanelScale / 100f;
    }

    /** 판 좌표로 본 화면 너비·높이(배율로 나눈 가상 화면) */
    public static int vw(Screen screen) {
        return Math.round(screen.width / scale());
    }

    public static int vh(Screen screen) {
        return Math.round(screen.height / scale());
    }

    /** 실제 화면 거리(서버 창 가장자리까지 등)를 판 좌표로 */
    public static int off(int real) {
        return Math.round(real / scale());
    }

    /** 실제 마우스 좌표 → 판 좌표 */
    public static int mouse(double real) {
        return (int) Math.floor(real / scale());
    }

    /** 판을 그리기 시작: 배율만큼 키운다(끝나면 pop) */
    public static void push(DrawContext ctx) {
        float s = scale();
        ctx.getMatrices().push();
        ctx.getMatrices().scale(s, s, 1);
    }

    public static void pop(DrawContext ctx) {
        ctx.getMatrices().pop();
    }

    /** 이번 프레임에 그린 판들(판 좌표): Ctrl+휠로 크기를 바꿀 자리 */
    private static final List<int[]> DRAWN = new ArrayList<>();

    /** 서버 창 옆 판 그리기를 시작할 때 부른다(지난 프레임 자리 지우기) */
    public static void beginFrame() {
        DRAWN.clear();
    }

    /** 판 위에서 Ctrl+휠: 크기를 10%씩 바꾸고 true(창으로는 넘기지 않음) */
    public static boolean resizeScroll(double mouseX, double mouseY, double vertical) {
        if (!Screen.hasControlDown() || vertical == 0) return false;
        int mx = mouse(mouseX), my = mouse(mouseY);
        for (int[] r : DRAWN) {
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                DdingMateConfig cfg = DdingMate.config();
                int next = cfg.sidePanelScale + (vertical > 0 ? 10 : -10);
                cfg.sidePanelScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, next));
                cfg.save();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 자리

    /**
     * 서버 창 옆 자리 {x, y}(판 좌표, screenW·screenH 는 vw·vh). 손님 창 그림(가운데서 좌우 100칸)을 가리지 않는 오른쪽 → 왼쪽
     * → 바닐라 상자 창(좌우 88칸) 바로 옆 오른쪽 → 왼쪽 순서로 들어가는 곳을 고르고, 어디도 안 되면 화면 오른쪽 끝에 붙인다.
     */
    public static int[] place(int screenW, int screenH, int w, int h) {
        int mid = screenW / 2;
        int far = off(104), near = off(92);
        int x;
        if (mid + far + w <= screenW - 2) x = mid + far;
        else if (mid - far - w >= 2) x = mid - far - w;
        else if (mid + near + w <= screenW - 2) x = mid + near;
        else if (mid - near - w >= 2) x = mid - near - w;
        else x = Math.max(2, screenW - 2 - w);
        int y = Math.max(2, screenH / 2 - off(90));
        if (y + h > screenH - 2) y = Math.max(2, screenH - 2 - h);
        return new int[]{x, y};
    }

    /**
     * 판 너비 상한: 기본 150, 화면이 넓어 창 오른쪽에 남는 자리가 있으면 그만큼(최대 210) 넓혀 긴 이름을 줄이지 않는다.
     * 좁은 화면(854×480 등)에서는 판이 창을 더 덮지 않도록 150 그대로다
     */
    public static int maxWidth(int screenW) {
        return Math.max(150, Math.min(210, screenW - 2 - (screenW / 2 + off(104))));
    }

    // ---------------------------------------------------------------- 모양

    /** "HUD 꾸미기 따라가기"면 HUD 판 색(NPC 판이면 그 판 그림)으로, 아니면 창에 맞춘 색 그대로 */
    public static Palette themed(Palette own) {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null || !cfg.sidePanelFollowHud) return own;
        HudSkins.Skin s = HudSkins.panel();
        if (s != null) {
            HudSkins.Skin head = HudSkins.header() != null ? HudSkins.header() : s;
            int stripe = (s.text() & 0xFFFFFF) | 0x14000000;
            return new Palette(s.text(), 0, 0, 0, head.title(), s.text(), s.soft(), s.blue(), s.green(), s.red(), stripe, 0, s);
        }
        HudTheme t = HudTheme.current();
        if (t == HudTheme.CLASSIC) return own;
        boolean light = t.style == DdingMateConfig.HudStyle.LIGHT;
        int header = light ? (t.headMid != 0 ? t.headMid : Theme.BLUE_MID) : t.inner != 0 ? t.inner : Theme.lerp(t.body, t.accent, 0.3);
        int headerText = light ? (t.headMid != 0 ? t.title : Theme.WHITE) : t.title;
        return new Palette(t.outer != 0 ? t.outer : Theme.OUTLINE, t.body, header, 0, headerText, t.text, t.soft, t.blue, t.green, t.red,
                Theme.lerp(t.body, t.text, 0.07), t.body);
    }

    /** 판 몸통 + 머리 띠(아이콘 + 굵은 제목) */
    public static void frame(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, Palette p, ItemStack icon, String title) {
        DRAWN.add(new int[]{x, y, w, h});
        if (p.skin() != null) {
            // HUD 꾸미기 NPC 판: 판 그림 + 제목 줄 그림(서버 창 위라 진하게)
            HudSkins.drawPanel(ctx, p.skin(), x, y, w, h, 245);
            HudSkins.Skin head = HudSkins.header() != null ? HudSkins.header() : p.skin();
            HudSkins.drawHeader(ctx, head, x, y, w, 245);
            int tx = x + 6;
            if (icon != null) {
                Theme.item(ctx, icon, x + 4, y + 2, 10);
                tx = x + 17;
            }
            Theme.boldText(ctx, tr, title, tx, y + 3, p.headerText(), head.titleShadow());
            return;
        }
        Theme.outline(ctx, x, y, w, h, p.outline());
        ctx.fill(x + 2, y + 1, x + w - 2, y + h - 1, p.body());
        ctx.fill(x + 1, y + 2, x + 2, y + h - 2, p.body());
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, p.body());
        // 머리 띠: 줄무늬 차양이면 4칸 간격으로 두 색을 번갈아 칠하고, 아래 가장자리를 물결 모양으로 깎는다
        int top = y + 1, bottom = y + HEAD_H;
        for (int i = x + 1; i < x + w - 1; i++) {
            int c = p.stripe() != 0 && ((i - x) / 5) % 2 == 1 ? p.stripe() : p.header();
            int y0 = i == x + 1 || i == x + w - 2 ? top + 1 : top;
            int wave = p.stripe() != 0 && (i - x) % 5 == 2 ? 1 : 0;
            ctx.fill(i, y0, i + 1, bottom + wave, c);
        }
        ctx.fill(x + 1, bottom + 1, x + w - 1, bottom + 2, Theme.withAlpha(p.outline(), 60));
        int tx = x + 6;
        if (icon != null) {
            Theme.item(ctx, icon, x + 4, y + 3, 11);
            tx = x + 18;
        }
        // 줄무늬 위에서도 읽히게 제목 밑에 어두운 띠
        int tw = Theme.boldWidth(tr, title);
        if (p.stripe() != 0) ctx.fill(tx - 2, y + 3, tx + tw + 2, y + 13, Theme.withAlpha(p.header(), 235));
        Theme.boldText(ctx, tr, title, tx, y + 5, p.headerText(), false);
    }

    /** 번갈아 칠하는 줄 바탕 */
    public static void rowBg(DrawContext ctx, int x, int y, int w, int h, Palette p, int index, boolean highlight) {
        if (p.skin() != null) {
            // 판 그림 위: 옅은 줄무늬만, 고른 줄은 강조색을 옅게
            if (highlight) {
                ctx.fill(x, y, x + w, y + h, Theme.withAlpha(p.accent(), 0x38));
                ctx.fill(x, y, x + 2, y + h, p.accent());
            } else if (index % 2 == 0) {
                ctx.fill(x, y, x + w, y + h, p.rowA());
            }
            return;
        }
        int c = highlight ? Theme.lerp(p.rowA(), p.accent(), 0.18) : index % 2 == 0 ? p.rowA() : p.rowB();
        ctx.fill(x, y, x + w, y + h, c);
        if (highlight) ctx.fill(x, y, x + 2, y + h, p.accent());
    }

    /** 점선 구분선 */
    public static void divider(DrawContext ctx, int x, int y, int w, Palette p) {
        int c = Theme.withAlpha(p.soft(), 140);
        for (int i = 0; i < w; i += 3) ctx.fill(x + i, y, x + Math.min(w, i + 2), y + 1, c);
    }

    /** 작은 둥근 꼬리표. 그린 너비를 돌려준다 */
    public static int chip(DrawContext ctx, TextRenderer tr, int x, int y, String text, int bg, int fg) {
        int w = Theme.width(tr, text) + 6;
        Theme.outlineSmall(ctx, x, y, w, 10, Theme.lerp(bg, 0xFF000000, 0.35));
        ctx.fill(x + 1, y + 1, x + w - 1, y + 9, bg);
        Theme.text(ctx, tr, text, x + 3, y + 1, fg);
        return w;
    }
}
