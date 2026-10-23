package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.Font;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.TrueTypeFont;
import net.minecraft.client.font.UnihexFont;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * "게임 전체에 적용"(HUD·꾸미기 탭 → 글꼴): 기본 글꼴(minecraft:default)의 글자 가운데 <b>글자 글꼴(TTF·유니폰트)로 그리는 글자만</b>
 * 고른 글꼴로 그린다.
 * <p>
 * 띵타이쿤 리소스팩(2026-10-21 판)은 기본 글꼴에 갈무리9(TTF)를 넣고, 채팅 칭호·등급 별·실루엣·진행 막대 같은 그림을
 * 잘 안 쓰는 한글 음절(예: U+C743) 자리에 그림(bitmap)으로 넣어 둔다. 글꼴 묶음째 바꾸면 그 자리에 한글이 그려져 그림이
 * 깨지므로, 글자마다 원래 그리던 글꼴이 그림·간격 글꼴이면 그대로 두고, 글자 글꼴일 때만 바꾼다.
 * 바닐라 기본 글꼴의 영문(ascii.png 그림)은 글자이므로 인쇄 가능한 ASCII 는 늘 바꾼다. 서버로는 아무것도 보내지 않는다.
 */
public final class GameFont {

    private GameFont() {}

    private static final Identifier DEFAULT = Identifier.ofVanilla("default");

    /** 판단 결과: 기본 글꼴의 글꼴 목록이 바뀌면(리소스 다시 읽기) 다시 계산 */
    private static List<Font> decidedFor;
    private static final it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap TEXT = new it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap();

    /**
     * 기본 글꼴 묶음에서 이 글자를 고른 글꼴로 그려야 하면 그 글꼴 묶음, 아니면 null(원래대로)
     */
    public static FontStorage target(Identifier storageId, List<Font> fonts, int codePoint) {
        if (!DEFAULT.equals(storageId) || fonts == null) return null;
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null || !cfg.fontEverywhere) return null;
        if (!isText(fonts, codePoint)) return null;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.textRenderer == null) return null;
        return ((kr.ddingmate.mixin.TextRendererAccessor) c.textRenderer).ddingmate$storage(Theme.bodyFont());
    }

    /** 원래 그 글자를 그리던 글꼴이 글자 글꼴(TTF·유니폰트)이거나, 인쇄 가능한 ASCII 인가 */
    static synchronized boolean isText(List<Font> fonts, int cp) {
        if (fonts != decidedFor) {
            decidedFor = fonts;
            TEXT.clear();
        }
        if (TEXT.containsKey(cp)) return TEXT.get(cp);
        boolean text = cp >= 0x20 && cp <= 0x7E;
        if (!text) {
            for (Font f : fonts) {
                if (f.getGlyph(cp) == null) continue;
                text = f instanceof TrueTypeFont || f instanceof UnihexFont;
                break;
            }
        }
        TEXT.put(cp, text);
        return text;
    }
}
