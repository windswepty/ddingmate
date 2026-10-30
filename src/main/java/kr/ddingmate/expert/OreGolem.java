package kr.ddingmate.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.game.ServerText;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 광석 골렘 출현 쿨타임. 위키 광석 골렘 문서(2026-10-22): 광석을 캘 때 0.5% 확률로 나오고, 한 번 나오면 모든 광석에
 * 5분 쿨타임(프레스티지 멤버십은 하루 처음 20마리까지 1분, 03시 초기화). 실제 채팅(2026-10 로그) "광석 골렘이 등장했습니다!"를 보면
 * 쿨타임을 센다. 5분이 안 돼 다시 나온 적이 있으면 프레스티지로 보고 그날 20마리까지 1분으로 센다.
 * 사용자가 /광석골렘 을 치면 오는 답에 남은 시간이 있으면 그 값으로 맞춘다(문구를 아직 못 봐서 로그에 남긴다).
 * 서버로는 아무것도 보내지 않는다.
 */
public final class OreGolem {

    private OreGolem() {}

    public static final long COOLDOWN_MS = 5 * 60_000L;
    public static final long PRESTIGE_MS = 60_000L;
    public static final int PRESTIGE_DAILY = 20;

    private static final Pattern SPAWN = Pattern.compile("광석\\s*골렘이\\s*등장했습니다");
    private static final Pattern MIN_SEC = Pattern.compile("(\\d+)\\s*분\\s*(\\d+)\\s*초");
    private static final Pattern MIN = Pattern.compile("(\\d+)\\s*분");
    private static final Pattern SEC = Pattern.compile("(\\d+)\\s*초");

    private static final class State {
        long lastSpawn;
        long endsAt;
        String day = "";
        int today;
        /** 5분이 안 돼 다시 나온 적이 있다 = 프레스티지 멤버십 */
        boolean prestige;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, State> store;
    private static String lastLogged = "";

    public static void onChat(String raw) {
        if (raw == null || !DdingMate.config().expert.oreGolem) return;
        String t = ItemKeys.stripGlyphs(raw).replaceAll("\\s+", " ").trim();
        // /광석골렘 답은 " : " 가 들어 있어 사람 채팅 거르기에 걸린다: 줄 머리가 정확히 맞으면 먼저 받는다(사람 채팅은 "[칭호] 닉 : " 로 시작)
        boolean reply = NEXT.matcher(t).find() || DAILY.matcher(t).find();
        if (!reply && ServerText.fromPlayer(t)) return;
        handle(t, System.currentTimeMillis());
    }

