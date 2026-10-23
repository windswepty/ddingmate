package kr.ddingmate.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NPC 세트 HUD(2026-10-22 사용자 요청): 띵타이쿤 동물 NPC 17명마다 판 · 제목 줄 · HUD 친구 한 벌.
 * 세트로 한 번에 끼거나, 판 · 제목 줄 · 친구를 따로따로 골라 섞을 수 있다(설정의 hudPanel · hudHeader · hudMascot).
 * 그림과 이름·글자색은 tools/art 가 만든 리소스(textures/gui/sprites/hud, hud_skins.json)를 읽는다.
 * 글자색은 판에 딸려 있어 어떤 조합이든 판 바탕 위에서 읽히고, 제목 글자색은 제목 줄에 딸려 있다.
 */
public final class HudSkins {
    private HudSkins() {
    }

    /** 제목 줄을 판에 맞춰(판이 NPC 판이면 그 NPC 제목 줄, 기본 테마면 테마 제목 줄) */
    public static final String AUTO = "auto";
    /** 제목 줄 띠 없이 글자만 */
    public static final String PLAIN = "plain";
    /** HUD 친구 없음 */
    public static final String NONE = "none";

    /** 동물 NPC 묶음 이름(친구가 있는 세트) */
    public static final String GROUP_NPC = "동물 NPC";

    /**
     * @param group     묶음(동물 NPC · 지역 · 계절·행사 · 사람 NPC · 전문가 · 시간대)
     * @param hasMascot 이 세트에 HUD 친구 그림이 있는지(지역·계절 등은 판·제목 줄만)
     */
    public record Skin(String id, String name, String role, String place, boolean dark, int title, boolean titleShadow,
                       int text, int soft, int green, int red, int gold, int blue, String group, boolean hasMascot) {
        public Identifier panel() {
            return Identifier.of(DdingMate.MOD_ID, "hud/panel_" + id);
        }

        public Identifier head() {
            return Identifier.of(DdingMate.MOD_ID, "hud/head_" + id);
        }

        public Identifier mascot() {
            return Identifier.of(DdingMate.MOD_ID, "hud/mascot_" + id);
        }

        /** "하니 · 토끼 디자이너" */
        public String label() {
            return name + " · " + role;
        }

        public boolean npc() {
            return hasMascot;
        }

        /** 세트 이름: "하니 · 꽃잎 공방" / "지역 · 마을" */
        public String display() {
            return npc() ? name + " · " + place : group + " · " + name;
        }

        /** 판 이름: "하니의 꽃잎 공방" / "마을 판" */
        public String panelLabel() {
            return npc() ? name + "의 " + place : name + " 판";
        }

        /** 제목 줄 이름 */
        public String headLabel() {
            return name + "의 제목 줄";
        }
    }

    /** 판 밖 장식 자리(그림 둘레), 제목 줄 그림 높이, 친구 그림 크기, 친구 그림에서 판 윗변이 지나갈 줄 */
    public static int outset = 3, headHeight = 17, mascotSize = 30, pawRow = 27;
    private static List<Skin> skins;

    public static List<Skin> all() {
        if (skins == null) skins = load();
        return skins;
    }

    public static Skin byId(String id) {
        if (id == null) return null;
        for (Skin s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    private static List<Skin> load() {
        List<Skin> out = new ArrayList<>();
        try (InputStream in = HudSkins.class.getResourceAsStream("/assets/" + DdingMate.MOD_ID + "/hud_skins.json")) {
            if (in == null) throw new IllegalStateException("hud_skins.json 없음");
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            outset = root.get("outset").getAsInt();
            headHeight = root.get("headHeight").getAsInt();
            mascotSize = root.get("mascotSize").getAsInt();
            pawRow = root.get("pawRow").getAsInt();
            for (var e : root.getAsJsonArray("skins")) {
                JsonObject o = e.getAsJsonObject();
                JsonObject c = o.getAsJsonObject("colors");
                out.add(new Skin(o.get("id").getAsString(), o.get("name").getAsString(), o.get("role").getAsString(),
                        o.get("place").getAsString(), o.get("dark").getAsBoolean(), rgb(o.get("title").getAsString()),
                        o.get("titleShadow").getAsBoolean(), rgb(c.get("text").getAsString()), rgb(c.get("soft").getAsString()),
                        rgb(c.get("green").getAsString()), rgb(c.get("red").getAsString()), rgb(c.get("gold").getAsString()),
                        rgb(c.get("blue").getAsString()),
                        o.has("group") ? o.get("group").getAsString() : GROUP_NPC,
                        !o.has("hasMascot") || o.get("hasMascot").getAsBoolean()));
            }
        } catch (Exception e) {
            DdingMate.LOG.warn("HUD 세트 목록을 읽지 못했어요", e);
        }
        return Collections.unmodifiableList(out);
    }

    private static int rgb(String hex) {
        return 0xFF000000 | Integer.parseInt(hex, 16);
    }

    /** HUD 친구 그림이 있는 세트들 */
    public static List<Skin> mascots() {
        return all().stream().filter(Skin::hasMascot).toList();
    }

    // ---------------------------------------------------------------- 지금 고른 것

    /** 지금 HUD 판(자동 바꾸기가 고른 판이 있으면 그 판, 아니면 고른 NPC 판, 기본 테마 판이면 null) */
    public static Skin panel() {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null) return null;
        Skin auto = HudAuto.effective();
        return auto != null ? auto : byId(cfg.hudPanel);
    }

    /** 자동 바꾸기와 상관없이 설정에서 고른 판 */
    public static Skin chosenPanel() {
        DdingMateConfig cfg = DdingMate.config();
        return cfg == null ? null : byId(cfg.hudPanel);
    }

    /** 지금 판이 이 밝기(어두운 판/밝은 판)의 NPC 판이면 그 판 */
    public static Skin panel(boolean dark) {
        Skin s = panel();
        return s != null && s.dark == dark ? s : null;
    }

    /** 지금 제목 줄 그림(없으면 null = 테마 제목 줄 또는 띠 없음) */
    public static Skin header() {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null) return null;
        String h = cfg.hudHeader == null ? AUTO : cfg.hudHeader;
        if (AUTO.equals(h)) return panel();
        return byId(h);
    }

