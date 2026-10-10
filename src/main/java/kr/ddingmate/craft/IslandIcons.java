package kr.ddingmate.craft;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import kr.ddingmate.DdingMate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.resource.Resource;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 항해 섬 그림. 항해 창의 섬 칸은 투명한 버튼 아이템(종이, 모델 2025)이고, 섬 그림은 창 제목에 서버 리소스팩 글꼴
 * "…:sailing/ui/island" 의 글자(2026-10 리소스팩: U+A001~U+D203, 섬 그림 21장)로 칸 자리에 맞춰 찍혀 있다.
 * 그래서 창 제목에서 그 글자들이 그려지는 자리를 계산해 가장 가까운 섬 칸과 짝짓고, 그 글자의 그림을 기억해 둔다.
 * 그림은 리소스팩에서 직접 읽으므로 섬 그림이 바뀌어도 따라간다. 읽기만 하고 아무것도 보내지 않는다.
 */
public final class IslandIcons {

    private IslandIcons() {}

    /** 항해 창 제목을 그리는 자리(상자형 창 기본값) */
    private static final int TITLE_X = 8, TITLE_Y = 6;
    /** 글꼴 그림의 윗변 = 줄 기준 7 − ascent (마인크래프트 비트맵 글꼴) */
    private static final int BASELINE = 7;
    /** 섬 칸 가운데가 그림에서 이만큼(px) 안에 있어야 짝으로 본다 */
    private static final int REACH = 14;

    /** 글꼴 글자 하나의 그림: 텍스처, 그 안의 칸 위치·크기, 글꼴에서의 높이·ascent */
    record Glyph(Identifier texture, int u, int v, int cellW, int cellH, int texW, int texH, int height, int ascent) {
        int drawWidth() {
            return Math.max(1, Math.round(cellW * (float) height / cellH));
        }
    }

    /** 섬 이름 → "글꼴 id#글자 번호" */
    private static Map<String, String> store = new LinkedHashMap<>();
    private static boolean loaded;
    /** 글꼴 id → (글자 번호 → 그림). 리소스를 다시 읽으면 비운다 */
    private static final Map<Identifier, Map<Integer, Glyph>> FONTS = new HashMap<>();

    /** 리소스팩이 바뀌면(서버 접속 등) 다시 읽게 한다 */
    public static void clearFonts() {
        FONTS.clear();
    }

    // ---------------------------------------------------------------- 항해 창에서 배우기

    /** 항해 창의 섬 칸 하나: 섬 이름과 칸 자리(창 기준) */
    public record IslandSlot(String island, int x, int y) {}

    /** 제목 글자 하나가 그려지는 자리(창 기준) */
    record Placed(Identifier font, int codepoint, Glyph glyph, float x, float y) {
        float cx() {
            return x + glyph.drawWidth() / 2f;
        }

        float cy() {
            return y + glyph.height() / 2f;
        }
    }

    /** 항해 창 제목과 섬 칸들로 섬마다 그림을 정한다. 새로 알게 된 게 있으면 true */
    public static boolean learn(Text title, List<IslandSlot> islands) {
        if (islands.isEmpty()) return false;
        List<Placed> placed = place(title);
        if (placed.isEmpty()) return false;
        ensureLoaded();
        boolean changed = false;
        for (IslandSlot s : islands) {
            float sx = s.x() + 8, sy = s.y() + 8;
            Placed best = null;
            double bestD = Double.MAX_VALUE;
            for (Placed p : placed) {
                // 칸 가운데가 그림(조금 넓혀서) 안에 있어야 한다
                if (sx < p.x() - REACH || sx > p.x() + p.glyph().drawWidth() + REACH
                        || sy < p.y() - REACH || sy > p.y() + p.glyph().height() + REACH) continue;
                double d = Math.hypot(p.cx() - sx, p.cy() - sy);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best == null) continue;
            String value = best.font() + "#" + best.codepoint();
            if (!value.equals(store.put(s.island(), value))) {
                changed = true;
                DdingMate.LOG.info("섬 그림 짝: {} 칸({},{}) → {} U+{} 자리({},{})", s.island(), s.x(), s.y(),
                        best.font(), Integer.toHexString(best.codepoint()).toUpperCase(), Math.round(best.x()), Math.round(best.y()));
            }
        }
        if (changed) save();
        return changed;
    }

    /** 제목에서 섬 글꼴 글자가 그려지는 자리들 */
    static List<Placed> place(Text title) {
        List<Placed> out = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null) return out;
        TextHandler handler = client.textRenderer.getTextHandler();
        float[] x = {TITLE_X};
        title.visit((style, str) -> {
            Identifier font = style.getFont();
            boolean island = font != null && font.getPath().contains("sailing/ui/island");
            Map<Integer, Glyph> glyphs = island ? glyphs(font) : Map.of();
            str.codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
                Glyph g = glyphs.get(cp);
                if (g != null) out.add(new Placed(font, cp, g, x[0], TITLE_Y + BASELINE - g.ascent()));
                x[0] += handler.getWidth(StringVisitable.styled(ch, style));
            });
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ---------------------------------------------------------------- 그리기

