package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.config.DdingMateConfig.FontStyle;
import kr.ddingmate.hud.HudPreview;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 글꼴 한눈에 보기(2026-10-23 사용자 요청: "폰트 선택도 HUD 테마 설정처럼 예시 화면을 보여 주면 좋겠다").
 * 글꼴마다 카드 하나: 그 글꼴로 쓴 이름 · 예문 · 숫자/영문, 그리고 지금 HUD 꾸미기 그대로의 일정 판.
 * 누르면 바로 그 글꼴로 바뀐다. 오른쪽은 지금 고른 글꼴을 크게 + "게임 전체에 적용" 켜고 끄기.
 * 카드는 그리는 동안만 설정의 글꼴을 그 후보로 바꿔 끼우고 곧바로 되돌린다(HudLookScreen 과 같은 방식).
 */
public class FontLookScreen extends Screen {
    /** 글꼴 카드. custom 이 비어 있지 않으면 내 글꼴·띵타이쿤 글꼴(UserFonts 키) */
    private record Card(String group, FontStyle font, String custom, String sub) {
        Card(String group, FontStyle font, String sub) {
            this(group, font, "", sub);
        }

        String key() {
            return custom.isEmpty() ? font.name() : custom;
        }

        String label() {
            return custom.isEmpty() ? font.label : kr.ddingmate.ui.UserFonts.label(custom);
        }
    }

    /** 배치된 카드(그리드 좌표, 스크롤 전) 또는 묶음 제목(card == null) */
    private record Placed(Card card, String header, int x, int y) {
    }

    private static final int CARD_W = 172, PANEL_W = 150, ROWS = 2, HEAD_H = 16, TOP = 44;
    private static final String SAMPLE = "띵타이쿤에서 오늘도 즐겁게!";
    private static final String DIGITS = "0123456789 · ABC abc · 12:30";
    private final Screen parent;
    private double scroll;
    private int gridX, gridY, gridW, gridH, cols;

    public FontLookScreen(Screen parent) {
        super(Text.literal("글꼴"));
        this.parent = parent;
    }

    private static DdingMateConfig cfg() {
        return DdingMate.config();
    }

    private static int cardH() {
        return 50 + HudPreview.height(ROWS) + 12; // 판은 cy+50 부터, 아래 여백 6(카드 간격 6 포함)
    }

    private static List<Card> cards() {
        List<Card> out = new ArrayList<>();
        out.add(new Card("도트", FontStyle.GALMURI, "기본 · 또렷한 도트"));
        out.add(new Card("고딕", FontStyle.GMARKET, "반듯한 고딕"));
        out.add(new Card("손글씨", FontStyle.REDIO, "온글잎 · 볼펜 손글씨"));
        out.add(new Card("손글씨", FontStyle.BERRY, "온글잎 · 동글동글"));
        out.add(new Card("손글씨", FontStyle.NARI, "온글잎 · 귀여운 손글씨"));
        out.add(new Card("손글씨", FontStyle.MONGTORI, "그리운 · 몽글몽글"));
        out.add(new Card("손글씨", FontStyle.DDOBAK, "그리운 · 또박또박"));
        // 게임 기본 글꼴(띵타이쿤 리소스팩·내 글꼴 리소스팩) 따라가기 · 내가 넣은 글꼴(이 컴퓨터에서만)
        out.add(new Card("게임 · 내 글꼴", FontStyle.GALMURI, kr.ddingmate.ui.UserFonts.GAME, "게임 글꼴(띵타이쿤 리소스팩) 따라가기"));
        for (kr.ddingmate.ui.UserFonts.Entry e : kr.ddingmate.ui.UserFonts.entries()) {
            out.add(new Card("게임 · 내 글꼴", FontStyle.GALMURI, e.key(), "내가 넣은 글꼴 · 나만 보여요"));
        }
        return out;
    }

