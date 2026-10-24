package kr.ddingmate.ui;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.game.ServerHud;
import net.minecraft.client.MinecraftClient;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;

/**
 * 판 자동 바꾸기(2026-10-23 사용자 요청): 지역 · 계절·행사 · 켜진 전문가 스킬 · 게임 시간에 맞춰 HUD 판·제목 줄을 저절로 바꾼다.
 * 맞는 판이 없으면(지역을 모름, 켜진 스킬 없음 등) 설정에서 고른 판 그대로. HUD 친구는 바꾸지 않는다.
 * 판이 바뀌면 글자 밝기(어두운 판/밝은 판)도 그 판에 맞춘다(저장은 하지 않음).
 */
public final class HudAuto {
    private HudAuto() {
    }

    public enum Mode {
        OFF("off", "끔"), REGION("region", "지역 따라"), SEASON("season", "계절·행사 따라"), EXPERT("expert", "켜진 전문가 스킬 따라"),
        TIME("time", "게임 시간 따라");

        public final String id, label;

        Mode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Mode of(String id) {
            for (Mode m : values()) if (m.id.equals(id)) return m;
            return null;
        }
    }

    private static HudSkins.Skin effective;
    private static int ticks;

    /** 꾸미기 창이 카드(후보)를 그리는 동안은 자동 판을 쓰지 않는다 */
    private static boolean suspended;

    /** 자동 바꾸기가 지금 고른 판(없으면 null = 설정에서 고른 판) */
    public static HudSkins.Skin effective() {
        return suspended ? null : effective;
    }

    public static void suspend(boolean on) {
        suspended = on;
    }

    public static Mode mode() {
        DdingMateConfig cfg = DdingMate.config();
        Mode m = cfg == null ? null : Mode.of(cfg.hudAuto);
        return m == null ? Mode.OFF : m;
    }

    public static void tick(MinecraftClient client) {
        if (++ticks % 10 != 0) return;
        update(client, LocalDate.now(ZoneId.of("Asia/Seoul")), System.currentTimeMillis());
    }

    /** 지금 상황으로 판을 다시 고른다(시험에서 날짜·시각을 넣어 부른다) */
    public static void update(MinecraftClient client, LocalDate today, long now) {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null) return;
        String id = switch (mode()) {
            case REGION -> region();
            case SEASON -> season(today);
            case EXPERT -> expert(now);
            case TIME -> client == null || client.world == null ? null : time(client.world.getTimeOfDay());
            case OFF -> null;
        };
        effective = HudSkins.byId(id);
        // 글자 밝기는 지금 보이는 판에 맞춘다(자동 판 → 그 판, 아니면 고른 판·테마)
        HudSkins.Skin shown = effective != null ? effective : HudSkins.chosenPanel();
        if (shown != null) cfg.hudStyle = shown.dark() ? DdingMateConfig.HudStyle.DARK : DdingMateConfig.HudStyle.LIGHT;
        else if (cfg.hudTheme != null) cfg.hudStyle = cfg.hudTheme.style;
    }

    /** 채널 표시의 지역·장소 → 지역 판 */
    static String region() {
        ServerHud.Area a = ServerHud.area();
        if (a == null) return null;
        return switch (a) {
            case SPAWN -> "region_spawn";
            case WILD -> "region_wild";
            case VILLAGE -> "region_village";
            case HUNT -> "region_noctila";
            // 아일랜드 섬 이름은 세레니티·파라다이스(위키 지역 이름은 타이니랜드). 판 id 는 예전 설정을 지키려고 그대로 둔다
            case ISLAND -> ServerHud.place().contains("파라다이스") || ServerHud.place().contains("타이니") ? "region_tinyland" : "region_serenity";
        };
    }

    /**
     * 날짜 → 계절·행사 판. 행사 기간(위키 한정 문서: 1주년 10/14~10/28, 추석 9/23~10/7, 크리스마스 12/22~12/31,
     * 여름 7/30~8/13 — 해마다 비슷하게 잡는다)이 먼저, 그 밖은 계절(겨울 12~2월 · 봄 3~5월 · 여름 6~8월 · 가을 9~11월 달토끼)
     */
    public static String season(LocalDate d) {
        MonthDay md = MonthDay.from(d);
        if (between(md, 10, 14, 10, 28)) return "season_anniv";
        if (between(md, 9, 23, 10, 7)) return "season_chuseok";
        int m = d.getMonthValue();
        if (m == 12 || m <= 2) return "season_winter";
        if (m <= 5) return "season_spring";
        if (m <= 8) return "season_summer";
        return "season_moon";
    }

    private static boolean between(MonthDay md, int m0, int d0, int m1, int d1) {
        return !md.isBefore(MonthDay.of(m0, d0)) && !md.isAfter(MonthDay.of(m1, d1));
    }

    /** 지금 켜져 있는(지속 중인) 전문가 스킬 → 그 전문가 판. 여럿이면 가장 늦게 켠 것 */
    static String expert(long now) {
        String tool = null;
        long at = -1;
        for (var s : kr.ddingmate.skill.SkillTimers.known()) {
            if (s.activeUntil > now && s.activatedAt > at) {
                at = s.activatedAt;
                tool = s.tool;
            }
        }
        if (tool == null) return null;
        return switch (tool) {
            case "pickaxe" -> "expert_mining";
            case "rod" -> "expert_ocean";
            case "hoe" -> "expert_farm";
            case "axe", "sword" -> "expert_hunt";
            default -> null;
        };
    }

    /** 게임 속 시각(0~24000 틱, 0 = 아침 6시) → 시간대 판: 새벽 · 낮 · 노을 · 밤 */
    public static String time(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        if (t >= 23000) return "time_dawn";
        if (t < 11500) return "time_day";
        if (t < 13500) return "time_sunset";
        return "time_night";
    }
}