    /** 이 섬의 그림을 알고 있는가 */
    public static boolean has(String jobName) {
        return glyphFor(jobName) != null;
    }

    /** "토르엔 항해" 줄에 그 섬 그림을 size×size 안에 맞춰 그린다. 모르면 false(다른 아이콘을 그리면 된다) */
    public static boolean draw(DrawContext ctx, String jobName, int x, int y, int size) {
        Glyph g = glyphFor(jobName);
        if (g == null) return false;
        float scale = Math.min(size / (float) g.cellW(), size / (float) g.cellH());
        int w = Math.max(1, Math.round(g.cellW() * scale));
        int h = Math.max(1, Math.round(g.cellH() * scale));
        ctx.drawTexture(RenderLayer::getGuiTextured, g.texture(), x + (size - w) / 2, y + (size - h) / 2,
                g.u(), g.v(), w, h, g.cellW(), g.cellH(), g.texW(), g.texH());
        return true;
    }

    private static Glyph glyphFor(String jobName) {
        if (jobName == null || !jobName.endsWith(CraftTimers.VOYAGE)) return null;
        ensureLoaded();
        String island = jobName.substring(0, jobName.length() - CraftTimers.VOYAGE.length()).trim();
        String v = store.get(island);
        if (v == null) return null;
        int i = v.lastIndexOf('#');
        Identifier font = i > 0 ? Identifier.tryParse(v.substring(0, i)) : null;
        if (font == null) return null;
        try {
            return glyphs(font).get(Integer.parseInt(v.substring(i + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 리소스팩 글꼴 읽기

    /** 글꼴 json(비트맵)을 읽어 글자 → 그림. 리소스팩에 없으면 빈 표(다음에 다시 시도) */
    static Map<Integer, Glyph> glyphs(Identifier font) {
        Map<Integer, Glyph> cached = FONTS.get(font);
        if (cached != null) return cached;
        Map<Integer, Glyph> out = new HashMap<>();
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier json = Identifier.of(font.getNamespace(), "font/" + font.getPath() + ".json");
        Optional<Resource> res = client.getResourceManager().getResource(json);
        if (res.isEmpty()) return out;
        try (Reader r = res.get().getReader()) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (!"bitmap".equals(p.has("type") ? p.get("type").getAsString() : "") || !p.has("file") || !p.has("chars")) continue;
                Identifier file = Identifier.tryParse(p.get("file").getAsString());
                if (file == null) continue;
                Identifier texture = Identifier.of(file.getNamespace(), "textures/" + file.getPath());
                int[] size = pngSize(texture);
                if (size == null) continue;
                JsonArray rows = p.getAsJsonArray("chars");
                int rowCount = rows.size();
                int colCount = 0;
                for (JsonElement row : rows) colCount = Math.max(colCount, row.getAsString().codePointCount(0, row.getAsString().length()));
                if (rowCount == 0 || colCount == 0) continue;
                int cellW = size[0] / colCount, cellH = size[1] / rowCount;
                int height = p.has("height") ? p.get("height").getAsInt() : 8;
                int ascent = p.get("ascent").getAsInt();
                for (int ry = 0; ry < rowCount; ry++) {
                    int[] cps = rows.get(ry).getAsString().codePoints().toArray();
                    for (int cx = 0; cx < cps.length; cx++) {
                        if (cps[cx] == 0 || cps[cx] == ' ') continue;
                        out.putIfAbsent(cps[cx], new Glyph(texture, cx * cellW, ry * cellH, cellW, cellH, size[0], size[1], height, ascent));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("섬 그림 글꼴을 읽지 못했어요: {}", json, e);
            return out;
        }
        if (!out.isEmpty()) FONTS.put(font, out);
        return out;
    }

    /** png 머리의 가로·세로. 없으면 null */
    private static int[] pngSize(Identifier texture) {
        Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(texture);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().getInputStream()) {
            byte[] head = in.readNBytes(24);
            if (head.length < 24 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') return null;
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16) | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16) | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 저장

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Map<String, String> m = new Gson().fromJson(r, new TypeToken<LinkedHashMap<String, String>>() {}.getType());
            if (m != null) store = m;
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("섬 그림 기록을 읽지 못했어요: {}", p, e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), new Gson().toJson(store), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DdingMate.LOG.error("섬 그림 기록 저장 실패", e);
        }
    }

    /** 시험용: 이 섬에 정한 "글꼴#글자 번호" */
    public static String debugValue(String island) {
        ensureLoaded();
        return store.get(island);
    }

    /** 시험용 */
    public static void debugClear() {
        loaded = true;
        store = new LinkedHashMap<>();
        FONTS.clear();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-islands.json");
    }
}