    /** /광석골렘 답(2026-10-20~29 실제): "다음 등장 대기 : 4분 42초", "다음 등장 대기 : 5초" */
    private static final Pattern NEXT = Pattern.compile("^다음\\s*등장\\s*대기\\s*[:：]");
    /** 프레스티지 하루 처음 20마리 1분 쿨타임: "일일 최초 쿨타임 감소 : 1 / 20" */
    private static final Pattern DAILY = Pattern.compile("^일일\\s*최초\\s*쿨타임\\s*감소\\s*[:：]\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** 쿨타임이 끝났을 때의 답: "광석 골렘이 등장할 수 있습니다." */
    private static final Pattern READY = Pattern.compile("^광석\\s*골렘이\\s*등장할\\s*수\\s*있습니다");

    static void handle(String t, long now) {
        State s = state();
        if (s == null) return;
        if (NEXT.matcher(t).find()) {
            long left = duration(t);
            s.endsAt = left > 0 ? now + left : 0;
            // 1분 넘게 남았으면 5분 쿨타임: 프레스티지가 아니거나 오늘 20마리를 넘겼다(멤버십이 끝나면 다시 5분)
            if (left > PRESTIGE_MS + 5_000 && s.today < PRESTIGE_DAILY) s.prestige = false;
            save();
            return;
        }
        Matcher daily = DAILY.matcher(t);
        if (daily.find()) {
            rollDay(s, now);
            s.today = Integer.parseInt(daily.group(1));
            s.prestige = true;
            save();
            return;
        }
        if (READY.matcher(t).find()) {
            s.endsAt = 0;
            save();
            return;
        }
        if (SPAWN.matcher(t).find()) {
            rollDay(s, now);
            long gap = s.lastSpawn > 0 ? now - s.lastSpawn : Long.MAX_VALUE;
            if (gap >= PRESTIGE_MS - 5_000 && gap < COOLDOWN_MS - 15_000) s.prestige = true;
            s.today++;
            s.lastSpawn = now;
            s.endsAt = now + cooldown(s);
            save();
            return;
        }
        // /광석골렘 답: "광석"·"골렘"과 남은 시간이 함께 있는 서버 문구
        if (t.contains("골렘") && t.contains("광석") && !t.contains("일꾼")) {
            long left = duration(t);
            if (!t.equals(lastLogged)) {
                lastLogged = t;
                DdingMate.LOG.info("광석 골렘 문구: {}", t);
            }
            if (left > 0 && left <= COOLDOWN_MS) {
                s.endsAt = now + left;
                save();
            } else if (left == 0 && (t.contains("가능") || t.contains("없습니다"))) {
                s.endsAt = 0;
                save();
            }
        }
    }

    private static long cooldown(State s) {
        return s.prestige && s.today <= PRESTIGE_DAILY ? PRESTIGE_MS : COOLDOWN_MS;
    }

    static long duration(String t) {
        Matcher a = MIN_SEC.matcher(t);
        if (a.find()) return (Long.parseLong(a.group(1)) * 60 + Long.parseLong(a.group(2))) * 1000;
        Matcher b = MIN.matcher(t);
        if (b.find()) return Long.parseLong(b.group(1)) * 60_000;
        Matcher c = SEC.matcher(t);
        if (c.find()) return Long.parseLong(c.group(1)) * 1000;
        return 0;
    }

    /** 오늘 출현 수. 위키 스케줄러: "광석 골렘 쿨타임 감소 횟수 — 매일 오전 3시에 초기화" */
    private static void rollDay(State s, long now) {
        String day = java.time.Instant.ofEpochMilli(now).atZone(ScheduleEngine.KST).minusHours(3).toLocalDate().toString();
        if (!day.equals(s.day)) {
            s.day = day;
            s.today = 0;
        }
    }

    /** 쿨타임 중이면 남은 ms, 아니면 0 */
    public static long remaining(long now) {
        if (!DdingMate.config().expert.oreGolem) return 0;
        State s = state();
        return s == null ? 0 : Math.max(0, s.endsAt - now);
    }

    public static String remainingText(long now) {
        long sec = (remaining(now) + 999) / 1000;
        return sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초";
    }

    /** 탭에 보일 한 줄 */
    public static String detail(long now) {
        State s = state();
        if (s == null) return "접속하면 보여요";
        long left = remaining(now);
        String base = left > 0 ? "쿨타임 " + remainingText(now) + " 남음" : "지금 나올 수 있어요";
        return base + (s.prestige ? " · 프레스티지(하루 처음 20마리 1분)" : " · 쿨타임 5분");
    }

    // ---------------------------------------------------------------- 저장

    private static State state() {
        String k = kr.ddingmate.craft.CraftTimers.accountKey();
        if (k == null) return null;
        if (store == null) load();
        return store.computeIfAbsent(k, x -> new State());
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-golem.json");
    }

    private static void load() {
        store = new LinkedHashMap<>();
        if (!Files.exists(path())) return;
        try (Reader r = Files.newBufferedReader(path(), StandardCharsets.UTF_8)) {
            Map<String, State> read = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, State>>() {}.getType());
            if (read != null) store.putAll(read);
        } catch (Exception e) {
            DdingMate.LOG.warn("광석 골렘 기록을 읽지 못했어요", e);
        }
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(store, w);
        } catch (Exception e) {
            DdingMate.LOG.warn("광석 골렘 기록을 저장하지 못했어요", e);
        }
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugReset() {
        if (store == null) load();
        store.clear();
        save();
    }

    public static void debugHandle(String text, long now) {
        handle(text, now);
    }

    public static boolean debugPrestige() {
        State s = state();
        return s != null && s.prestige;
    }
}
