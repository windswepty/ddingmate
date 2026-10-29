package kr.ddingmate.skill;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 스킬을 켠 순간 서버가 클라이언트에 보내는 "정확한" 신호를 찾아 HUD 시간을 그 값에 맞춘다.
 * <ul>
 *   <li>상태 효과: 서버가 스킬 동안 효과를 걸면 남은 시간이 틱 단위로 온다 → 효과가 남은 동안 "사용 중", 사라지면 끝</li>
 *   <li>아이템 쿨타임: 서버가 도구에 쿨타임을 걸면 길이(틱)가 그대로 온다 → 재사용 대기 끝 시각</li>
 * </ul>
 * 켠 뒤 {@link #WATCH_MS} 안에 새로 생긴 신호 중 길이가 스킬 창 값과 비슷한 것만 그 스킬의 신호로 짝짓고(한 번 짝지으면 기억),
 * 무엇을 봤는지는 로그에 남긴다(액션바·보스바·사이드바 포함). 읽기만 하고 아무것도 보내지 않는다.
 */
public final class SkillSignals {

    private SkillSignals() {}

    /** 켠 뒤 이 시간 안에 생긴 신호만 그 스킬 것으로 본다 */
    static final long WATCH_MS = 3_000;
    /** 효과 길이가 스킬 창 지속 시간과 이만큼 안이면 같은 것으로 본다 */
    static final long EFFECT_MATCH_MS = 5_000;
    /** 쿨타임 길이가 스킬 창 재사용 시간과 이만큼 안이면 같은 것으로 본다 */
    static final long COOLDOWN_MATCH_MS = 3_000;
    /** 서버가 접속·채널 이동 연출로 거는 효과: 스킬 지속 신호로 짝짓지 않는다 */
    static final Set<String> LOADING_EFFECTS = Set.of("minecraft:blindness", "minecraft:darkness");

    /** 지금 지켜보는 스킬(방금 켠 것)과 켠 시각 */
    private static SkillTimers.Skill watching;
    private static long watchFrom;
    /** 켜기 직전 효과(id → 남은 틱): 새로 생기거나 다시 길어진 효과만 본다 */
    private static final Map<String, Integer> BASELINE = new HashMap<>();
    /** 이번 켬에서 짝지은 효과를 한 번이라도 봤는가(봤다가 사라지면 끝) */
    private static final Set<SkillTimers.Skill> EFFECT_SEEN = new HashSet<>();
    /** 진단 기록: 켬마다 본 것 */
    private static final List<String> NOTES = new ArrayList<>();
    private static final Set<String> OVERLAYS = new LinkedHashSet<>();
    private static int logged;

    // ---------------------------------------------------------------- 들어오는 신호

    /** SkillTimers 가 "X 스킬을 활성화했습니다."를 받았을 때 */
    static void activated(SkillTimers.Skill s, long now) {
        watching = s;
        watchFrom = now;
        EFFECT_SEEN.remove(s);
        NOTES.clear();
        OVERLAYS.clear();
        BASELINE.clear();
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) {
            for (StatusEffectInstance e : c.player.getStatusEffects()) BASELINE.put(effectId(e), e.getDuration());
        }
        // 짝지은 효과가 이미 걸려 있으면(서버가 메시지보다 먼저 보냄) 바로 맞춘다
        if (!s.effect.isEmpty()) BASELINE.remove(s.effect);
    }

    /** 내 도구에 아이템 쿨타임이 걸렸다(서버가 보낸 길이, 틱) */
    public static void onCooldownSet(Identifier group, int ticks) {
        long now = System.currentTimeMillis();
        String g = String.valueOf(group);
        if (watching != null && now - watchFrom <= WATCH_MS) note("아이템 쿨타임 " + g + " " + ticks + "틱(" + ticks / 20.0 + "초)");
        SkillTimers.Skill s = watching;
        if (s == null || now - watchFrom > WATCH_MS || ticks <= 0) return;
        long ms = ticks * 50L;
        // 재사용 시간을 모르면 짝짓지 않는다: 쓸어담는 광질을 켤 때마다 서버가 1초짜리 곡괭이 쿨타임도 보낸다(2026-10 로그 31번)
        boolean fits = s.cooldownSec > 0 && Math.abs(ms - s.cooldownSec * 1000L) <= COOLDOWN_MATCH_MS;
        if (s.cooldownGroup.isEmpty()) {
            if (!fits) return;
            s.cooldownGroup = g;
            DdingMate.LOG.info("스킬 신호 짝: {} 재사용 대기 = 아이템 쿨타임 {} ({}초)", s.name, g, ticks / 20.0);
        }
        if (!s.cooldownGroup.equals(g)) return;
        if (!fits) {
            // 짝지은 쿨타임 길이가 창 값과 크게 어긋나면(예전 판이 잘못 짝지음) 짝을 풀고 이번 값은 쓰지 않는다
            DdingMate.LOG.info("스킬 신호 짝 풀기: {} 쿨타임 {}초가 재사용 {}초와 다름", s.name, ticks / 20.0, s.cooldownSec);
            s.cooldownGroup = "";
            SkillTimers.touched();
            return;
        }
        // 서버가 정한 끝 시각 그대로
        s.readyAt = now + ms;
        s.readySynced = true;
        s.cooldownPending = false;
        SkillTimers.touched();
    }

    /** 액션바·제목에 뜬 글자(켠 직후만 기록) */
    public static void onOverlay(String text) {
        if (watching == null || text == null || System.currentTimeMillis() - watchFrom > 8_000) return;
        String t = ItemKeys.stripGlyphs(text).trim();
        if (!t.isEmpty() && OVERLAYS.size() < 8) OVERLAYS.add(t.length() > 80 ? t.substring(0, 80) : t);
    }

    // ---------------------------------------------------------------- 매 틱

    public static void tick(MinecraftClient c) {
        if (c.player == null) return;
        long now = System.currentTimeMillis();
        Map<String, StatusEffectInstance> effects = new HashMap<>();
        for (StatusEffectInstance e : c.player.getStatusEffects()) effects.put(effectId(e), e);
        // 1) 방금 켠 스킬: 새로 생긴 효과 중 길이가 맞는 것을 짝짓는다
        SkillTimers.Skill s = watching;
        if (s != null && now - watchFrom <= WATCH_MS) {
            for (Map.Entry<String, StatusEffectInstance> e : effects.entrySet()) {
                StatusEffectInstance in = e.getValue();
                Integer before = BASELINE.get(e.getKey());
                if (before != null && in.getDuration() <= before + 20) continue; // 원래 있던 효과
                if (in.isInfinite()) continue;
                note("효과 " + e.getKey() + " " + in.getDuration() + "틱(" + in.getDuration() / 20.0 + "초) 세기 " + in.getAmplifier());
                BASELINE.put(e.getKey(), in.getDuration());
                long ms = in.getDuration() * 50L;
                if (s.effect.isEmpty()) {
                    // 지속 시간을 모르면 짝짓지 않고, 채널 이동 때 걸리는 실명(2026-10-10 로그: 2초)은 빼고, 오차는 길이에 비례
                    long lore = SkillTimers.durationMs(s);
                    if (lore <= 0 || LOADING_EFFECTS.contains(e.getKey())) continue;
                    if (Math.abs(ms - lore) > Math.min(EFFECT_MATCH_MS, Math.max(1_000, lore / 5))) continue;
                    s.effect = e.getKey();
                    DdingMate.LOG.info("스킬 신호 짝: {} 지속 = 효과 {} ({}초)", s.name, e.getKey(), in.getDuration() / 20.0);
                    SkillTimers.touched();
                }
            }
        } else if (s != null && now - watchFrom > WATCH_MS) {
            writeNotes(c, s);
            watching = null;
        }
        // 2) 짝지은 효과가 있는 스킬: 효과가 남은 동안 그 시간으로, 사라지면 끝
        for (SkillTimers.Skill k : SkillTimers.activeSkills()) {
            if (k.effect.isEmpty()) continue;
            StatusEffectInstance in = effects.get(k.effect);
            if (in != null && !in.isInfinite() && now - k.activatedAt < 30 * 60_000L) {
                EFFECT_SEEN.add(k);
                long until = now + in.getDuration() * 50L;
                if (Math.abs(until - k.activeUntil) > 100) {
                    k.activeUntil = until;
                    k.activeExact = true;
                }
            } else if (EFFECT_SEEN.contains(k) && k.activeUntil > now) {
                // 효과가 사라졌다 = 스킬이 끝났다(종료 메시지보다 정확)
                k.activeUntil = 0;
                EFFECT_SEEN.remove(k);
                SkillTimers.touched();
            }
        }
    }

    // ---------------------------------------------------------------- 진단 기록

    private static void note(String s) {
        if (NOTES.size() < 20 && !NOTES.contains(s)) NOTES.add(s);
    }

    /** 켤 때마다(접속당 스킬마다 몇 번) 무엇이 보였는지 로그에 남긴다 */
    private static void writeNotes(MinecraftClient c, SkillTimers.Skill s) {
        if (logged >= 12) return;
        logged++;
        List<String> bars = new ArrayList<>();
        try {
            for (ClientBossBar bar : ((BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars().values()) {
                bars.add(ItemKeys.stripGlyphs(bar.getName().getString()).trim() + " " + Math.round(bar.getPercent() * 100) + "%");
            }
        } catch (RuntimeException ignored) {
            // 진단용이라 실패해도 그만
        }
        List<String> side = new ArrayList<>();
        if (c.world != null) {
            Scoreboard sb = c.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj != null) {
                side.add("제목:" + ItemKeys.stripGlyphs(obj.getDisplayName().getString()).trim());
                for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
                    if (side.size() > 15) break;
                    Team team = sb.getScoreHolderTeam(e.owner());
                    side.add(ItemKeys.stripGlyphs(Team.decorateName(team, e.name()).getString()).trim());
                }
            }
        }
        DdingMate.LOG.info("스킬 신호 기록: {} (창 지속 {}초·재사용 {}초) | 새 신호 {} | 액션바 {} | 보스바 {} | 사이드바 {}",
                s.name, s.durationSec, s.cooldownSec, NOTES, OVERLAYS, bars, side);
    }

    private static String effectId(StatusEffectInstance e) {
        Identifier id = Registries.STATUS_EFFECT.getId(e.getEffectType().value());
        return id == null ? e.getTranslationKey() : id.toString();
    }

    /** 시험용 */
    public static void debugReset() {
        watching = null;
        BASELINE.clear();
        EFFECT_SEEN.clear();
        NOTES.clear();
        OVERLAYS.clear();
        logged = 0;
    }
}