    public static boolean plainHeader() {
        DdingMateConfig cfg = DdingMate.config();
        return cfg != null && PLAIN.equals(cfg.hudHeader);
    }

    public static Skin mascot() {
        DdingMateConfig cfg = DdingMate.config();
        Skin s = cfg == null ? null : byId(cfg.hudMascot);
        return s != null && s.hasMascot ? s : null;
    }

    /** 고른 판·제목 줄(·친구)이 한 세트로 맞춰져 있으면 그 세트, 아니면 null(직접 조합). 친구가 없는 세트는 친구를 따지지 않는다 */
    public static Skin set() {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null) return null;
        Skin p = chosenPanel();
        if (p == null) return null;
        boolean head = AUTO.equals(cfg.hudHeader) || p.id.equals(cfg.hudHeader);
        return head && (!p.hasMascot || p.id.equals(cfg.hudMascot)) ? p : null;
    }

    /** 세트로 끼기: 판 · 제목 줄(· 친구가 있는 세트면 친구까지)을 이 세트로 */
    public static void applySet(DdingMateConfig cfg, Skin s) {
        cfg.hudPanel = s.id;
        cfg.hudHeader = AUTO;
        if (s.hasMascot) cfg.hudMascot = s.id;
        cfg.hudStyle = s.dark ? DdingMateConfig.HudStyle.DARK : DdingMateConfig.HudStyle.LIGHT;
    }

    /** 판만 NPC 판으로(글자 밝기도 그 판에 맞춰) */
    public static void applyPanel(DdingMateConfig cfg, Skin s) {
        cfg.hudPanel = s.id;
        cfg.hudStyle = s.dark ? DdingMateConfig.HudStyle.DARK : DdingMateConfig.HudStyle.LIGHT;
    }

    /** 판을 기본 테마로 */
    public static void applyTheme(DdingMateConfig cfg, HudTheme t) {
        cfg.hudPanel = null;
        cfg.hudTheme = t;
        cfg.hudStyle = t.style;
    }

    // ---------------------------------------------------------------- 그리기

    /** NPC 판 그림(판 둘레 장식 자리까지). alpha 는 HUD 진하기 */
    public static void drawPanel(DrawContext ctx, Skin s, int x, int y, int w, int h, int alpha) {
        int o = outset;
        ctx.drawGuiTexture(RenderLayer::getGuiTextured, s.panel(), x - o, y - o, w + 2 * o, h + 2 * o, tint(alpha));
    }

    /** NPC 제목 줄 그림(판 위 장식 자리 + 제목 줄 14칸). 띠는 진하기를 너무 낮추지 않는다(제목 글자 받침) */
    public static void drawHeader(DrawContext ctx, Skin s, int x, int y, int w, int alpha) {
        int o = outset;
        ctx.drawGuiTexture(RenderLayer::getGuiTextured, s.head(), x - o, y - o, w + 2 * o, headHeight, tint(Math.max(alpha, 200)));
    }

    public static void drawMascot(DrawContext ctx, Skin s, int x, int y) {
        ctx.drawGuiTexture(RenderLayer::getGuiTextured, s.mascot(), x, y, mascotSize, mascotSize);
    }

    private static int tint(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
    }
}
