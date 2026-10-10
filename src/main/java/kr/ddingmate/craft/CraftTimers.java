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
        /** 대기열과 따로 도는 향장품 제조 */
        public boolean separate;
        public boolean notified;
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
    private static final Pattern PERFUME_DONE =Pattern.compile("^제조\\s*완료된\\s*향장품을\\s*성공적으로\\s*수령");
    public static final String PERFUME = "향장품";

    /** 설명 줄의 시간: "2분 0초", "1시간 2분 3초", "45초" */
    private static final Pattern KOREAN_TIME = Pattern.compile("(?:(\\d+)\\s*일)?\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?");
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
        if (t.contains(" : ")) return; // 사람이 쓴 채팅은 무시
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
    private static void start(String name, int count, long now) {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        Job j = new Job();
        j.name = name;
        j.count = Math.max(1, count);
        j.startedAt = now;
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
        long base = secondsPerJob(name);
        if (base <= 0) return -1;
        kr.ddingmate.counter.RecipeBook.Recipe r = kr.ddingmate.counter.RecipeBook.recipeFor(name);
        int per = r == null ? 1 : Math.max(1, r.output);
        long times = Math.max(1, (Math.max(1, count) + per - 1) / per);
        int pct = reductionPercent(kr.ddingmate.counter.RecipeBook.facilityOf(name));
        return Math.round(base * times * 1000.0 * (100 - pct) / 100.0);
    }

    /** 스킬 레벨을 바꾸면 아직 안 끝난 어림값 제작을 처음부터 다시 계산한다 */
    public static void recalculate() {
        List<Job> jobs = jobsForWrite();
        if (jobs == null) return;
        long now = System.currentTimeMillis();
        long cursor = 0;
        boolean unknown = false;
        for (Job j : queueOrder(jobs)) {
            if (j.separate) continue;
            if (j.synced || j.startedAt == 0 || (j.endsAt > 0 && j.endsAt <= now)) {
                cursor = Math.max(cursor, j.endsAt);
                continue;
            }
            long dur = durationMs(j.name, j.count);
            if (unknown || dur <= 0) {
                j.endsAt = 0;
                unknown = true;
                continue;
            }
            j.endsAt = Math.max(j.startedAt, cursor) + dur;
            j.notified = false;
            cursor = j.endsAt;
        }
        changed();
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
        long cursor = 0;
        for (Job j : queueOrder(jobs)) {
            if (j.separate) continue;
            if (j.endsAt > 0) {
                cursor = Math.max(cursor, j.endsAt);
                continue;
            }
            if (j.startedAt == 0) continue; // 창에서만 본 제작은 늘 시각을 안다(여기 올 일 없음)
            long dur = durationMs(j.name, j.count);
            if (dur <= 0) return;
            long begin = Math.max(j.startedAt, cursor);
            j.endsAt = begin + dur;
            cursor = j.endsAt;
        }
    }

    /** 이 제작의 완료 시각을 창에 보인 값으로 바로잡고, 뒤에 기다리는 어림값 제작도 같은 만큼 옮긴다 */
    private static void sync(List<Job> jobs, Job job, long ends) {
        long delta = job.endsAt > 0 ? ends - job.endsAt : 0;
        boolean after = false;
        for (Job j : queueOrder(jobs)) {
            if (j == job) {
                after = true;
                continue;
            }
            if (after && !j.separate && !j.synced && j.endsAt > 0 && delta != 0) j.endsAt += delta;
        }
        job.endsAt = ends;
        job.synced = true;
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
        record QueueSlot(String name, String key, int count, Reading r) {}
        List<QueueSlot> queue = new ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack s = slot.getStack();
            if (s.isEmpty()) continue;
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            if (isVoyage(lore.lines())) {
                String island = islandOf(s);
                if (!island.isEmpty()) islandsSeen.add(island);
                if (voyage(jobs, s, lore.lines(), now)) changed = true;
                continue;
            }
            Reading r = read(lore.lines());
            if (r == null) continue;
            String name = cleanName(ItemKeys.stripGlyphs(s.getName().getString()));
            String key = ItemKeys.normalize(name);
            if (key.isEmpty()) continue;
            logOnce(name, lore.lines());
            if (r.recipe) {
                // 레시피 칸: 1개 만드는 데 걸리는 시간(스킬 전)만 배운다(제작 중인 것으로 등록하지 않는다)
                if (r.totalSec > 0 && !Objects.equals(store.secondsPerJob.get(key), r.totalSec)) {
                    store.secondsPerJob.put(key, r.totalSec);
                    fillUnknown(jobs);
                    changed = true;
                }
                continue;
            }
            if (kr.ddingmate.counter.RecipeBook.recipeFor(name) == null
                    && jobs.stream().noneMatch(x -> !x.separate && ItemKeys.normalize(x.name).equals(key))) continue;
            int count = Math.max(1, s.getCount());
            // 같은 제작이 두 칸(진행 칸·대기열 칸)에 보이면 하나로
            boolean dup = queue.stream().anyMatch(q -> q.key().equals(key) && q.count() == count
                    && q.r().done == r.done && Math.abs(q.r().remainingSec - r.remainingSec) <= 2);
            if (!dup) queue.add(new QueueSlot(name, key, count, r));
        }
        // 남은 시간이 짧은 칸부터 → 같은 개수 중 먼저 넣은 제작과 짝짓는다(한 제작은 한 칸과만)
        queue.sort(Comparator.comparingLong(q -> q.r().done ? -1 : q.r().remainingSec));
        Map<Job, Reading> seen = new IdentityHashMap<>();
        for (QueueSlot q : queue) {
            List<Job> same = queueOrder(jobs).stream()
                    .filter(x -> !x.separate && !matched.contains(x) && ItemKeys.normalize(x.name).equals(q.key())).toList();
            Job job = same.stream().filter(x -> x.count == q.count()).findFirst()
                    .orElse(same.isEmpty() ? null : same.get(0));
            if (job == null) {
                if (q.r().done || q.r().remainingSec <= 0) continue;
                // 시작 메시지 없이 창에서만 새 제작을 만드는 건 "남은 시간"이 분명히 적힌 칸뿐이다.
                // 칸의 "제작 시간"은 레시피 칸 설명일 수도 있어(뱃지 창) 이미 넣은 제작을 맞출 때만 쓴다
                if (q.r().own) continue;
                // 모드를 깔기 전에 넣었거나 시작 메시지를 놓친 제작
                job = new Job();
                job.name = q.name();
                job.count = q.count();
                jobs.add(job);
                changed = true;
            }
            matched.add(job);
            seen.put(job, q.r());
        }
        // 대기열 순서대로 완료 시각을 정한다. "남은 시간"은 지금부터, 칸의 "제작 시간"은 앞 제작이 끝난 뒤부터
        long cursor = now;
        for (Job job : queueOrder(jobs)) {
            if (job.separate) continue;
            Reading r = seen.get(job);
            if (r == null) {
                if (job.endsAt > 0) cursor = Math.max(cursor, job.endsAt);
                continue;
            }
            long ends = r.done ? Math.min(job.endsAt == 0 ? now : job.endsAt, now)
                    : (r.own ? Math.max(cursor, now) : now) + r.remainingSec * 1000;
            if (!job.synced || Math.abs(job.endsAt - ends) > 1500) {
                sync(jobs, job, ends);
                changed = true;
            }
            cursor = Math.max(cursor, job.endsAt);
        }
        // 항해 창: 지금 창에 없는 섬(초기화로 바뀐 섬)의 끝난 항해 줄은 정리한다
        if (!islandsSeen.isEmpty()) {
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
            return parseInt(m.group(1)) * 86400L + parseInt(m.group(2)) * 3600L + parseInt(m.group(3)) * 60L + parseInt(m.group(4));
        }
        return -1;
    }

    private static void logOnce(String name, List<Text> lines) {
        StringBuilder sb = new StringBuilder();
        for (Text l : lines) sb.append(" | ").append(ItemKeys.stripGlyphs(l.getString()));
        String sig = ItemKeys.normalize(name) + sb.toString().replaceAll("\\d", "#");
        if (LOGGED.size() >= 60 || !LOGGED.add(sig)) return;
        DdingMate.LOG.info("제작 시간 읽음: {}{}", name, sb);
    }

    private static String cleanName(String name) {
        String n = Formatting.strip(name);
        return (n == null ? name : n).replaceFirst("(\\s*\\[\\+\\d+\\])+\\s*$", "").trim();
    }

    // ---------------------------------------------------------------- 항해

    /** 항해 타이머 이름 끝: "유실라 항해" */
    public static final String VOYAGE = "항해";
    private static final Pattern VOYAGE_HINT = Pattern.compile("항해");
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
            if (done(j, now) && !j.notified) {
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
            boolean estimated = g.stream().anyMatch(j -> !j.synced);
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
        if (g.unknown()) return "시설 창 열면 표시";
        return text(g.endsAt(), g.estimated(), now);
    }

    public static String remainingText(Job j, long now) {
        if (j.endsAt == 0) return "시설 창 열면 표시";
        return text(j.endsAt, !j.synced, now);
    }

    private static String text(long endsAt, boolean estimated, long now) {
        long sec = Math.max(0, (endsAt - now + 999) / 1000);
        if (sec == 0) return "완료!";
        String s;
        if (sec >= 3600) s = (sec / 3600) + "시간 " + (sec % 3600 / 60) + "분";
        else if (sec >= 60) s = (sec / 60) + "분 " + (sec % 60) + "초";
        else s = sec + "초";
        return (estimated ? "약 " : "") + s;
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
