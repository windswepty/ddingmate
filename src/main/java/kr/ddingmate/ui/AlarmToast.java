package kr.ddingmate.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;

/** 띵타이쿤 창 모양 알림 팝업: 둥근 회청색 창 + 왼쪽 색 띠 + 슬롯 안 아이콘 + 굵은 제목·본문 */
public class AlarmToast implements Toast {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 32;
    private static final long SHOW_MS = 6000;

    private final String title;
    private final String body;
    private final ItemStack icon;
    private final int accent;
    private Visibility visibility = Visibility.SHOW;

    /** accent: 왼쪽 띠·제목 색(일정=파랑, 키워드=하늘, 완료=초록 등) */
    public AlarmToast(String title, String body, ItemStack icon, int accent) {
        this.title = title;
        this.body = body;
        this.icon = icon;
        this.accent = accent;
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
        Theme.window(ctx, 0, 0, WIDTH, HEIGHT);
        ctx.fill(2, 2, 5, HEIGHT - 4, accent);
        Theme.slot(ctx, 8, 7, 18);
        Theme.item(ctx, icon, 9, 8, 16);
        int textW = WIDTH - 36;
        Theme.boldText(ctx, tr, Theme.ellipsize(tr, title, textW, true), 31, 7, Theme.lerp(accent, 0xFF000000, 0.35), false);
        Theme.text(ctx, tr, Theme.ellipsize(tr, body, textW), 31, 18, Theme.TEXT_SOFT);
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }
}
