package kr.ddingmate.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;

/** 띵타이쿤 창 모양 알림 팝업: 둥근 회청색 창 + 왼쪽 색 띠 + 슬롯 안 아이콘 + 굵은 제목·본문 */
public class DdingMateToast implements Toast {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 32;
    private static final long SHOW_MS = 6000;

    private final String title;
    private final String body;
    /** 본문을 너비에 맞춰 나눈 줄(최대 3줄, 넘치면 마지막 줄을 … 로). 예전에는 한 줄이라 "시설에서…" 처럼 잘렸다 */
    private final java.util.List<String> lines;
    private final ItemStack icon;
    private final int accent;
    private Visibility visibility = Visibility.SHOW;

    /** accent: 왼쪽 띠·제목 색(일정=파랑, 키워드=하늘, 완료=초록 등) */
    public DdingMateToast(String title, String body, ItemStack icon, int accent) {
        this.title = title;
        this.body = body;
        this.icon = icon;
        this.accent = accent;
        this.lines = wrapBody(net.minecraft.client.MinecraftClient.getInstance().textRenderer, body);
    }

    static final int MAX_LINES = 3;

    static java.util.List<String> wrapBody(TextRenderer tr, String body) {
        if (body == null || body.isEmpty()) return java.util.List.of();
        java.util.List<String> all = Theme.wrap(tr, body, WIDTH - 36);
        if (all.size() <= MAX_LINES) return all;
        java.util.List<String> out = new java.util.ArrayList<>(all.subList(0, MAX_LINES));
        out.set(MAX_LINES - 1, Theme.ellipsize(tr, out.get(MAX_LINES - 1) + " " + all.get(MAX_LINES), WIDTH - 36));
        return out;
    }

    /** 시험용: 본문이 몇 줄로 나뉘는지 */
    public java.util.List<String> lines() {
        return lines;
    }

    @Override
    public Visibility getVisibility() {
        return visibility;
    }

    @Override
    public void update(ToastManager manager, long time) {
        visibility = time >= SHOW_MS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public void draw(DrawContext ctx, TextRenderer tr, long startTime) {
        int h = getHeight();
        Theme.window(ctx, 0, 0, WIDTH, h);
        ctx.fill(2, 2, 5, h - 4, accent);
        Theme.slot(ctx, 8, 7, 18);
        Theme.item(ctx, icon, 9, 8, 16);
        int textW = WIDTH - 36;
        Theme.boldText(ctx, tr, Theme.ellipsize(tr, title, textW, true), 31, 7, Theme.lerp(accent, 0xFF000000, 0.35), false);
        for (int i = 0; i < lines.size(); i++) Theme.text(ctx, tr, lines.get(i), 31, 18 + i * 10, Theme.TEXT_SOFT);
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT + Math.max(0, lines.size() - 1) * 10;
    }
}
