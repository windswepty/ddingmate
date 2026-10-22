package kr.ddingmate.gui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.hud.HudPreview;
import kr.ddingmate.ui.HudSkins;
import kr.ddingmate.ui.HudTheme;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * HUD 꾸미기 한눈에 보기(2026-10-22 사용자 요청: "테마를 고를 때 프리뷰 창을 띄워서 비교해 보고 고를 수 있게").
 * 위 탭(세트 · 판 디자인 · 제목 줄 · HUD 친구)마다 후보를 실제 HUD 와 똑같은 미리보기 카드로 늘어놓고,
 * 누르면 바로 그걸로 바뀐다. 오른쪽은 지금 고른 조합을 크게.
 * 카드는 그리는 동안만 설정을 그 후보로 바꿔 끼우고 곧바로 되돌린다(실제 HUD 그리는 코드를 그대로 써서 똑같이 보이게).
 */
public class HudLookScreen extends Screen {
    private enum Mode {
        SET("세트"), PANEL("판 디자인"), HEAD("제목 줄"), FRIEND("HUD 친구");
        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private record Card(String title, String sub, Consumer<DdingMateConfig> apply, Predicate<DdingMateConfig> chosen) {
    }

    private static final int CARD_W = 136, CARD_H = 112, PANEL_W = 116, ROWS = 2;
    private static Mode mode = Mode.SET;
    private final Screen parent;
    private double scroll;
    private int gridX, gridY, gridW, gridH, cols;

    public HudLookScreen(Screen parent) {
        super(Text.literal("HUD 꾸미기"));
        this.parent = parent;
    }

    private static DdingMateConfig cfg() {
        return DdingMate.config();
    }

