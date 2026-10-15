package kr.ddingmate.craft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.notify.Notifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제작·가공 시설에서 만드는 아이템마다 완료까지 남은 시간을 센다.
 *
 * 서버에서 확인한 규칙(2026-10 실제 로그):
 *  - 제작을 시작하면 "조약돌 뭉치 아이템 7개 제작을 시작했습니다.", 꺼내면 "…7개를 제작대에서 회수했습니다." 가 온다(시간은 없음).
 *  - 시설 창의 레시피 칸 설명에 "제작 시간 : 20초" 처럼 제작 1번(위 예의 7개 한 묶음)에 걸리는 시간이 적혀 있다.
 *  - 여러 번 넣으면 하나씩 차례대로 만든다(조약돌 뭉치 ×7 을 4번 넣으면 20·40·60·80초에 끝남).
 * 그래서 레시피 칸에서 1번에 걸리는 시간을 배우고, 넣은 순서대로 이어 붙여 완료 시각을 계산한다.
 * 대기열 칸 설명에 남은 시간이 보이면 그 값으로 바로잡고, 뒤에 기다리는 제작도 같이 옮긴다.
 * 향장품은 서버가 "10분 후 제조가 완료됩니다" 라고 알려 주는 따로 도는 제조라 대기열에 넣지 않는다.
 * 읽기만 하고 창을 대신 열거나 아이템을 꺼내지 않는다.
 */
public final class CraftTimers {

    public static final class Job {
        public String name = "";
        public int count = 1;
        /** 시작 메시지를 받은 시각(ms). 시설 창에서 처음 본 제작이면 0 */
        public long startedAt;
        /** 완료 예정 시각(ms). 0 이면 아직 모름 */
        public long endsAt;
        /** 시설 창에 보인 남은 시간이나 서버가 알려 준 시간으로 맞춘 값이면 true(아니면 계산한 어림값) */
        public boolean synced;
        /**
         * 창에서 직접 읽진 않았지만, 앞 제작이 확실하고 이 제작의 실제 시간을 알아(서버에서 배운 값) 완료 시각이 확실한 제작.
         * synced 나 derived 가 아니면 시각을 숫자로 보여 주지 않는다(어림값으로 착각하지 않게)
         */
        public boolean derived;
        /** 대기열과 따로 도는 향장품 제조 */
        public boolean separate;
        /**
         * 어느 대기열인지: "시설 종류@지역"(예: "채광물 가공 시설@ISLAND"). 시설·장소가 다르면 따로 돌아서 서로 잇지 않는다
         * (예전 결함: 다른 시설의 긴 제작 뒤에 귀중품 40초를 이어 붙여 1시간으로 보임). 빈 값 = 예전 기록
         */
        public String queue = "";
        public boolean notified;
        /** 시설 창에서 이 제작이 맨 앞에서 돌고 있는 것을 마지막으로 본 시각(저장 안 함). 완료로 바뀌는 순간을 잡는 데 쓴다 */
        transient long seenRunningAt;
    }

    /**
     * 화면에 보일 묶음: 같은 아이템을 여러 번 넣으면 한 줄로. 개수가 모두 같으면 "조약돌 뭉치 ×7 [5]",
     * 개수가 다르면 합쳐서 "어빌리티 스톤 ×128". 시간은 마지막 것이 끝날 때까지(차례로 도니 합친 시간)
     */
    public record Group(String name, int count, int times, long endsAt, boolean done, boolean estimated,
                        boolean unknown, List<Job> jobs) {
        public boolean sameCount() {
            return jobs.stream().allMatch(j -> j.count == count);
        }

        public int total() {
            return jobs.stream().mapToInt(j -> j.count).sum();
        }

        public String label() {
            if (!sameCount()) return name + " ×" + total();
            return name + (count > 1 ? " ×" + count : "") + (times > 1 ? " [" + times + "]" : "");
        }
    }

    private static final class Store {
        Map<String, List<Job>> accounts = new LinkedHashMap<>();
        /** 정규화한 아이템 이름 → 제작 1번에 걸리는 초(레시피 칸 "제작 시간") */
        Map<String, Long> secondsPerJob = new LinkedHashMap<>();
        /**
         * 서버가 실제로 보여 준 제작 1건의 시간(ms). 키 "정규화 이름*개수". 레시피마다 개수에 따라 늘기도 하고(어빌리티 스톤 50개
         * = 1시간 30분) 안 늘기도 해서(조약돌 뭉치 7개 = 14초, 2026-10-10 실측) 공식 대신 본 값을 먼저 쓴다. 스킬이 바뀌면 비운다
         */
        Map<String, Long> observedMs = new LinkedHashMap<>();
        /** 한 번에 만들어지는 개수(시간 계산용, 실측에서 배움). 이름 → 개수 */
        Map<String, Integer> batch = new LinkedHashMap<>();
        /** 2 = 대기열 순서대로 계산하는 방식. 그 전 기록은 시간 계산이 달라 제작 목록을 비운다 */
        int version;
    }

    /** "코룸 주괴 아이템 64개 제작을 시작했습니다." */
    private static final Pattern START = Pattern.compile("^(.+?)\\s*아이템\\s*([\\d,]+)\\s*개\\s*제작을\\s*시작했습니다");
    /** "코룸 주괴 아이템 64개를 제작대에서 회수했습니다." */
    private static final Pattern COLLECT = Pattern.compile("^(.+?)\\s*아이템\\s*([\\d,]+)\\s*개를\\s*제작대에서\\s*회수했습니다");
    /** "향장품 제조를 시작했습니다. 10분 후 제조가 완료됩니다." */
    private static final Pattern PERFUME_START = Pattern.compile("^향장품\\s*제조를\\s*시작했습니다.*?(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?\\s*후");
    /** "유실라 항해 보상을 수령했습니다." (2026-10-10 실제 채팅) */
    private static final Pattern VOYAGE_CLAIM = Pattern.compile("^(.+?)\\s*항해\\s*보상을\\s*수령했습니다");
    /** 항해를 보낼 때 서버 채팅(2026-10 실제): "벨로아(으)로 항해를 시작했습니다." */
    private static final Pattern VOYAGE_START = Pattern.compile("^(.+?)\\s*(?:\\(으\\))?로\\s*항해를\\s*시작했습니다");
    /** 항해도를 새로 받았을 때 서버 채팅(2026-10 실제): "성공적으로 항해도를 초기화했습니다." */
    private static final Pattern VOYAGE_RESET_DONE = Pattern.compile("항해도를\\s*초기화했습니다");
    private static final Pattern PERFUME_DONE =Pattern.compile("^제조\\s*완료된\\s*향장품을\\s*성공적으로\\s*수령");
    public static final String PERFUME = "향장품";