    /** 카드를 묶음마다 새 줄에서 시작하고 묶음 제목을 그 위에 둔다 */
    private List<Placed> place(List<Card> cards) {
        List<Placed> out = new ArrayList<>();
        int y = 0, col = 0, h = cardH();
        String group = null;
        for (Card c : cards) {
            if (!c.group.equals(group)) {
                if (col > 0) y += h;
                col = 0;
                out.add(new Placed(null, c.group, 0, y));
                y += HEAD_H;
                group = c.group;
            } else if (col >= cols) {
                col = 0;
                y += h;
            }
            out.add(new Placed(c, null, col * CARD_W, y));
            col++;
        }
        return out;
    }

    private static int contentHeight(List<Placed> placed) {
        int h = 0;
        for (Placed p : placed) h = Math.max(h, p.y + (p.card != null ? cardH() : HEAD_H));
        return h;
    }

    // ---------------------------------------------------------------- 그리기

    private int previewW() {
        return width >= 560 ? 200 : 0;
    }

    private void layout() {
        gridX = 8;
        gridY = TOP;
        gridW = width - 16 - previewW() - (previewW() > 0 ? 8 : 0);
        gridH = height - gridY - 28;
        cols = Math.max(1, gridW / CARD_W);
    }

    /** 오른쪽 "게임 전체에 적용" 단추 자리 */
    private int[] everywhereButton() {
        int pw = previewW();
        if (pw <= 0) return null;
        int px = width - pw - 8;
        return new int[]{px + 8, gridY + gridH - 26, pw - 16, 18};
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        layout();
        ctx.fill(0, 0, width, height, 0xD0101218);
        ctx.fill(0, 0, width, gridY - 2, 0xF6101218);
        Theme.boldText(ctx, textRenderer, "글꼴 한눈에 보기", 10, 8, Theme.WHITE, true);
        Theme.textShadow(ctx, textRenderer, "카드를 누르면 바로 그 글꼴로 바뀌어요. 띵메이트 창 · HUD · 알림 글자예요.", 10, 20, 0xFFB8C0CC, true);
        DdingMateConfig c = cfg();
        FontStyle keep = c.font;
        String keepCustom = c.customFont;
        String chosenKey = currentKey();
        List<Placed> placed = place(cards());
        double max = Math.max(0, contentHeight(placed) - gridH);
        scroll = Math.max(0, Math.min(max, scroll));
        int h = cardH();
        ctx.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        try {
            for (Placed p : placed) {
                int cx = gridX + p.x;
                int cy = gridY + p.y - (int) scroll;
                if (p.card == null) {
                    if (cy + HEAD_H < gridY || cy > gridY + gridH) continue;
                    c.font = keep;
                    c.customFont = keepCustom;
                    Theme.boldText(ctx, textRenderer, p.header, cx + 2, cy + 4, 0xFFF2C230, true);
                    ctx.fill(cx + 4 + Theme.boldWidth(textRenderer, p.header), cy + 8, gridX + gridW - 4, cy + 9, 0x60F2C230);
                    continue;
                }
                if (cy + h < gridY || cy > gridY + gridH) continue;
                Card card = p.card;
                boolean chosen = card.key().equals(chosenKey);
                boolean hover = mouseX >= cx && mouseX < cx + CARD_W - 6 && mouseY >= cy && mouseY < cy + h - 6
                        && mouseY >= gridY && mouseY < gridY + gridH;
                ctx.fill(cx, cy, cx + CARD_W - 6, cy + h - 6, chosen ? 0xFF3A3420 : hover ? 0xFF2E3440 : 0xFF20242C);
                if (chosen || hover) Theme.outline(ctx, cx, cy, CARD_W - 6, h - 6, chosen ? 0xFFF2C230 : 0xFF6A7488);
                c.font = card.font; // 이 카드만 그 글꼴로
                c.customFont = card.custom;
                int tw = CARD_W - 18;
                ctx.drawText(textRenderer, Theme.title(Theme.ellipsize(textRenderer, card.label(), tw, true)), cx + 6, cy + 5, chosen ? 0xFFF2C230 : Theme.WHITE, true);
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, card.sub, tw), cx + 6, cy + 17, 0xFF9AA4B4, true);
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, SAMPLE, tw), cx + 6, cy + 28, 0xFFE8ECF2, true);
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, DIGITS, tw), cx + 6, cy + 38, 0xFFC8D0DC, true);
                HudPreview.draw(ctx, textRenderer, cx + 7, cy + 50, PANEL_W, ROWS, false);
                c.font = keep;
                c.customFont = keepCustom;
            }
        } finally {
            c.font = keep;
            c.customFont = keepCustom;
            ctx.disableScissor();
        }
        // 지금 고른 글꼴(크게)
        int pw = previewW();
        if (pw > 0) {
            int px = width - pw - 8;
            ctx.fill(px, gridY, px + pw, gridY + gridH, 0xFF181C24);
            Theme.boldText(ctx, textRenderer, "지금 고른 글꼴", px + 8, gridY + 6, Theme.WHITE, true);
            ctx.drawText(textRenderer, Theme.title(Theme.ellipsize(textRenderer, kr.ddingmate.ui.UserFonts.label(chosenKey), pw - 16, true)), px + 8, gridY + 20, 0xFFF2C230, true);
            ctx.getMatrices().push();
            ctx.getMatrices().translate(px + 10, gridY + 36, 0);
            ctx.getMatrices().scale(1.3f, 1.3f, 1);
            HudPreview.draw(ctx, textRenderer, 0, 0, (int) ((pw - 20) / 1.3f), 4, false);
            ctx.getMatrices().pop();
            int y = gridY + 36 + (int) (HudPreview.height(4) * 1.3f) + 10;
            for (String l : List.of("[띵메이트] 10분 뒤 바닥 아이템 청소예요", "반짝이는 황금 곡괭이 ×3", "가나다라마바사 아자차카타파하")) {
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, l, pw - 16), px + 8, y, 0xFFD8DEE8, true);
                y += 12;
            }
            int[] b = everywhereButton();
            boolean hover = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
            ctx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], c.fontEverywhere ? 0xFF3C6FB8 : hover ? 0xFF3A404C : 0xFF2A2E36);
            String label = "게임 전체에 적용: " + (c.fontEverywhere ? "켜짐" : "꺼짐");
            Theme.boldText(ctx, textRenderer, label, b[0] + (b[2] - Theme.boldWidth(textRenderer, label)) / 2, b[1] + 5, Theme.WHITE, true);
            if (y + 22 < b[1]) {
                for (String l : List.of("켜면 채팅 · 아이템 이름 · 메뉴 글자도", "이 글꼴로 그려요")) {
                    Theme.textShadow(ctx, textRenderer, l, px + 8, b[1] - 26 + (l.startsWith("켜면") ? 0 : 11), 0xFF8A94A4, true);
                }
            }
        }
        Theme.textShadow(ctx, textRenderer, "휠: 넘기기 · Esc: 돌아가기", 10, height - 16, 0xFF8A94A4, true);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 뒤 게임 화면이 비치게(실제 HUD 와 견주어 보도록) 흐림 없이 어둡게만
    }

    // ---------------------------------------------------------------- 입력

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();
        int[] b = everywhereButton();
        if (button == 0 && b != null && mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
            cfg().fontEverywhere = !cfg().fontEverywhere;
            cfg().save();
            return true;
        }
        if (button == 0 && mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int h = cardH();
            for (Placed p : place(cards())) {
                if (p.card == null) continue;
                int cx = gridX + p.x, cy = gridY + p.y - (int) scroll;
                if (mouseX >= cx && mouseX < cx + CARD_W - 6 && mouseY >= cy && mouseY < cy + h - 6) {
                    choose(p.card.key());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static void choose(String key) {
        DdingMateConfig c = cfg();
        kr.ddingmate.ui.UserFonts.choose(c, key);
        c.save();
    }

    /** 지금 고른 글꼴의 키(내 글꼴이 아직 안 읽혔으면 띵메이트 글꼴) */
    private static String currentKey() {
        DdingMateConfig c = cfg();
        String k = c.customFont;
        if (k != null && !k.isEmpty() && cards().stream().anyMatch(x -> x.key().equals(k))) return k;
        return c.font.name();
    }

    /** 시험용: 카드 수(글꼴마다 하나) */
    public static int debugCards() {
        return cards().size();
    }

    /** 시험용: i 번째 카드를 누른 것처럼 */
    public static void debugChoose(int i) {
        choose(cards().get(i).key());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 24;
        return true;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