    private List<Card> cards() {
        List<Card> out = new ArrayList<>();
        List<HudSkins.Skin> npcs = HudSkins.all();
        switch (mode) {
            case SET -> {
                for (HudSkins.Skin s : npcs)
                    out.add(new Card(s.name(), s.role() + " · " + s.place(), c -> HudSkins.applySet(c, s), c -> HudSkins.set() == s));
            }
            case PANEL -> {
                for (HudTheme t : HudTheme.values())
                    out.add(new Card(t.label, "기본 테마", c -> HudSkins.applyTheme(c, t), c -> c.hudPanel == null && HudTheme.current() == t));
                for (HudSkins.Skin s : npcs)
                    out.add(new Card(s.name() + "의 판", s.place(), c -> HudSkins.applyPanel(c, s), c -> s.id().equals(c.hudPanel)));
            }
            case HEAD -> {
                out.add(new Card("판에 맞춤", "판과 같은 NPC 제목 줄", c -> c.hudHeader = HudSkins.AUTO, c -> HudSkins.AUTO.equals(c.hudHeader)));
                out.add(new Card("띠 없이 글자만", "제목 글자와 가는 밑줄", c -> c.hudHeader = HudSkins.PLAIN, c -> HudSkins.PLAIN.equals(c.hudHeader)));
                for (HudSkins.Skin s : npcs)
                    out.add(new Card(s.name() + "의 제목 줄", s.place(), c -> c.hudHeader = s.id(), c -> s.id().equals(c.hudHeader)));
            }
            case FRIEND -> {
                out.add(new Card("없음", "친구 없이", c -> c.hudMascot = HudSkins.NONE, c -> HudSkins.NONE.equals(c.hudMascot)));
                for (HudSkins.Skin s : npcs)
                    out.add(new Card(s.name(), s.role(), c -> c.hudMascot = s.id(), c -> s.id().equals(c.hudMascot)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 설정 잠깐 바꿔 끼우기

    private record Look(String panel, String header, String mascot, HudTheme theme, DdingMateConfig.HudStyle style) {
        static Look of(DdingMateConfig c) {
            return new Look(c.hudPanel, c.hudHeader, c.hudMascot, c.hudTheme, c.hudStyle);
        }

        void restore(DdingMateConfig c) {
            c.hudPanel = panel;
            c.hudHeader = header;
            c.hudMascot = mascot;
            c.hudTheme = theme;
            c.hudStyle = style;
        }
    }

    // ---------------------------------------------------------------- 그리기

    private int previewW() {
        return width >= 560 ? 190 : 0;
    }

    private void layout() {
        gridX = 8;
        gridY = 44;
        gridW = width - 16 - previewW() - (previewW() > 0 ? 8 : 0);
        gridH = height - gridY - 28;
        cols = Math.max(1, gridW / CARD_W);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        layout();
        ctx.fill(0, 0, width, height, 0xD0101218);
        ctx.fill(0, 0, width, gridY - 2, 0xF6101218); // 제목·탭 줄은 뒤 HUD 가 비치지 않게
        Theme.boldText(ctx, textRenderer, "HUD 꾸미기 한눈에 보기", 10, 8, Theme.WHITE, true);
        Theme.textShadow(ctx, textRenderer, "카드를 누르면 바로 바뀌어요. 세트로 끼우거나, 판 · 제목 줄 · 친구를 따로 골라 섞어도 돼요.", 10, 20, 0xFFB8C0CC, true);
        // 탭
        int tx = 10;
        for (Mode m : Mode.values()) {
            int tw = Theme.boldWidth(textRenderer, m.label) + 16;
            boolean on = m == mode;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= 30 && mouseY < 42;
            ctx.fill(tx, 30, tx + tw, 42, on ? 0xFF3C6FB8 : hover ? 0xFF3A404C : 0xFF2A2E36);
            Theme.boldText(ctx, textRenderer, m.label, tx + 8, 32, on ? Theme.WHITE : 0xFFC8CED8, true);
            tx += tw + 4;
        }
        // 카드
        List<Card> cards = cards();
        int rows = (cards.size() + cols - 1) / cols;
        double max = Math.max(0, rows * CARD_H - gridH);
        scroll = Math.max(0, Math.min(max, scroll));
        DdingMateConfig c = cfg();
        Look keep = Look.of(c);
        ctx.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        try {
            for (int i = 0; i < cards.size(); i++) {
                int cx = gridX + (i % cols) * CARD_W;
                int cy = gridY + (i / cols) * CARD_H - (int) scroll;
                if (cy + CARD_H < gridY || cy > gridY + gridH) continue;
                Card card = cards.get(i);
                boolean chosen = card.chosen.test(c);
                boolean hover = mouseX >= cx && mouseX < cx + CARD_W - 6 && mouseY >= cy && mouseY < cy + CARD_H - 6
                        && mouseY >= gridY && mouseY < gridY + gridH;
                ctx.fill(cx, cy, cx + CARD_W - 6, cy + CARD_H - 6, chosen ? 0xFF3A3420 : hover ? 0xFF2E3440 : 0xFF20242C);
                if (chosen || hover) Theme.outline(ctx, cx, cy, CARD_W - 6, CARD_H - 6, chosen ? 0xFFF2C230 : 0xFF6A7488);
                card.apply.accept(c);
                c.sanitize();
                HudPreview.draw(ctx, textRenderer, cx + 7, cy + 40, PANEL_W, ROWS, true);
                keep.restore(c);
                int ly = cy + 40 + HudPreview.height(ROWS) + 5;
                Theme.boldText(ctx, textRenderer, Theme.ellipsize(textRenderer, card.title, CARD_W - 16), cx + 6, ly, chosen ? 0xFFF2C230 : Theme.WHITE, true);
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, card.sub, CARD_W - 16), cx + 6, ly + 10, 0xFF9AA4B4, true);
            }
        } finally {
            keep.restore(c);
            ctx.disableScissor();
        }
        // 지금 고른 조합(크게)
        int pw = previewW();
        if (pw > 0) {
            int px = width - pw - 8;
            ctx.fill(px, gridY, px + pw, gridY + gridH, 0xFF181C24);
            Theme.boldText(ctx, textRenderer, "지금 고른 조합", px + 8, gridY + 6, Theme.WHITE, true);
            ctx.getMatrices().push();
            ctx.getMatrices().translate(px + 10, gridY + 74, 0);
            ctx.getMatrices().scale(1.4f, 1.4f, 1);
            HudPreview.draw(ctx, textRenderer, 0, 0, 120, 4, true);
            ctx.getMatrices().pop();
            int y = gridY + 74 + (int) (HudPreview.height(4) * 1.4f) + 12;
            HudSkins.Skin set = HudSkins.set();
            HudSkins.Skin panel = HudSkins.panel();
            HudSkins.Skin head = HudSkins.header();
            HudSkins.Skin friend = HudSkins.mascot();
            String[] lines = {
                    "세트: " + (set != null ? set.name() : "직접 조합"),
                    "판: " + (panel != null ? panel.name() + "의 판" : HudTheme.current().label),
                    "제목 줄: " + (HudSkins.PLAIN.equals(c.hudHeader) ? "띠 없이 글자만" : head != null ? head.name() + "의 제목 줄" : "테마 제목 줄"),
                    "친구: " + (friend != null ? friend.label() : "없음"),
            };
            for (String l : lines) {
                Theme.textShadow(ctx, textRenderer, Theme.ellipsize(textRenderer, l, pw - 16), px + 8, y, 0xFFD8DEE8, true);
                y += 11;
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
        int tx = 10;
        for (Mode m : Mode.values()) {
            int tw = Theme.boldWidth(textRenderer, m.label) + 16;
            if (mouseX >= tx && mouseX < tx + tw && mouseY >= 30 && mouseY < 42) {
                mode = m;
                scroll = 0;
                return true;
            }
            tx += tw + 4;
        }
        if (button == 0 && mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int col = (int) ((mouseX - gridX) / CARD_W);
            int row = (int) ((mouseY - gridY + scroll) / CARD_H);
            int i = row * cols + col;
            List<Card> cards = cards();
            if (col < cols && i >= 0 && i < cards.size()) {
                choose(cards.get(i));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void choose(Card card) {
        DdingMateConfig c = cfg();
        card.apply.accept(c);
        c.sanitize();
        c.save();
    }

    /** 시험용: 이 탭의 i 번째 카드를 누른 것처럼 */
    public void debugChoose(String tab, int i) {
        for (Mode m : Mode.values()) if (m.label.equals(tab)) mode = m;
        choose(cards().get(i));
    }

    /** 시험용: 지금 탭의 카드 수 */
    public int debugCards(String tab) {
        for (Mode m : Mode.values()) if (m.label.equals(tab)) mode = m;
        return cards().size();
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