    /** 설명 줄의 시간: "2분 0초", "1시간 2분 3초", "45초" */
    private static final Pattern KOREAN_TIME = Pattern.compile("(?:(\\d+)\\s*일)?\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*초)?");
    /**
     * 시설 창의 "[ 강화 횃불 제작 시작하기 ]" 버튼(2026-10-09 실제): "- 제작 시간 : 6.3초" — 지금 고른 개수로 넣었을 때의
     * 스킬 적용 시간. 서버는 이를 초 단위로 올려 쓴다(6.3초 → 대기열 7초, 실제 7초 뒤 회수)
     */
    private static final Pattern START_BUTTON = Pattern.compile("^\\[?\\s*(.+?)\\s*제작\\s*시작하기\\s*]?$");
    /** 마지막으로 본 제작 시작 버튼: 아이템 키, 걸리는 시간(ms), 본 시각 */
    private static String buttonKey;
    private static long buttonMs, buttonAt;
    /** 설명 줄의 시간: "01:02:03", "02:30" */
    private static final Pattern CLOCK_TIME = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)");
    /** 남은 시간을 뜻하는 줄 */
    private static final Pattern REMAIN_HINT = Pattern.compile("남은|남음|완료까지|후\\s*완료|후에\\s*완료|remaining|left", Pattern.CASE_INSENSITIVE);
    /** 제작 1번에 걸리는 시간을 뜻하는 줄(레시피 칸: "제작 시간 : 20초") */
    private static final Pattern TOTAL_HINT = Pattern.compile("소요|제작\\s*시간|걸리는\\s*시간|필요\\s*시간");
    /** 레시피 고르는 칸(대기열이 아님): "(좌) 해당 레시피 선택", "(쉬프트+우) 레시피 제거" */
    /**
     * 레시피 칸 표시. 시설마다 문구가 다르다(2026-10-10 실제 뱃지 제작 창: "(좌) 해당 아이템 제작 시작", "(쉬프트+우) 레시피 고정").
     * 이걸 못 알아보면 레시피 칸의 "제작 시간 : 5초"를 진행 중인 제작으로 착각한다
     */
    private static final Pattern RECIPE_HINT = Pattern.compile("레시피\\s*(선택|제거|고정)|제작\\s*시작");
    /** 다 됐다는 줄(시간 없이) */
    private static final Pattern DONE_HINT = Pattern.compile("제작\\s*완료|완료되었|완료됨|회수\\s*가능|클릭하여\\s*회수|눌러\\s*회수");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Store store = fresh();

    private static Store fresh() {
        Store s = new Store();
        s.version = 3;
        return s;
    }
    private static boolean dirty;
    private static long lastSave;
    /** 시설 창 설명 진단 기록은 접속마다 몇 번만 */
    private static final Set<String> LOGGED = new HashSet<>();

    private CraftTimers() {}

    // ---------------------------------------------------------------- 채팅

    public static void onChat(String message) {
        if (message == null || !DdingMate.config().craft.enabled) return;
        String t = Formatting.strip(message);
        if (t == null) return;
        t = ItemKeys.stripGlyphs(t).trim();
        if (kr.ddingmate.game.ServerText.fromPlayer(t)) return; // 사람이 쓴 채팅·귓속말은 무시
        long now = System.currentTimeMillis();
        Matcher m = START.matcher(t);
        if (m.find()) {
            start(m.group(1).trim(), parseInt(m.group(2)), now);
            return;
        }
        m = COLLECT.matcher(t);
        if (m.find()) {
            collect(m.group(1).trim(), parseInt(m.group(2)), false);
            return;
        }
        m = VOYAGE_CLAIM.matcher(t);
        if (m.find()) {
            // "유실라 항해 보상을 수령했습니다." → 그 섬 항해를 목록에서 뺀다
            List<Job> jobs = jobsForWrite();
            String name = m.group(1).trim() + " " + VOYAGE;
            lastVoyageClaim = now; // 마지막 섬이었다면 이때부터 1분 뒤 항해가 초기화된다
            if (jobs != null && jobs.removeIf(j -> j.separate && j.name.equals(name))) changed();
            return;
        }
        m = VOYAGE_START.matcher(t);
        if (m.find()) {
            voyageStarted(m.group(1).trim(), now);
            return;
        }
        if (VOYAGE_RESET_DONE.matcher(t).find()) {
            // 항해도가 새로 바뀌었다 → 지난 섬 줄과 "항해 초기화" 줄을 모두 지운다(새 섬은 항해를 보내면 생긴다)
            List<Job> jobs = jobsForWrite();
            lastVoyageClaim = 0;
            if (jobs != null && jobs.removeIf(j -> j.separate && j.name.endsWith(VOYAGE))) changed();
            return;
        }
        m = PERFUME_START.matcher(t);
        if (m.find()) {
            List<Job> jobs = jobsForWrite();
            if (jobs == null) return;
            long sec = parseInt(m.group(1)) * 3600L + parseInt(m.group(2)) * 60L + parseInt(m.group(3));
            Job j = new Job();
            j.name = PERFUME;
            j.startedAt = now;
            j.separate = true;
            if (sec > 0) {
                j.endsAt = now + sec * 1000;
                j.synced = true;
            }
            jobs.add(j);
            changed();
            return;
        }
        if (PERFUME_DONE.matcher(t).find()) collect(PERFUME, 1, true);
    }

    /** 제작 시작: 대기열 맨 뒤에 붙인다. 1번에 걸리는 시간을 알면 앞 제작이 끝난 뒤부터 센다 */
    /** 시작 버튼을 본 뒤 이 안에 시작 메시지가 오면 그 버튼으로 넣은 것으로 본다 */
    static final long BUTTON_FRESH_MS = 15_000;

    private static void start(String name, int count, long now) {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        Job j = new Job();
        j.name = name;
        j.count = Math.max(1, count);
        j.startedAt = now;
        j.queue = queueOf(name);
        String key = ItemKeys.normalize(name);
        if (key.equals(buttonKey) && buttonMs > 0 && now - buttonAt <= BUTTON_FRESH_MS) {
            // 넣기 직전 시작 버튼에 보인 시간 = 이 제작(이 개수)의 실제 시간
            store.observedMs.put(key + "*" + j.count, buttonMs);
            buttonKey = null;
        }
        jobs.add(j);
        fillUnknown(jobs);
        changed();
    }

    private static void collect(String name, int count, boolean separate) {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        String key = ItemKeys.normalize(name);
        long now = System.currentTimeMillis();
        List<Job> same = queueOrder(jobs).stream()
                .filter(j -> j.separate == separate && ItemKeys.normalize(j.name).equals(key)).toList();
        // 다 된 것 중 개수가 같은 것 → 개수가 같은 것 → 이름만 같은 것
        Job pick = same.stream().filter(j -> j.count == count && done(j, now)).findFirst()
                .orElse(same.stream().filter(j -> j.count == count).findFirst()
                        .orElse(same.isEmpty() ? null : same.get(0)));
        if (pick != null) {
            jobs.remove(pick);
            changed();
        }
    }

    // ---------------------------------------------------------------- 시간 계산

    /** 전문가 스킬: 레벨별 제작 시간 감소(%) — 위키 [채광·재배·사냥] 10·30·40·60·80, [해양] 10·20·30·50·70 */
    private static final int[] FAST = {0, 10, 30, 40, 60, 80};
    private static final int[] FAST_MARINE = {0, 10, 20, 30, 50, 70};

    /** 이 시설의 제작 시간이 몇 % 줄어드는지(배운 전문가 스킬 기준) */
    public static int reductionPercent(String facility) {
        if (facility == null) return 0;
        DdingMateConfig.Craft c = DdingMate.config().craft;
        return switch (facility) {
            case "채광물 가공 시설", "강화 제작 시설" -> FAST[c.mining];
            case "농작물 가공 시설", "요리 제작 시설" -> FAST[c.farming];
            case "해양 제작 시설", "연금 제작 시설" -> FAST_MARINE[c.marine];
            case "영혼 가공 시설" -> FAST[c.hunting];
            default -> 0;
        };
    }

    /**
     * 제작 1번에 실제로 걸리는 시간(ms). 시설 창 설명의 "제작 시간"은 스킬 적용 전 시간이라
     * 배운 전문가 스킬만큼 줄인다(실제 로그: 20초 레시피가 채광 1레벨에서 18초에 끝남). 모르면 -1
     */
    public static long durationMs(String name) {
        return durationMs(name, 1);
    }

    /**
     * count 개를 한 번에 넣은 제작의 시간(ms). 시설에서 고른 개수만큼 시간이 늘어난다
     * (실제: 어빌리티 스톤 2분 × 50개 × 채광 1레벨 0.9 = 1시간 30분 0초). 한 번에 여러 개 나오는 레시피는 횟수로 센다
     */
    public static long durationMs(String name, int count) {
        Long seen = store.observedMs.get(ItemKeys.normalize(name) + "*" + Math.max(1, count));
        if (seen != null && seen > 0) return seen;
        long multi = multiRecipeSec(name, count);
        if (multi > 0) return perUnitSec(name, multi) * 1000L; // 이 개수짜리 레시피 1번
        long base = secondsPerJob(name);
        if (base <= 0) return -1;
        kr.ddingmate.counter.RecipeBook.Recipe r = kr.ddingmate.counter.RecipeBook.recipeFor(name);
        Integer learned = store.batch.get(ItemKeys.normalize(name));
        int per = learned != null ? Math.max(1, learned) : r == null ? 1 : Math.max(1, r.output);
        long times = Math.max(1, (Math.max(1, count) + per - 1) / per);
        int pct = reductionPercent(kr.ddingmate.counter.RecipeBook.facilityOf(name));
        // 서버는 줄어든 시간을 초 단위로 올려 쓴다(2026-10-10 실측: 강화 횃불 7초 × 채광 10% → 6.3초가 아니라 7초,
        // 3개 21초). 20초→18초, 2분→108초처럼 나누어떨어지는 경우는 그대로
        long perUnit = (long) Math.ceil(base * (100 - pct) / 100.0 - 1e-9);
        return perUnit * times * 1000L;
    }

    /**
     * 한 번에 여러 개 나오는 레시피의 시간(초, 스킬 전). 같은 이름이라도 개수마다 레시피가 따로 있다
     * (2026-10-11 실제 창: 조약돌 뭉치 1개 = 5초, 조약돌 뭉치 7개 = 20초). 모르면 -1
     */
    static long multiRecipeSec(String name, int count) {
        if (count <= 1) return -1;
        Long v = store.secondsPerJob.get(ItemKeys.normalize(name) + "*" + count);
        return v == null ? -1 : v;
    }

    /** 레시피 시간(초)에 전문가 스킬 감소를 적용(서버처럼 초 단위 올림) */
    private static long perUnitSec(String name, long baseSec) {
        int pct = reductionPercent(kr.ddingmate.counter.RecipeBook.facilityOf(name));
        return (long) Math.ceil(baseSec * (100 - pct) / 100.0 - 1e-9);
    }

    /** 대기열 이름: 시설 종류(모르면 아이템 이름) + 지금 있는 지역 */
    static String queueOf(String name) {
        String facility = kr.ddingmate.counter.RecipeBook.facilityOf(name);
        String where = String.valueOf(kr.ddingmate.game.ServerHud.area());
        return (facility != null ? facility : "?" + ItemKeys.normalize(name)) + "@" + where;
    }

    /** 대기열별로 나눈 제작(각 대기열 안은 넣은 순서). 향장품처럼 따로 도는 것은 빼고 */
    private static java.util.Collection<List<Job>> queues(List<Job> jobs) {
        Map<String, List<Job>> out = new LinkedHashMap<>();
        for (Job j : queueOrder(jobs)) {
            if (j.separate) continue;
            out.computeIfAbsent(j.queue == null ? "" : j.queue, k -> new ArrayList<>()).add(j);
        }
        return out.values();
    }

    /** 전문가 스킬 레벨이 바뀌었다: 그 전 레벨에서 본 시간은 버리고 다시 계산한다 */
    public static void skillChanged() {
        store.observedMs.clear();
        recalculate();
    }

    /**
     * 제작을 넣은 직후(5초 안) 맨 앞 제작의 남은 시간을 창에서 봤다 → 그 제작 1건의 실제 시간을 배운다.
     * 레시피 1개 시간과 견줘 한 번에 몇 개가 만들어지는지도 배워 다른 개수에도 쓴다
     */
    private static boolean learn(Job job, long ms) {
        if (ms <= 0) return false;
        String name = ItemKeys.normalize(job.name);
        long multi = multiRecipeSec(job.name, job.count);
        long base = multi > 0 ? multi : secondsPerJob(job.name);
        long perUnit = base > 0 ? perUnitSec(job.name, base) : 0;
        long dur;
        if (perUnit > 0) {
            // 창의 남은 초는 반올림·올림이 섞여 ±1초 흔들린다 → 레시피 1번 시간의 배수 중 가장 가까운 것으로(실측 14~15초 → 14초)
            long crafts = Math.max(1, Math.round(ms / 1000.0 / perUnit));
            dur = crafts * perUnit * 1000L;
            if (job.count > 1 && multi <= 0) store.batch.put(name, (int) Math.max(1, Math.round((double) job.count / crafts)));
        } else {
            dur = Math.round(ms / 1000.0) * 1000L;
        }
        if (dur <= 0) return false;
        Long before = store.observedMs.put(name + "*" + job.count, dur);
        dirty = true;
        return before == null || before != dur;
    }

    /** 스킬 레벨을 바꾸면 아직 안 끝난 어림값 제작을 처음부터 다시 계산한다 */
    public static void recalculate() {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        long now = System.currentTimeMillis();
        for (List<Job> q : queues(jobs)) recalculateQueue(q, now);
        changed();
    }

    private static void recalculateQueue(List<Job> ordered, long now) {
        long cursor = 0;
        boolean unknown = false;
        boolean chainOk = true;
        for (Job j : ordered) {
            if (j.synced || j.startedAt == 0 || (j.endsAt > 0 && j.endsAt <= now)) {
                chainOk = j.synced || j.derived;
                cursor = Math.max(cursor, j.endsAt);
                continue;
            }
            long dur = durationMs(j.name, j.count);
            if (unknown || dur <= 0) {
                j.endsAt = 0;
                j.derived = false;
                unknown = true;
                continue;
            }
            j.endsAt = Math.max(j.startedAt, cursor) + dur;
            j.derived = chainOk && confident(j.name, j.count);
            chainOk = j.derived;
            j.notified = false;
            cursor = j.endsAt;
        }
    }

    /** 대기열 순서: 창에서만 본(이미 돌던) 제작 → 시작 메시지 순 */
    private static List<Job> queueOrder(List<Job> jobs) {
        List<Job> out = new ArrayList<>(jobs);
        out.sort(Comparator.comparingInt((Job j) -> j.startedAt == 0 ? 0 : 1)
                .thenComparingLong(j -> j.startedAt == 0 ? j.endsAt : j.startedAt));
        return out;
    }

    /**
     * 완료 시각을 모르는 제작을, 앞 제작이 끝나는 시각에 이어 붙여 채운다.
     * 앞에 시간을 모르는 제작이 있으면 그 뒤도 알 수 없다.
     */
    private static void fillUnknown(List<Job> jobs) {
        for (List<Job> q : queues(jobs)) fillQueue(q);
    }

    private static void fillQueue(List<Job> ordered) {
        long cursor = 0;
        boolean chainOk = true;
        for (Job j : ordered) {
            if (j.endsAt > 0) {
                if (!j.synced) j.derived = chainOk && confident(j.name, j.count);
                chainOk = j.synced || j.derived;
                cursor = Math.max(cursor, j.endsAt);
                continue;
            }
            if (j.startedAt == 0) continue; // 창에서만 본 제작은 늘 시각을 안다(여기 올 일 없음)
            long dur = durationMs(j.name, j.count);
            if (dur <= 0) return;
            long begin = Math.max(j.startedAt, cursor);
            j.endsAt = begin + dur;
            j.derived = chainOk && confident(j.name, j.count);
            chainOk = j.derived;
            cursor = j.endsAt;
        }
    }

    /**
     * 이 아이템·개수의 제작 시간을 확실히 아는가: 서버가 보여 준 실제 시간을 배웠거나(같은 개수),
     * 한 번에 만들어지는 개수를 배워 개수에 따른 시간을 확실히 계산할 수 있을 때
     */
    static boolean confident(String name, int count) {
        String key = ItemKeys.normalize(name);
        return store.observedMs.containsKey(key + "*" + Math.max(1, count)) || store.batch.containsKey(key);
    }

    /** 이 제작의 완료 시각을 창에 보인 값으로 바로잡고, 뒤에 기다리는 어림값 제작도 같은 만큼 옮긴다 */
    private static void sync(List<Job> jobs, Job job, long ends) {
        job.endsAt = ends;
        job.synced = true;
        // 뒤에 기다리는(창에서 아직 못 본) 제작은 앞 제작이 끝나는 시각에 다시 이어 붙인다.
        // 예전에는 바뀐 만큼 더해 옮겨서, 앞 제작 둘을 차례로 맞추면 세 번째부터 두 번 밀렸다(1.5초 → 3초)
        boolean after = false;
        long cursor = 0;
        String q = job.queue == null ? "" : job.queue;
        for (Job j : queueOrder(jobs)) {
            if (j.separate || !q.equals(j.queue == null ? "" : j.queue)) continue;
            if (j == job) {
                after = true;
                cursor = j.endsAt;
                continue;
            }
            if (!after) continue;
            if (j.synced || j.startedAt == 0 || j.endsAt <= 0) {
                cursor = Math.max(cursor, j.endsAt);
                continue;
            }
            long dur = durationMs(j.name, j.count);
            if (dur <= 0) break;
            j.endsAt = Math.max(j.startedAt, cursor) + dur;
            cursor = j.endsAt;
        }
        fillUnknown(jobs);
    }

    private static boolean done(Job j, long now) {
        return j.endsAt > 0 && now >= j.endsAt;
    }

    // ---------------------------------------------------------------- 시설 창

    /** 10틱마다. 내가 연 창의 아이템 설명에서 제작 시간·남은 시간을 읽는다 */
    public static void scanScreen(MinecraftClient client) {
        if (!DdingMate.config().craft.enabled || client.player == null) return;
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        long now = System.currentTimeMillis();
        Set<Job> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean changed = false;
        // 대기열 칸: 레시피 칸이 아닌데 시간이 보이는 칸. 실제 창은 "제작 시간 : 1시간 29분 33초" 처럼
        // 그 제작의 남은 시간(스킬 적용 후, 맨 앞은 1초마다 줄어듦)을 보여 준다
        Set<String> islandsSeen = new HashSet<>();
        List<IslandIcons.IslandSlot> islandSlots = new ArrayList<>();
        Map<String, Long> recipeSeen = new LinkedHashMap<>();
        Set<String> recipeConflict = new HashSet<>();
        record QueueSlot(String name, String key, int count, Reading r) {}
        List<QueueSlot> queue = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack s = slot.getStack();
            if (s.isEmpty()) continue;
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            if (isVoyage(lore.lines())) {
                if (!isIslandSlot(s, lore.lines())) {
                    // "라비온 수하물 넣기" 칸: 섬 상태는 아니지만, 초기화 뒤 새 항해도에 보이는 칸이라 끝난 "항해 초기화" 줄은 정리한다
                    if (jobs.removeIf(j -> j.separate && j.name.equals(VOYAGE_RESET_NAME) && done(j, now))) changed = true;
                    continue;
                }
                String island = islandOf(s);
                if (!island.isEmpty()) {
                    islandsSeen.add(island);
                    islandSlots.add(new IslandIcons.IslandSlot(island, slot.x, slot.y));
                }
                if (voyage(jobs, s, lore.lines(), now)) changed = true;
                continue;
            }
            Reading r = read(lore.lines());
            if (r == null) continue;
            String name = cleanName(ItemKeys.stripGlyphs(s.getName().getString()));
            Matcher button = START_BUTTON.matcher(name.trim());
            if (button.matches() && r.totalSec > 0) {
                // 이 개수로 넣으면 걸리는 시간. 바로 뒤 시작 메시지가 오면 그 제작의 정확한 시간으로 쓴다
                buttonKey = ItemKeys.normalize(button.group(1));
                buttonMs = r.totalSec * 1000L;
                buttonAt = now;
                continue;
            }
            String key = ItemKeys.normalize(name);
            if (key.isEmpty()) continue;
            logOnce(name, lore.lines());
            if (r.recipe) {
                // 레시피 칸: 1개 만드는 데 걸리는 시간(스킬 전)만 배운다(제작 중인 것으로 등록하지 않는다).
                // 한 창에 같은 이름이 서로 다른 시간으로 보이면(실측: 조약돌 뭉치 5초·20초) 어느 쪽인지 몰라 배우지 않는다
                if (r.totalSec > 0) {
                    // 한 번에 여러 개 나오는 레시피는 개수와 함께 따로 기억한다(조약돌 뭉치 1개 5초, 7개 20초)
                    String rk = s.getCount() > 1 ? key + "*" + s.getCount() : key;
                    Long before = recipeSeen.putIfAbsent(rk, r.totalSec);
                    if (before != null && before != r.totalSec) recipeConflict.add(rk);
                }
                continue;
            }
            if (kr.ddingmate.counter.RecipeBook.recipeFor(name) == null
                    && jobs.stream().noneMatch(x -> !x.separate && ItemKeys.normalize(x.name).equals(key))) continue;
            int count = Math.max(1, s.getCount());
            // 대기열 칸("제작 시간 : N", "제작이 완료되었습니다")은 칸마다 다른 제작이다(2026-10 실제 창: 같은 제작이 두 칸에
            // 보인 적 없음). 진행 중 6초·대기 7초처럼 비슷해도 합치지 않는다. "남은 시간"이 적힌 칸만 같은 값이면 하나로 본다
            boolean dup = !r.own && !r.done && queue.stream().anyMatch(q -> q.key().equals(key) && q.count() == count
                    && !q.r().own && !q.r().done && Math.abs(q.r().remainingSec - r.remainingSec) <= 2);
            if (!dup) queue.add(new QueueSlot(name, key, count, r));
        }
        for (Map.Entry<String, Long> e : recipeSeen.entrySet()) {
            if (recipeConflict.contains(e.getKey()) || Objects.equals(store.secondsPerJob.get(e.getKey()), e.getValue())) continue;
            store.secondsPerJob.put(e.getKey(), e.getValue());
            fillUnknown(jobs);
            changed = true;
        }
        // 대기열 칸은 창에 놓인 순서 = 넣은 순서다. 그 순서대로 같은 이름의 제작과 짝짓는다(한 제작은 한 칸과만).
        // 예전에는 남은 시간이 짧은 칸부터 짝지어, 기다리는 칸의 시간(7개 12분)이 진행 중인 칸(40개 40분)보다 짧으면 뒤바뀌었다
        String here = "@" + kr.ddingmate.game.ServerHud.area();
        boolean anyHere = jobs.stream().anyMatch(x -> !x.separate && x.queue != null && x.queue.endsWith(here));
        // 칸 순서대로 (칸, 제작) 짝. 모드가 모르는 진행 중 제작(모드를 깔기 전에 넣었거나 시작 메시지를 놓침)은
        // 제작 없이 자리만 차지하는 칸으로 둔다 → 그 뒤 제작은 이 칸이 끝난 뒤부터 센다
        record Pair(QueueSlot slot, Job job) {}
        List<Pair> pairs = new ArrayList<>();
        for (int qi = 0; qi < queue.size(); qi++) {
            QueueSlot q = queue.get(qi);
            List<QueueSlot> later = queue.subList(qi + 1, queue.size());
            List<Job> same = queueOrder(jobs).stream()
                    .filter(x -> !x.separate && !matched.contains(x) && ItemKeys.normalize(x.name).equals(q.key()))
                    .filter(x -> !anyHere || x.queue == null || x.queue.isEmpty() || x.queue.endsWith(here)).toList();
            // 개수가 같은 제작 → 없으면 개수가 다른 제작 중, 뒤 칸에 제 개수와 맞는 칸이 없는 것(그 칸을 기다린다)
            Job job = same.stream().filter(x -> x.count == q.count()).findFirst()
                    .orElse(same.stream().filter(x -> later.stream().noneMatch(l -> l.key().equals(q.key()) && l.count() == x.count))
                            .findFirst().orElse(null));
            if (job == null) {
                if (q.r().done || q.r().remainingSec <= 0) continue;
                if (q.r().own) {
                    pairs.add(new Pair(q, null)); // 모르는 제작: 자리만 차지
                    continue;
                }
                // "남은 시간"이 분명히 적힌 칸은 모르는 제작이라도 새로 등록한다
                job = new Job();
                job.name = q.name();
                job.count = q.count();
                job.queue = queueOf(q.name());
                jobs.add(job);
                changed = true;
            }
            matched.add(job);
            pairs.add(new Pair(q, job));
        }
        // 칸 순서 = 대기열 순서로 완료 시각을 정한다. "남은 시간"은 지금부터, 칸의 "제작 시간"은 앞 칸이 끝난 뒤부터
        long cursor = now;
        boolean relearned = false;
        for (Pair pr : pairs) {
            Reading r = pr.slot().r();
            Job job = pr.job();
            if (job == null) {
                cursor = Math.max(cursor, now) + r.remainingSec * 1000;
                continue;
            }
            if (r.own && !r.done && job.startedAt > 0 && now - job.startedAt <= 5000 && cursor <= now
                    && learn(job, now - job.startedAt + r.remainingSec * 1000)) {
                relearned = true;
            }
            boolean front = cursor <= now + 500;
            if (r.done && job.seenRunningAt > 0 && now - job.seenRunningAt <= 400) {
                // 방금(0.1초 전)까지 돌던 맨 앞 제작이 "완료"로 바뀌었다 → 그 사이가 실제 완료 시각(창 숫자 반올림과 무관)
                long exact = (job.seenRunningAt + now) / 2;
                job.seenRunningAt = 0;
                if (!job.synced || Math.abs(job.endsAt - exact) > 50) {
                    sync(jobs, job, exact);
                    changed = true;
                }
                cursor = Math.max(cursor, job.endsAt);
                continue;
            }
            job.seenRunningAt = !r.done && front ? now : 0;
            long ends = r.done ? Math.min(job.endsAt == 0 ? now : job.endsAt, now)
                    : (r.own ? Math.max(cursor, now) : now) + r.remainingSec * 1000;
            if (!job.synced && job.derived && !r.done && Math.abs(job.endsAt - ends) <= 1000) {
                // 시작 메시지 시각 + 서버 규칙으로 계산한 시각이 창의 초 단위 숫자보다 정확하다(창 숫자는 1초 단위로 끊김) → 그대로 두고 확인만
                job.synced = true;
                changed = true;
            } else if (!job.synced || Math.abs(job.endsAt - ends) > 1500) {
                sync(jobs, job, ends);
                changed = true;
            }
            cursor = Math.max(cursor, job.endsAt);
        }
        // 실제 시간을 새로 배웠으면, 창에 안 보이는 뒤 제작들의 어림값도 그 시간으로 다시 계산한다
        if (relearned) {
            recalculate();
            changed = true;
        }
        // 항해 창: 지금 창에 없는 섬(초기화로 바뀐 섬)의 끝난 항해 줄은 정리한다
        // 섬 칸은 투명 버튼이고 섬 그림은 창 제목 글꼴로 찍혀 있다 → 제목에서 섬마다 그림을 찾아 둔다
        if (!islandSlots.isEmpty()) IslandIcons.learn(screen.getTitle(), islandSlots);
        if (islandsSeen.size() >= VOYAGE_MAP_MIN) {
            changed |= jobs.removeIf(j -> j.separate && j.name.endsWith(VOYAGE) && !j.name.equals(VOYAGE_RESET_NAME)
                    && done(j, now) && !islandsSeen.contains(j.name.substring(0, j.name.length() - VOYAGE.length()).trim()));
        }
        if (changed) {
            dirty = true;
            if (now - lastSave > 5000) save();
        }
    }

    /** 설명 줄에서 남은 시간·제작 1번 시간·완료 여부를 읽는다. 시간과 관계없는 설명이면 null */
    public static Reading read(List<Text> lines) {
        long remaining = -1, total = -1;
        boolean done = false, recipe = false;
        for (Text line : lines) {
            String t = Formatting.strip(line.getString());
            if (t == null) continue;
            t = ItemKeys.stripGlyphs(t);
            if (RECIPE_HINT.matcher(t).find()) recipe = true;
            long sec = seconds(t);
            if (sec >= 0) {
                if (TOTAL_HINT.matcher(t).find()) total = sec;
                else if (REMAIN_HINT.matcher(t).find()) remaining = sec;
            } else if (DONE_HINT.matcher(t).find() && !REMAIN_HINT.matcher(t).find()) {
                done = true;
            }
        }
        // 레시피 칸이 아닌 칸의 "제작 시간"은 그 제작의 남은 시간(실제 대기열 칸, 1초마다 줄어듦)
        boolean own = false;
        if (!recipe && remaining < 0 && total > 0) {
            remaining = total;
            total = -1;
            own = true;
        }
        if (remaining == 0) done = true;
        if (remaining < 0 && total < 0 && !done) return null;
        return new Reading(done ? 0 : remaining, total, done, recipe, own);
    }

    /** own: 칸의 "제작 시간"(앞 제작이 끝난 뒤부터 세는 그 제작만의 시간) — "남은 시간"(지금부터)과 구분 */
    public record Reading(long remainingSec, long totalSec, boolean done, boolean recipe, boolean own) {}

    /** 줄에서 시간(초)을 찾는다. 없으면 -1 */
    static long seconds(String t) {
        Matcher c = CLOCK_TIME.matcher(t);
        if (c.find()) {
            if (c.group(3) != null) return parseInt(c.group(1)) * 3600L + parseInt(c.group(2)) * 60L + parseInt(c.group(3));
            return parseInt(c.group(1)) * 60L + parseInt(c.group(2));
        }
        Matcher m = KOREAN_TIME.matcher(t);
        while (m.find()) {
            if (m.group(1) == null && m.group(2) == null && m.group(3) == null && m.group(4) == null) {
                if (m.end() >= t.length()) break;
                continue;
            }
            return parseInt(m.group(1)) * 86400L + parseInt(m.group(2)) * 3600L + parseInt(m.group(3)) * 60L + secondsPart(m.group(4));
        }
        return -1;
    }

    /** "7" → 7, "6.3" → 7 (서버처럼 초 단위 올림), 없으면 0 */
    private static long secondsPart(String s) {
        if (s == null) return 0;
        if (!s.contains(".")) return parseInt(s);
        return (long) Math.ceil(Double.parseDouble(s) - 1e-9);
    }

    private static void logOnce(String name, List<Text> lines) {
        StringBuilder sb = new StringBuilder();
        for (Text l : lines) sb.append(" | ").append(ItemKeys.stripGlyphs(l.getString()));
        String sig = ItemKeys.normalize(name) + sb.toString().replaceAll("\\d", "#");
        if (LOGGED.size() >= 60 || !LOGGED.add(sig)) return;
        DdingMate.LOG.info("제작 시간 읽음: {}{}", name, sb);
    }

    public static String cleanName(String name) {
        String n = Formatting.strip(name);
        return (n == null ? name : n).replaceFirst("(\\s*\\[\\+\\d+\\])+\\s*$", "").trim();
    }

    // ---------------------------------------------------------------- 항해

    /** 항해 타이머 이름 끝: "유실라 항해" */
    public static final String VOYAGE = "항해";
    /**
     * 항해 창의 섬 칸에만 붙는 문구(2026-10-10 실제): 항해 전·중·돌아옴은 "항해를 통해 다양한 아이템을 획득해보세요.",
     * 보상까지 받은 섬은 "이미 항해를 마친 섬/대륙입니다." — "항해" 글자만 보면 전문가 창의 항해 스킬 칸
     * ("[세레니티] 가벼운 항해선": "항해 포인트가 5% 높게…")이나 수하물 칸까지 섬으로 잘못 읽는다
     */
    private static final Pattern VOYAGE_HINT = Pattern.compile("항해를\\s*통해\\s*다양한\\s*아이템|이미\\s*항해를\\s*마친|초기화까지\\s*남은");
    /** 항해 창 전체(섬 지도)라고 볼 섬 칸 수. 수하물 넣기 창처럼 섬 하나만 보이는 창에서는 다른 섬 줄을 정리하지 않는다 */
    static final int VOYAGE_MAP_MIN = 3;
    /**
     * 돌아와 보상을 받을 수 있는 칸(2026-10-10 실제 "[ 유실라 보상 수령 ]": "클릭 시 항해 보상을 수령합니다.").
     * "항해선이 돌아와 보상을 수령할 수 있습니다!"는 모든 섬 칸에 붙는 안내 문구일 수 있어 쓰지 않는다
     */
    private static final Pattern VOYAGE_READY = Pattern.compile("항해\\s*보상을\\s*수령합니다");
    /**
     * 칸 이름에서 섬 이름만(2026-10-10 실제): "[ 유실라 보상 수령 ]"(돌아옴), "[ 벨로아 빠른 항해 ]"(항해 중, "남은 시간 : 2시간 16분 15초"),
     * "[ 유실라 항해 완료 ]"(보상까지 받음) → 유실라·벨로아
     */
    private static final Pattern VOYAGE_NAME = Pattern.compile("^\\[?\\s*(.+?)\\s*(?:보상\\s*수령|빠른\\s*항해.*|항해.*|출항.*|보내기.*)?\\s*]?$");

    private static boolean isVoyage(List<Text> lines) {
        for (Text l : lines) if (VOYAGE_HINT.matcher(ItemKeys.stripGlyphs(l.getString())).find()) return true;
        return false;
    }

    /** 항해 섬 칸인가(수하물 넣기 칸은 아님) */
    private static boolean isIslandSlot(ItemStack stack, List<Text> lines) {
        return isVoyage(lines) && !ItemKeys.stripGlyphs(stack.getName().getString()).contains("수하물");
    }

    /**
     * 항해 창의 섬 칸을 읽는다. 돌아온 칸("보상 수령")이면 완료로, 남은 시간이 보이는 칸이면 그 시각으로 맞춘다.
     * "항해 소요 시간"은 걸리는 시간일 뿐 남은 시간이 아니라 쓰지 않는다. 바꾼 게 있으면 true
     */
    static boolean voyage(List<Job> jobs, ItemStack stack, List<Text> lines, long now) {
        if (!isVoyage(lines)) return false;
        boolean ready = false;
        long remaining = -1;
        long resetSec = -1;
        StringBuilder all = new StringBuilder();
        for (Text l : lines) {
            String t = ItemKeys.stripGlyphs(l.getString());
            all.append(" | ").append(t);
            if (VOYAGE_RESET.matcher(t).find()) {
                // "초기화까지 남은 시간 : 12초" 는 항해 남은 시간이 아니다(모든 섬을 마쳐 초기화를 기다리는 칸)
                long sec = seconds(t);
                resetSec = Math.max(0, sec);
                continue;
            }
            if (VOYAGE_READY.matcher(t).find()) ready = true;
            if (REMAIN_HINT.matcher(t).find()) {
                long sec = seconds(t);
                if (sec >= 0) remaining = sec;
            }
        }
        String rawName = ItemKeys.stripGlyphs(stack.getName().getString()).trim();
        if (rawName.matches(".*보상\\s*수령.*")) ready = true;
        String island = islandOf(stack);
        if (island.isEmpty()) return false;
        String sig = "voyage:" + island + all.toString().replaceAll("\\d", "#");
        if (LOGGED.size() < 60 && LOGGED.add(sig)) DdingMate.LOG.info("항해 칸 읽음: {}{}", island, all);
        String name = island + " " + VOYAGE;
        Job job = jobs.stream().filter(j -> j.separate && j.name.equals(name)).findFirst().orElse(null);
        if (resetSec >= 0) {
            // 이 섬은 보상까지 받았고 모든 섬이 끝나 초기화를 기다린다 → 섬 줄은 빼고 "항해 초기화" 한 줄로
            boolean changed = job != null && jobs.remove(job);
            return voyageReset(jobs, resetSec, now) || changed;
        }
        Job reset = jobs.stream().filter(j -> j.separate && j.name.equals(VOYAGE_RESET_NAME)).findFirst().orElse(null);
        if (reset != null && reset.endsAt > 0 && reset.endsAt <= now) {
            jobs.remove(reset); // 초기화가 끝나 섬 칸이 다시 보이면 정리
            return true;
        }
        if (!ready && remaining < 0) {
            // 돌아온 항해가 보상 수령 칸에서 다른 칸으로 바뀌었으면 받아 간 것 → 목록에서 뺀다
            if (job != null && job.endsAt > 0 && job.endsAt <= now) {
                jobs.remove(job);
                return true;
            }
            return false;
        }
        if (ready) {
            if (job == null || job.endsAt == 0 || job.endsAt > now) {
                if (job == null) {
                    job = new Job();
                    job.name = name;
                    job.separate = true;
                    job.notified = true; // 이미 돌아온 걸 창에서 본 것이라 알리지 않는다
                    jobs.add(job);
                }
                job.endsAt = now;
                job.synced = true;
                return true;
            }
            return false;
        }
        long ends = now + remaining * 1000;
        if (job == null) {
            job = new Job();
            job.name = name;
            job.separate = true;
            jobs.add(job);
        } else if (job.synced && Math.abs(job.endsAt - ends) <= 1500) {
            return false;
        }
        job.endsAt = ends;
        job.synced = true;
        job.notified = false;
        return true;
    }

    /** 모든 섬을 마친 뒤 항해가 초기화되기까지(2026-10-10 실제 칸: "초기화까지 남은 시간 : 12초") */
    private static final Pattern VOYAGE_RESET = Pattern.compile("초기화까지\\s*남은");
    public static final String VOYAGE_RESET_NAME = "항해 초기화";
    /** 초기화는 마지막 섬 보상을 받은 뒤 1분(사용자 실측). 창의 남은 시간은 연 순간 값에서 멈춰 있어 한 번만 쓴다 */
    static final long VOYAGE_RESET_MS = 60_000;
    private static long lastVoyageClaim;

    private static String islandOf(ItemStack stack) {
        Matcher m = VOYAGE_NAME.matcher(ItemKeys.stripGlyphs(stack.getName().getString()).trim());
        return m.matches() ? m.group(1).trim() : "";
    }

    /**
     * 항해를 보냈다: 그 섬 줄을 새로 만든다. 남은 시간은 채팅에 없어서 항해 창 칸("남은 시간 : …")을 읽을 때 정확히 맞춘다
     * (보내는 동안 창이 열려 있어 보통 0.5초 안에 맞춰진다). 초기화를 기다리던 줄은 이제 필요 없다
     */
    private static void voyageStarted(String island, long now) {
        List<Job> jobs = jobsForWrite();
        if (jobs == null || island.isEmpty() || island.length() > 30) return;
        String name = island + " " + VOYAGE;
        jobs.removeIf(j -> j.separate && (j.name.equals(name) || j.name.equals(VOYAGE_RESET_NAME)));
        Job j = new Job();
        j.name = name;
        j.separate = true;
        j.startedAt = now;
        jobs.add(j);
        changed();
    }

    private static boolean voyageReset(List<Job> jobs, long shownSec, long now) {
        // 모든 섬을 마쳐 초기화를 기다린다 → 섬 항해 줄은 모두 지우고 "항해 초기화" 한 줄만(새 섬은 항해를 보내면 다시 생긴다)
        boolean removed = jobs.removeIf(j -> j.separate && j.name.endsWith(VOYAGE) && !j.name.equals(VOYAGE_RESET_NAME));
        Job reset = jobs.stream().filter(j -> j.separate && j.name.equals(VOYAGE_RESET_NAME)).findFirst().orElse(null);
        if (reset != null && reset.endsAt > now) return removed; // 이미 세는 중: 멈춰 있는 창 값으로 다시 맞추지 않는다
        if (reset != null) jobs.remove(reset);
        long ends = lastVoyageClaim > 0 && now - lastVoyageClaim < 2 * VOYAGE_RESET_MS
                ? lastVoyageClaim + VOYAGE_RESET_MS : now + shownSec * 1000;
        if (ends <= now) return reset != null || removed;
        Job j = new Job();
        j.name = VOYAGE_RESET_NAME;
        j.separate = true;
        j.synced = true;
        j.endsAt = ends;
        jobs.add(j);
        return true;
    }

    /** 시험용: 시작 버튼을 지금 본 것으로 */
    public static void debugButton(String name, long ms) {
        buttonKey = ItemKeys.normalize(name);
        buttonMs = ms;
        buttonAt = System.currentTimeMillis();
    }

    /** 시험용 */
    public static void debugVoyageClaimAt(long at) {
        lastVoyageClaim = at;
    }

    /** 시험용 */
    public static boolean debugVoyage(ItemStack stack, List<Text> lines) {
        List<Job> jobs = jobsForWrite();
        return jobs != null && voyage(jobs, stack, lines, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- 알림

    /** 1초마다: 같은 아이템·개수로 넣은 제작이 모두 끝나면 한 번 알린다(조약돌 뭉치 ×7 [4]) */
    public static void tick() {
        if (!DdingMate.config().craft.enabled) return;
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        Map<String, Job> finished = new LinkedHashMap<>();
        for (Job j : jobs) {
            if (done(j, now) && !j.notified && (j.synced || j.derived || j.separate)) {
                j.notified = true;
                changed = true;
                finished.put(groupKey(j), j);
            }
        }
        for (Job j : finished.values()) {
            String k = groupKey(j);
            boolean waiting = jobs.stream().anyMatch(o -> groupKey(o).equals(k) && !done(o, now));
            if (waiting) continue; // 같은 것이 아직 남았으면 다 끝날 때 한 번에
            List<Job> all = jobs.stream().filter(o -> groupKey(o).equals(k) && done(o, now)).toList();
            boolean same = all.stream().allMatch(o -> o.count == j.count);
            int sum = all.stream().mapToInt(o -> o.count).sum();
            if (DdingMate.config().craft.notify && j.name.equals(VOYAGE_RESET_NAME)) {
                Notifier.voyageReset();
            } else if (DdingMate.config().craft.notify && j.name.endsWith(VOYAGE)) {
                Notifier.voyageDone(j.name.substring(0, j.name.length() - VOYAGE.length()).trim());
            } else if (DdingMate.config().craft.notify) {
                if (same) Notifier.craftDone(j.name, j.count, all.size());
                else Notifier.craftDone(j.name, sum, 1);
            }
        }
        // 다 되고 하루가 지나도 안 꺼낸 기록, 일주일 넘게 시간을 모르는 기록은 정리(서버에서 이미 꺼냈을 수 있다)
        changed |= jobs.removeIf(j -> j.endsAt > 0 && now - j.endsAt > 24L * 3600_000L)
                | jobs.removeIf(j -> j.endsAt == 0 && j.startedAt > 0 && now - j.startedAt > 7L * 24 * 3600_000L);
        if (changed) changed();
    }

    private static String groupKey(Job j) {
        return (j.separate ? "s:" : "q:") + ItemKeys.normalize(j.name);
    }

    // ---------------------------------------------------------------- 읽기

    /** 지금 계정의 제작(대기열 순) */
    public static List<Job> jobs() {
        String key = accountKey();
        List<Job> jobs = key == null ? null : store.accounts.get(key);
        return jobs == null ? List.of() : queueOrder(jobs);
    }

    /** 화면에 보일 묶음: 같은 아이템·개수·완료 여부끼리 한 줄. 완료가 가까운 순(시간 모르는 것은 뒤로) */
    public static List<Group> groups() {
        long now = System.currentTimeMillis();
        Map<String, List<Job>> by = new LinkedHashMap<>();
        for (Job j : jobs()) by.computeIfAbsent(groupKey(j) + (done(j, now) ? "!" : ""), k -> new ArrayList<>()).add(j);
        List<Group> out = new ArrayList<>();
        for (List<Job> g : by.values()) {
            Job first = g.get(0);
            boolean unknown = g.stream().anyMatch(j -> j.endsAt == 0);
            long ends = unknown ? 0 : g.stream().mapToLong(j -> j.endsAt).max().orElse(0);
            boolean isDone = !unknown && now >= ends;
            boolean estimated = g.stream().anyMatch(j -> !j.synced && !j.derived);
            out.add(new Group(first.name, first.count, g.size(), ends, isDone, estimated, unknown, List.copyOf(g)));
        }
        out.sort(Comparator.comparing((Group g) -> !g.done()).thenComparingLong(g -> g.unknown() ? Long.MAX_VALUE : g.endsAt()));
        return out;
    }

    public static void remove(Group group) {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        Set<Job> drop = Collections.newSetFromMap(new IdentityHashMap<>());
        drop.addAll(group.jobs());
        if (jobs.removeIf(drop::contains)) changed();
    }

    /** 남은 시간 글: "1분 20초"(어림값이면 "약 "), 끝났으면 "완료!", 모르면 안내 */
    public static String remainingText(Group g, long now) {
        // 확실하지 않은 시각(서버에서 확인 전)은 어림 숫자 대신 확인 방법을 보여 준다
        if (g.unknown() || g.estimated()) return unknownText(g.name());
        return text(g.endsAt(), g.estimated(), now);
    }

    public static String remainingText(Job j, long now) {
        if (j.endsAt == 0 || (!j.synced && !j.derived)) return unknownText(j.name);
        return text(j.endsAt, !j.synced, now);
    }

    /** 남은 시간을 아직 모를 때: 어디를 열면 정확히 맞춰지는지 */
    private static String unknownText(String name) {
        return name != null && name.endsWith(VOYAGE) ? "항해 창 열면 표시" : "시설 창 열면 표시";
    }

    private static String text(long endsAt, boolean estimated, long now) {
        long sec = Math.max(0, (endsAt - now + 999) / 1000);
        if (sec == 0) return "완료!";
        String s;
        if (sec >= 3600) s = (sec / 3600) + "시간 " + (sec % 3600 / 60) + "분";
        else if (sec >= 60) s = (sec / 60) + "분 " + (sec % 60) + "초";
        else s = sec + "초";
        return s;
    }

    /** 제작 1번에 걸리는 초. 모르면 -1 */
    public static long secondsPerJob(String name) {
        Long d = store.secondsPerJob.get(ItemKeys.normalize(name));
        if (d != null) return d;
        // 아직 시설 창을 안 열었어도: 모드에 넣어 둔 시간(실제 서버에서 모은 값)
        return kr.ddingmate.counter.RecipeBook.timeFor(name);
    }

    private static List<Job> jobsForWrite() {
        String key = accountKey();
        if (key == null) return null;
        return store.accounts.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private static String accountKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID id = client.player != null ? client.player.getUuid() : null;
        if (id == null) return null;
        ServerInfo server = client.getCurrentServerEntry();
        String where = server == null ? "singleplayer" : server.address.toLowerCase(Locale.ROOT);
        return id + "@" + where;
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 저장

    private static void changed() {
        dirty = true;
        save();
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s == null) return;
            if (s.accounts == null) s.accounts = new LinkedHashMap<>();
            if (s.secondsPerJob == null) s.secondsPerJob = new LinkedHashMap<>();
            if (s.observedMs == null) s.observedMs = new LinkedHashMap<>();
            if (s.batch == null) s.batch = new LinkedHashMap<>();
            // 이름 규칙(별 그림 글자 등)이 바뀌었을 수 있어 키를 다시 만든다
            Map<String, Long> rekeyed = new LinkedHashMap<>();
            s.secondsPerJob.forEach((k, v) -> rekeyed.put(ItemKeys.normalize(k), v));
            s.secondsPerJob = rekeyed;
            s.accounts.values().removeIf(Objects::isNull);
            if (s.version < 2) {
                s.accounts.clear(); // 예전 방식으로 센 제작(레시피 칸을 제작으로 잘못 등록한 것 포함)은 버린다
                s.version = 2;
                dirty = true;
            }
            if (s.version < 3) {
                // 0.3.4 까지: 뱃지 창 레시피 칸을 창에서만 본 제작으로 잘못 만든 것을 지운다(시작 메시지가 없던 제작)
                for (List<Job> l : s.accounts.values()) l.removeIf(j -> j != null && !j.separate && j.startedAt == 0);
                s.version = 3;
                dirty = true;
            }
            for (List<Job> l : s.accounts.values()) l.removeIf(j -> j == null || j.name == null || j.name.isBlank());
            for (List<Job> l : s.accounts.values()) for (Job j : l) if (j.queue == null) j.queue = "";
            store = s;
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("제작 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    public static void save() {
        if (!dirty) return;
        lastSave = System.currentTimeMillis();
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(store, w);
            }
            dirty = false;
        } catch (IOException e) {
            DdingMate.LOG.error("제작 기록 저장 실패", e);
        }
    }

    /** 접속이 끊기면 진단 기록을 다시 남길 수 있게 */
    public static void onDisconnect() {
        save();
        LOGGED.clear();
    }

    /** 시험용 */
    public static void debugClear() {
        store = fresh();
        dirty = true;
    }

    /** 시험용: 제작 1번 시간을 정해 둔다 */
    public static void debugLearn(String name, long seconds) {
        store.secondsPerJob.put(ItemKeys.normalize(name), seconds);
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-crafts.json");
    }
}
