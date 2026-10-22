package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;

/**
 * HUD 테마(2026-10-22 사용자 요청): 판 바탕·테두리·제목 밑줄·글자색을 한 벌로. 어두운 판(dark)인지 밝은 판인지,
 * 예전 반투명 검은 상자(클래식)인지는 {@link #style}로 정해 HUD 그리는 길은 그대로 쓴다.
 */
public enum HudTheme {
    /** 지금까지 쓰던 어두운 판 + 파란 밑줄 */
    BLUE("띵타이쿤 파랑", DdingMateConfig.HudStyle.DARK,
            0xFF000000, 0xFF14171C, 0xFF3C4250, Theme.BLUE, 0xFFFFFFFF, 0, 0,
            Theme.TEXT_ON_DARK, Theme.SOFT_ON_DARK, Theme.GREEN_ON_DARK, Theme.RED_ON_DARK, Theme.GOLD_ON_DARK, Theme.BLUE_ON_DARK),
    DARK("다크", DdingMateConfig.HudStyle.DARK,
            0xFF000000, 0xFF0B0B0D, 0xFF2A2A2E, 0xFF8A8F98, 0xFFF2F2F2, 0, 0,
            0xFFE6E6E6, 0xFF9A9AA0, 0xFF7BD88F, 0xFFFF7B72, 0xFFE8C468, 0xFF8AB4F8),
    /** 지금까지 쓰던 밝은 판(하늘색 제목 줄) */
    LIGHT("라이트", DdingMateConfig.HudStyle.LIGHT,
            Theme.OUTLINE, Theme.WINDOW, 0, 0, Theme.INK, 0, 0,
            Theme.TEXT, Theme.TEXT_SOFT, Theme.GREEN_ON_LIGHT, Theme.RED_ON_LIGHT, Theme.GOLD_ON_LIGHT, Theme.BLUE_ON_LIGHT),
    PASTEL("파스텔", DdingMateConfig.HudStyle.LIGHT,
            0xFF9C7FB0, 0xFFFFF4F8, 0, 0, 0xFF6A4C7A, 0xFFFFE3EF, 0xFFE3D9FF,
            0xFF5A4A5E, 0xFF8C7A90, 0xFF3F8F6A, 0xFFC94A72, 0xFFB07A1E, 0xFF5B6FCF),
    /** 고대비: 검은 바탕 + 흰 테두리 + 노란 강조, 진하기를 낮춰도 바탕은 진하게 */
    CONTRAST("고대비", DdingMateConfig.HudStyle.DARK,
            0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFD400, 0xFFFFD400, 0, 0,
            0xFFFFFFFF, 0xFFE0E0E0, 0xFF00FF66, 0xFFFF5A5A, 0xFFFFD400, 0xFF66CCFF),
    /** 예전 반투명 검은 상자 */
    CLASSIC("클래식(예전)", DdingMateConfig.HudStyle.CLASSIC,
            0, 0, 0, 0, 0xFFFFFFFF, 0, 0,
            Theme.TEXT_ON_DARK, Theme.SOFT_ON_DARK, Theme.GREEN_ON_DARK, Theme.RED_ON_DARK, Theme.GOLD_ON_DARK, Theme.BLUE_ON_DARK);

    public final String label;
    public final DdingMateConfig.HudStyle style;
    /** 바깥 테두리 · 바탕 · 안쪽 테두리(어두운 판) · 제목 밑줄(어두운 판) · 제목 글자 */
    public final int outer, body, inner, accent, title;
    /** 밝은 판 제목 줄 가운데·가장자리 색(0 이면 기본 하늘색 줄) */
    public final int headMid, headEdge;
    public final int text, soft, green, red, gold, blue;

    HudTheme(String label, DdingMateConfig.HudStyle style, int outer, int body, int inner, int accent, int title, int headMid, int headEdge,
             int text, int soft, int green, int red, int gold, int blue) {
        this.label = label;
        this.style = style;
        this.outer = outer;
        this.body = body;
        this.inner = inner;
        this.accent = accent;
        this.title = title;
        this.headMid = headMid;
        this.headEdge = headEdge;
        this.text = text;
        this.soft = soft;
        this.green = green;
        this.red = red;
        this.gold = gold;
        this.blue = blue;
    }

    /** 지금 테마(설정이 없으면 예전 HUD 스타일에서) */
    public static HudTheme current() {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null) return BLUE;
        if (cfg.hudTheme != null) return cfg.hudTheme;
        return of(cfg.hudStyle);
    }

    /** 예전 설정의 HUD 스타일 → 테마 */
    public static HudTheme of(DdingMateConfig.HudStyle style) {
        if (style == null) return BLUE;
        return switch (style) {
            case LIGHT -> LIGHT;
            case CLASSIC -> CLASSIC;
            default -> BLUE;
        };
    }

    /** 고대비는 진하기를 낮춰도 읽을 수 있게 바탕을 진하게 */
    public int alpha(int alpha) {
        return this == CONTRAST ? Math.max(alpha, 230) : alpha;
    }
}
