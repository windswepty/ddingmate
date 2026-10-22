package kr.ddingmate.hud;

import kr.ddingmate.ui.HudSkins;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 친구: 고른 NPC 가 HUD 판 윗변에 앞발을 올리고 기대어 있다(상점 NPC 가 창틀에 기댄 모습처럼).
 * 한 화면에 한 마리만, 이번 프레임에 그린 판 가운데 처음으로 자리가 나는 판에 올린다.
 * 판 위쪽이 화면 밖이거나 서버 HUD(재화 바·아일랜드 프로필 등)·다른 HUD 판과 겹치면 반대쪽 끝, 그래도 안 되면 다음 판.
 */
public final class HudMascot {
    private HudMascot() {
    }

    /** 이번 프레임 판들(화면 GUI 좌표): x, y, w, h, 배율 */
    private static final List<float[]> PANELS = new ArrayList<>();
    private static boolean recording;
    /** 시험용: 마지막에 친구를 그린 자리(x, y, 크기). 못 그렸으면 null */
    private static float[] lastDrawn;
    /** 다음에 기록하는 판을 맨 앞(먼저 앉을 판)으로: 자리를 미리 잡아 둔 일정 판 */
    private static boolean preferNext;

    public static void begin() {
        PANELS.clear();
        recording = true;
        preferNext = false;
    }

    /** 친구 자리를 미리 잡을 만큼 화면이 높은지(HUD 좌표). 낮은 화면에선 아래 HUD 가 밀려 겹쳐서 잡지 않는다 */
    public static final int RESERVE_MIN_HEIGHT = 300;

    /** 친구를 골랐으면 판 위에 비워 둘 높이(HUD 좌표), 아니면 0 */
    static int reserve(DrawContext ctx) {
        return HudSkins.mascot() != null && HudLayout.vh(ctx) >= RESERVE_MIN_HEIGHT ? HudSkins.pawRow : 0;
    }

    static void prefer() {
        preferNext = true;
    }

    /** 판을 그릴 때 부른다(HUD 마다 크기를 따로 바꿔 행렬이 다를 수 있어 화면 좌표로 바꿔 둔다) */
    static void record(DrawContext ctx, int x, int y, int w, int h) {
        if (!recording || w <= 0 || h <= 0) return;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        Vector3f a = m.transformPosition(new Vector3f(x, y, 0));
        Vector3f b = m.transformPosition(new Vector3f(x + w, y + h, 0));
        float[] p = {a.x, a.y, b.x - a.x, b.y - a.y, (b.x - a.x) / w};
        if (preferNext) PANELS.add(0, p);
        else PANELS.add(p);
        preferNext = false;
    }

    public static void end(DrawContext ctx) {
        recording = false;
        lastDrawn = null;
        HudSkins.Skin s = HudSkins.mascot();
        if (s == null || PANELS.isEmpty()) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        List<HudLayout.Rect> avoid = HudLayout.serverAreas(sw, sh);
        for (float[] p : PANELS) {
            float k = p[4];
            float size = HudSkins.mascotSize * k;
            float above = HudSkins.pawRow * k; // 판 윗변 위로 솟는 높이
            float top = p[1] - above;
            for (float sx : new float[]{p[0] + p[2] - size - 6 * k, p[0] + 6 * k}) {
                if (top < 0 || sx < 0 || sx + size > sw || sx < p[0] - 1 || sx + size > p[0] + p[2] + 1) continue;
                if (hits(sx, top, size, above - 3 * k, avoid, p)) continue;
                var mat = ctx.getMatrices();
                mat.push();
                mat.translate(sx, top, 0);
                mat.scale(k, k, 1);
                HudSkins.drawMascot(ctx, s, 0, 0);
                mat.pop();
                lastDrawn = new float[]{sx, top, size};
                return;
            }
        }
    }

    private static boolean hits(float x, float y, float w, float h, List<HudLayout.Rect> avoid, float[] host) {
        for (HudLayout.Rect r : avoid) {
            if (x < r.x() + r.w() && x + w > r.x() && y < r.y() + r.h() && y + h > r.y()) return true;
        }
        for (float[] p : PANELS) {
            if (p == host) continue;
            if (x < p[0] + p[2] && x + w > p[0] && y < p[1] + p[3] && y + h > p[1]) return true;
        }
        return false;
    }

    /** 시험용 */
    public static float[] debugLastDrawn() {
        return lastDrawn;
    }
}
