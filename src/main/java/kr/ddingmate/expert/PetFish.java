package kr.ddingmate.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.notify.Notifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 반려어(수족관) 밥 타이머. 위키 수족관 문서(2026-10-31)와 실제 관찰(수질 만렙 어항 5마리 × 3번)대로 계산한다.
 * <ul>
 * <li>허기·기분·활력은 10분마다(해양 스킬 "수질 유지 기술" Lv1~4: 11·14·17·20분) 가장 높은 스탯 하나가 20~25%씩 준다
 * (2026-10-31 관찰: 20분마다, 매번 가장 높은 스탯, 20~25). 하나라도 0% 면 폐사</li>
 * <li>회복: 빵 허기 1%, 덩굴 기분 2%, 뼈 블록 활력 2%</li>
 * <li>어항에 넣은 때부터 2시간 동안만 자라고(성장도 최대 100), 세 스탯 평균이 80% 이상이면 100%, 50~79% 70%, 1~49% 30% 속도</li>
 * <li>2시간이 지났을 때 성장도가 90 이상이면 스탯이 더 줄지 않는다</li>
 * </ul>
 * 줄어드는 양은 매번 20~25 사이 무작위라 앞날은 가장 많이 주는 25%로 계산한다(그래야 '이만큼 주면 다음 감소 뒤에도 100%'가 늘 맞다).
 * 어항 창의 반려어 칸마다(창 칸 번호로 구분) 따로 기억하고, 두 번 읽는 사이에 한 번 줄었으면 그 사이를 감소 시각 후보로 좁혀 간다.
 * 서버로는 아무것도 보내지 않는다.
 */
public final class PetFish {

    private PetFish() {}

    public static final long GROW_MS = 2 * 3600_000L;
    public static final long TICK_MS = 10 * 60_000L;
    /** 한 번에 주는 양: 20~25% 중 큰 값(늦게 알리는 것보다 일찍 알리는 게 낫다) */
    static final double DROP = 25;
    static final double DROP_MIN = 20;
    /** 성장 속도가 느려지는 평균(이 아래면 70%) */
    static final double SLOW_AVG = 80;
    /** 다음 감소(최대 25%) 뒤에도 평균 80% 이상이려면 감소 전에 세 스탯 합이 이만큼 */
    static final double KEEP_SUM = SLOW_AVG * 3 + DROP;
    /** 밥 알림은 성장이 느려질 감소보다 이만큼 먼저(그 전에 주면 성장 100% 가 끊기지 않는다) */
    static final long LEAD_MS = 3 * 60_000L;

    public static final class Fish {
        public String name = "";
        /** 어항 창 칸 번호(같은 이름 반려어를 가른다). -1 = 모름(예전 기록·시험) */
        public int slot = -1;
        /** 어항에 넣은 시각(ms). 0 = 모름 */
        public long insertedAt;
        /** 넣은 시각을 성장도로 어림했나(남은 성장 시간·성장도 0 을 보면 정확한 값으로 바꾼다) */
        public boolean insertGuessed;
        /** 스탯을 마지막으로 읽은 시각 */
        public long statsAt;
        public double hunger = -1, mood = -1, vigor = -1;
        public double growth = -1;
        /** 스탯이 줄어든 한 순간이 들어 있는 구간(감소 주기마다 되풀이). 0 = 아직 모름 */
        public long dropLo, dropHi;
        /** 이번에 이미 알린 것(스탯이 다시 오르면 지운다) */
        public boolean warnedFeed, warnedDanger, notifiedGrown;
        /** 이 반려어를 읽은 어항 창의 제목(서버 그림 글자 포함 그대로). 같은 창에 반려어가 없으면 꺼낸 것 */
        public String screen = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Fish> store;
    private static String lastLore = "";

    private static final Pattern STAT = Pattern.compile("(허기|기분|활력)[^0-9%]{0,12}?(\\d{1,3}(?:\\.\\d+)?)\\s*%");
    private static final Pattern GROWTH = Pattern.compile("성장도[^0-9]{0,12}?(\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern H_M = Pattern.compile("(\\d+)\\s*시간\\s*(\\d+)\\s*분");
    private static final Pattern M_S = Pattern.compile("(\\d+)\\s*분\\s*(\\d+)\\s*초");
    private static final Pattern MIN = Pattern.compile("(\\d+)\\s*분");

    // ---------------------------------------------------------------- 어항 창 읽기

    /**
     * 10틱마다: 연 창에 허기·기분·활력이 적힌 칸을 모두 읽는다(어항에 여러 마리). 인벤토리의 반려어 아이템은 보지 않는다 —
     * 같은 종 아기 물고기를 들고만 있어도 어항 기록을 지웠다. 꺼냈는지는 어항 창을 다시 열 때 그 칸이 비었는지로 본다
     */
    public static void scan(MinecraftClient client) {
        if (!DdingMate.config().expert.petFish || client.player == null) return;
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        String rawTitle = screen.getTitle().getString();
        String title = ItemKeys.stripGlyphs(rawTitle);
        long now = kr.ddingmate.util.Clock.now();
        Set<Integer> found = new HashSet<>();
        boolean anyItem = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            anyItem = true;
            ItemStack s = slot.getStack();
            List<String> lore = lore(s);
            String joined = String.join(" | ", lore);
            // "허기 80%"처럼 숫자(%)가 붙은 스탯이 둘 이상이어야 반려어 칸. 낱말만 보면 "기분 좋은 활력이 차오릅니다" 같은
            // 다른 아이템 설명(신나는 풍선 뭉치)을 반려어로 착각했다(2026-10-21 사용자)
            if (statKinds(joined) < 2) continue;
            String name = ItemKeys.stripGlyphs(s.getName().getString()).trim();
            String line = slot.id + " " + name + " | " + joined;
            if (!line.equals(lastLore)) {
                lastLore = line;
                DdingMate.LOG.info("어항 칸 읽음: {}", line);
            }
            Fish f = read(name, lore, now, slot.id);
            if (f != null && !rawTitle.equals(f.screen)) {
                f.screen = rawTitle;
                save();
            }
            found.add(slot.id);
        }
        afterScan(rawTitle, title, found, anyItem, now);
    }

    /**
     * 어항 창인데 기억한 칸에 반려어가 없으면 꺼냈거나 방생한 것(창이 다 그려진 뒤에 판단하도록 1초 지나서).
     * 어항 창은 제목이 서버 그림 글자라 "어항" 글자로는 못 알아볼 수 있어서, 반려어를 읽었던 창과 제목이 같은지로도 본다
     * (2026-10-20 사용자 문의: "어항에서 빼도 일정에서 안 빠져요")
     */
    static void afterScan(String rawTitle, String title, Set<Integer> found, boolean anyItem, long now) {
        boolean titled = title.contains("어항") || title.contains("수족관");
        List<Fish> gone = new ArrayList<>();
        for (Fish f : fishes()) {
            boolean here = f.screen != null && !f.screen.isEmpty() ? f.screen.equals(rawTitle) : titled;
            if (here && !found.contains(f.slot)) gone.add(f);
        }
        if (gone.isEmpty() || !anyItem) {
            emptySince = 0;
            return;
        }
        if (emptySince == 0) emptySince = now;
        else if (now - emptySince > 1000) {
            for (Fish f : gone) DdingMate.LOG.info("어항에 반려어가 없어 기록을 지움: {} ({}번 칸)", f.name, f.slot);
            store().values().removeIf(gone::contains);
            save();
            emptySince = 0;
        }
    }

    private static long emptySince;

    /** 설명에 "허기 N%"·"기분 N%"·"활력 N%" 중 몇 가지가 있나 */
    static int statKinds(String joined) {
        Set<String> kinds = new HashSet<>();
        Matcher m = STAT.matcher(joined);
        while (m.find()) kinds.add(m.group(1));
        return kinds.size();
    }

    private static List<String> lore(ItemStack s) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return out;
        for (Text t : lore.lines()) {
            String l = ItemKeys.stripGlyphs(t.getString()).replaceAll("\\s+", " ").trim();
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    /** 칸 설명을 반영한다(시험에서도 부른다). 반영한 반려어(아니면 null) */
    static Fish read(String name, List<String> lore, long now, int slot) {
        if (statKinds(String.join(" | ", lore)) < 2) return null;
        String acct = key();
        if (acct == null) return null;
        String k = acct + "#" + slot;
        Fish f = store().get(k);
        boolean newFish = f == null || !sameFish(f.name, name);
        if (newFish) {
            f = new Fish();
            f.name = name;
            f.slot = slot;
            store().put(k, f);
        }
        double h = -1, m = -1, v = -1, g = -1;
        long growLeft = -1;
        for (String l : lore) {
            Matcher st = STAT.matcher(l);
            while (st.find()) {
                double val = Double.parseDouble(st.group(2));
                switch (st.group(1)) {
                    case "허기" -> h = val;
                    case "기분" -> m = val;
                    default -> v = val;
                }
            }
            Matcher gr = GROWTH.matcher(l);
            if (gr.find()) g = Double.parseDouble(gr.group(1));
            if (l.contains("성장") && (l.contains("남은") || l.contains("남음"))) growLeft = duration(l);
        }
        if (h < 0 && m < 0 && v < 0) return null;
        f.name = name; // "아기 먹"이 자라 "먹"이 되면 이름도 따라간다
        if (!newFish && f.statsAt > 0 && now > f.statsAt) learnPhase(f, h, m, v, now);
        // 밥을 줘서 스탯이 올랐으면 알림을 다시 할 수 있게: 지난번에 읽은 값보다 올랐거나 지금 평균이 충분할 때.
        // (예전엔 25%씩 깎은 예상값과 비교해서, 실제로 20%씩 줄면 어항을 열기만 해도 '밥을 줬다'고 보고 알림이 다시 울렸다)
        double read = avgOf(h, m, v);
        if (f.statsAt == 0 || read > avgOf(f.hunger, f.mood, f.vigor) + 1 || read >= SLOW_AVG) {
            f.warnedFeed = false;
            f.warnedDanger = false;
        }
        f.hunger = h;
        f.mood = m;
        f.vigor = v;
        f.statsAt = now;
        if (g >= 0) f.growth = g;
        // 넣은 시각: 창에 남은 성장 시간이 있으면 거기서, 막 넣은 것(성장도 1 이하)이면 지금,
        // 그 밖엔 성장도로 어림(평균 80% 이상 = 100% 속도로 자랐다고 보고). 실제 툴팁엔 남은 성장 시간 줄이 없다(2026-10-31)
        if (growLeft >= 0) {
            f.insertedAt = now + growLeft - GROW_MS;
            f.insertGuessed = false;
        } else if (g >= 0 && g <= 1 && (f.insertedAt == 0 || f.insertGuessed)) {
            f.insertedAt = now;
            f.insertGuessed = false;
        } else if (f.insertedAt == 0 && g > 1 && g < 100 && read >= SLOW_AVG) {
            f.insertedAt = now - (long) (g / 100.0 * GROW_MS);
            f.insertGuessed = true;
        }
        save();
        return f;
    }

    /**
     * 지난번에 읽은 뒤 밥 없이 한 번 줄었으면(합이 15~30 줄고 오른 스탯 없음) 그 사이에 감소가 있었다.
     * 감소 주기마다 되풀이되니 지난 구간과 주기만큼 옮겨 겹치는 곳만 남긴다(어항을 자주 열수록 감소 시각이 정확해진다)
     */
    static void learnPhase(Fish f, double h, double m, double v, long now) {
        double[] old = {f.hunger, f.mood, f.vigor}, cur = {h, m, v};
        double drop = 0;
        for (int i = 0; i < 3; i++) {
            if (old[i] < 0 || cur[i] < 0 || cur[i] > old[i] + 0.5) return;
            drop += old[i] - cur[i];
        }
        long t = tickMs();
        if (drop < DROP_MIN - 5 || drop > DROP + 5 || now - f.statsAt > 2 * t) return;
        long a = f.statsAt, b = now;
        if (f.dropLo == 0) {
            f.dropLo = a;
            f.dropHi = b;
            return;
        }
        long shift = Math.round((double) (f.dropLo - a) / t) * t;
        long lo = Math.max(f.dropLo, a + shift), hi = Math.min(f.dropHi, b + shift);
        if (lo > hi) { // 맞지 않음(스킬이 바뀜 등): 새로 본 구간부터 다시
            lo = a;
            hi = b;
        }
        f.dropLo = lo;
        f.dropHi = hi;
    }

    /** "1시간 20분", "35분 10초", "12분" → ms. 못 읽으면 -1 */
    static long duration(String l) {
        Matcher a = H_M.matcher(l);
        if (a.find()) return (Long.parseLong(a.group(1)) * 60 + Long.parseLong(a.group(2))) * 60_000;
        Matcher b = M_S.matcher(l);
        if (b.find()) return (Long.parseLong(b.group(1)) * 60 + Long.parseLong(b.group(2))) * 1000;
        Matcher c = MIN.matcher(l);
        if (c.find()) return Long.parseLong(c.group(1)) * 60_000;
        return -1;
    }

    /** "아기 먹"이 자라 "먹"이 돼도 같은 물고기로 본다 */
    private static boolean sameFish(String a, String b) {
        return a.replace("아기", "").trim().equals(b.replace("아기", "").trim());
    }

    // ---------------------------------------------------------------- 내다보기

    /** 앞으로의 모습 */
    public record Forecast(double avg, double min, double growth, boolean alive, long nextDropAt, long slowAt, long dangerAt,
                           boolean grownSafe) {}

    /**
     * 감소 시각의 기준: 감소를 본 구간이 있으면 그 가장 이른 때(알림이 늦지 않게), 없으면 넣은 시각, 그것도 모르면 읽은 때.
     * 구간이 주기보다 넓으면 아직 쓸모없어 넣은 시각으로
     */
    static long anchor(Fish f) {
        if (f.dropLo > 0 && f.dropHi - f.dropLo < tickMs()) return f.dropLo;
        return f.insertedAt > 0 ? f.insertedAt : f.statsAt;
    }

    /** t 뒤 첫 감소 시각 */
    static long nextDropAfter(Fish f, long t) {
        long anchor = anchor(f), tick = tickMs();
        if (t < anchor) return anchor;
        return anchor + ((t - anchor) / tick + 1) * tick;
    }

    /**
     * 마지막으로 읽은 스탯에서 at 시각까지 규칙대로 굴려 본다(감소량은 가장 많이 주는 25%).
     * slowAt: 평균이 80% 아래로 내려가는 때, dangerAt: 다음 감소에 0% 가 될 수 있는 때
     */
    public static Forecast forecast(Fish f, long at) {
        double[] s = stats(f);
        double g = Math.max(0, f.growth);
        long growEnd = f.insertedAt > 0 ? f.insertedAt + GROW_MS : Long.MAX_VALUE;
        long t = f.statsAt;
        long nextDrop = nextDropAfter(f, t);
        long slowAt = avg(s) < SLOW_AVG ? t : 0, dangerAt = min(s) <= DROP ? t : 0;
        boolean alive = min(s) > 0;
        long horizon = Math.max(at, t) + 6 * 3600_000L;
        long firstNext = 0;
        while (t < horizon && alive) {
            long step = Math.min(nextDrop, Math.min(growEnd > t ? growEnd : Long.MAX_VALUE, horizon));
            // 그동안 자란 양(평균 속도로)
            if (t < growEnd) g = Math.min(100, g + speed(avg(s)) * 100.0 * (Math.min(step, growEnd) - t) / GROW_MS);
            t = step;
            boolean stopped = growEnd != Long.MAX_VALUE && t >= growEnd && g >= 90;
            if (t == nextDrop && !stopped) {
                if (firstNext == 0 && t > at) firstNext = t;
                dropHighest(s);
                if (min(s) <= 0) alive = false;
                if (slowAt == 0 && avg(s) < SLOW_AVG) slowAt = t;
                if (dangerAt == 0 && min(s) <= DROP) dangerAt = t;
                nextDrop += tickMs();
            } else if (t == nextDrop) {
                nextDrop += tickMs();
            }
            if (t >= at && slowAt != 0 && dangerAt != 0 && firstNext != 0) break;
        }
        Snapshot now = snapshot(f, at);
        boolean grownSafe = f.insertedAt > 0 && at >= f.insertedAt + GROW_MS && now.growth >= 90;
        return new Forecast(now.avg(), now.min(), now.growth, now.alive, firstNext, slowAt, dangerAt, grownSafe);
    }

    /** at 시각의 예상 스탯(허기·기분·활력, 25% 로 깎은 값)·성장도 */
    record Snapshot(double[] stats, double growth, boolean alive) {
        double avg() {
            return PetFish.avg(stats);
        }

        double min() {
            return PetFish.min(stats);
        }
    }

    static Snapshot snapshot(Fish f, long at) {
        double[] s = stats(f);
        double g = Math.max(0, f.growth);
        long growEnd = f.insertedAt > 0 ? f.insertedAt + GROW_MS : Long.MAX_VALUE;
        long t = f.statsAt;
        long nextDrop = nextDropAfter(f, t);
        boolean alive = min(s) > 0;
        while (t < at && alive) {
            long step = Math.min(nextDrop, Math.min(growEnd > t ? growEnd : Long.MAX_VALUE, at));
            if (t < growEnd) g = Math.min(100, g + speed(avg(s)) * 100.0 * (Math.min(step, growEnd) - t) / GROW_MS);
            t = step;
            boolean stopped = growEnd != Long.MAX_VALUE && t >= growEnd && g >= 90;
            if (t == nextDrop) {
                if (!stopped) {
                    dropHighest(s);
                    if (min(s) <= 0) alive = false;
                }
                nextDrop += tickMs();
            }
        }
        return new Snapshot(s, g, alive);
    }

    private static double[] stats(Fish f) {
        double[] s = {f.hunger, f.mood, f.vigor};
        for (int i = 0; i < 3; i++) if (s[i] < 0) s[i] = 100;
        return s;
    }

    private static void dropHighest(double[] s) {
        int hi = 0;
        for (int i = 1; i < 3; i++) if (s[i] > s[hi]) hi = i;
        s[hi] = Math.max(0, s[hi] - DROP);
    }

    /** 위키 성장 속도표 */
    static double speed(double avg) {
        if (avg >= 80) return 1.0;
        if (avg >= 50) return 0.7;
        if (avg > 0) return 0.3;
        return 0;
    }

    private static double avg(double[] s) {
        return (s[0] + s[1] + s[2]) / 3;
    }

    private static double min(double[] s) {
        return Math.min(s[0], Math.min(s[1], s[2]));
    }

    private static double avgOf(double h, double m, double v) {
        double sum = 0;
        int n = 0;
        for (double x : new double[]{h, m, v}) if (x >= 0) {
            sum += x;
            n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    // ---------------------------------------------------------------- 알림

    /** 1초마다: 반려어마다 보고, 같은 때 여러 마리면 한 번에 묶어서 알린다 */
    public static void tick(long now) {
        if (!DdingMate.config().expert.petFish) return;
        List<Fish> lost = new ArrayList<>(), grown = new ArrayList<>(), timeUp = new ArrayList<>(), danger = new ArrayList<>(), feed = new ArrayList<>();
        int[] feedItems = new int[3];
        for (Fish f : fishes()) {
            if (f.statsAt == 0) continue;
            Forecast fc = forecast(f, now);
            if (!fc.alive() || fc.grownSafe()) {
                if (!fc.alive() && !fc.grownSafe() && !f.warnedDanger) {
                    // 접속하지 않은 사이 예상상 스탯이 0% 가 됐다: 알림 없이 HUD 만 바뀌지 않게 한 번 알린다
                    f.warnedDanger = true;
                    f.warnedFeed = true;
                    lost.add(f);
                }
                if (fc.grownSafe() && !f.notifiedGrown) {
                    f.notifiedGrown = true;
                    grown.add(f);
                }
                continue;
            }
            if (f.insertedAt > 0 && now >= f.insertedAt + GROW_MS && !f.notifiedGrown) {
                f.notifiedGrown = true;
                timeUp.add(f);
            }
            if (!f.warnedDanger && fc.dangerAt() != 0 && now >= fc.dangerAt()) {
                f.warnedDanger = true;
                f.warnedFeed = true;
                danger.add(f);
                continue;
            }
            long feedAt = feedAt(f, fc);
            if (!f.warnedFeed && feedAt != 0 && now >= feedAt) {
                f.warnedFeed = true;
                feed.add(f);
                int[] it = keepItems(snapshot(f, now).stats());
                for (int i = 0; i < 3; i++) feedItems[i] += it[i];
            }
        }
        if (lost.isEmpty() && grown.isEmpty() && timeUp.isEmpty() && danger.isEmpty() && feed.isEmpty()) return;
        save();
        if (!lost.isEmpty()) Notifier.petFish(who(lost) + " 어항을 확인해 주세요", "예상으로는 스탯이 0% 가 됐을 수 있어요 · 어항을 열면 실제 값으로 맞춰요");
        if (!grown.isEmpty()) Notifier.petFish(who(grown) + " 다 컸어요", "성장도 90 이상이라 이제 스탯이 줄지 않아요 · 포스에게 방생할 수 있어요");
        if (!timeUp.isEmpty()) Notifier.petFish(who(timeUp) + " 성장 시간 끝", "성장도 90 아래면 스탯이 계속 줄어요 · 밥을 계속 챙겨 주세요");
        if (!danger.isEmpty()) {
            Notifier.petFish(who(danger) + " 폐사 위험!", "다음 감소에 스탯이 0% 가 될 수 있어요 · 어항에서 빵·덩굴·뼈 블록으로 채워 주세요");
            return;
        }
        if (!feed.isEmpty()) {
            String items = itemsText(feedItems);
            Notifier.petFish(who(feed) + " 밥 줄 때예요", items.isEmpty()
                    ? "곧 스탯이 줄어 평균이 80% 아래가 될 수 있어요"
                    : "다음 감소 전에 " + (feed.size() > 1 ? "모두 합쳐 " : "") + items + " 주면 성장 100% 가 이어져요(가장 많이 주는 25% 기준)");
        }
    }

    /** 알림 제목 앞: "반려어 아기 클라이" · "반려어 5마리" */
    private static String who(List<Fish> list) {
        return list.size() == 1 ? "반려어 " + displayName(list.get(0)) : "반려어 " + list.size() + "마리";
    }

    // ---------------------------------------------------------------- 밥 계산

    /** 회복 아이템 하나가 채우는 양(2026-10-31 위키·사용자 확인): 빵 = 허기 1%, 덩굴 = 기분 2%, 뼈 블록 = 활력 2% */
    static final double BREAD = 1, VINE = 2, BONE = 2;

    /** 마지막으로 읽은 값을 100% 로 채우는 데 드는 아이템: "빵 25개 · 뼈 블록 3개"(다 차 있으면 빈 글) */
    public static String fillText(Fish f) {
        return itemsText(new int[]{need(f.hunger, BREAD), need(f.mood, VINE), need(f.vigor, BONE)});
    }

    /**
     * 다음 감소(최대 25%) 뒤에도 평균 80%(성장 100%)를 지키는 가장 적은 밥: 세 스탯 합을 265% 까지.
     * 2% 짜리(덩굴·뼈 블록)를 먼저 쓰고 모자라면 빵. [빵, 덩굴, 뼈 블록]
     */
    static int[] keepItems(double[] s) {
        double deficit = KEEP_SUM - (s[0] + s[1] + s[2]);
        int[] out = new int[3];
        if (deficit <= 0) return out;
        int[] order = {1, 2, 0};
        double[] per = {BREAD, VINE, BONE};
        for (int i : order) {
            if (deficit <= 0) break;
            double room = Math.max(0, 100 - s[i]);
            double add = Math.min(room, deficit);
            if (add <= 0) continue;
            out[i] = (int) Math.ceil(add / per[i] - 1e-9);
            deficit -= out[i] * per[i];
        }
        return out;
    }

    /** 마지막으로 읽은 값에서 다음 감소 뒤에도 성장 100% 를 지키는 밥(이미 넉넉하면 빈 글) */
    public static String keepText(Fish f) {
        return itemsText(keepItems(stats(f)));
    }

    static String itemsText(int[] it) {
        List<String> parts = new ArrayList<>();
        if (it[0] > 0) parts.add("빵 " + it[0] + "개");
        if (it[1] > 0) parts.add("덩굴 " + it[1] + "개");
        if (it[2] > 0) parts.add("뼈 블록 " + it[2] + "개");
        return String.join(" · ", parts);
    }

    private static int need(double stat, double per) {
        return stat < 0 ? 0 : (int) Math.ceil(Math.max(0, 100 - stat) / per - 1e-9);
    }

    // ---------------------------------------------------------------- HUD·탭에 보일 글

    /** 이 계정의 어항 반려어들(칸 번호 순) */
    public static List<Fish> fishes() {
        String k = key();
        if (k == null) return List.of();
        List<Fish> out = new ArrayList<>();
        for (Map.Entry<String, Fish> e : store().entrySet()) if (e.getKey().startsWith(k + "#") && e.getValue() != null) out.add(e.getValue());
        out.sort(Comparator.comparingInt(f -> f.slot));
        return out;
    }

    /** HUD 에 보일 반려어: 가장 급한 것(위험 → 밥 → 가장 빨리 밥 줄 것). 없으면 null */
    public static Fish current() {
        List<Fish> all = fishes();
        if (all.isEmpty()) return null;
        long now = kr.ddingmate.util.Clock.now();
        Fish best = null;
        long bestKey = Long.MAX_VALUE;
        for (Fish f : all) {
            int lv = level(f, now);
            long when = f.statsAt == 0 ? Long.MAX_VALUE / 4 : nextEvent(f, now);
            long key = (lv >= 3 ? 0 : lv == 2 ? 1 : lv == 0 ? 2 : 3) * (Long.MAX_VALUE / 4) + Math.min(when, Long.MAX_VALUE / 4 - 1);
            if (best == null || key < bestKey) {
                best = f;
                bestKey = key;
            }
        }
        return best;
    }

    private static long nextEvent(Fish f, long now) {
        Forecast fc = forecast(f, now);
        long feed = feedAt(f, fc);
        return feed == 0 ? Long.MAX_VALUE / 8 : Math.max(0, feed - now);
    }

    /** 같은 이름이 여럿이면 칸 순서 번호를 붙인다: "아기 클라이 2" */
    public static String displayName(Fish f) {
        List<Fish> same = new ArrayList<>();
        for (Fish o : fishes()) if (o.name.equals(f.name)) same.add(o);
        if (same.size() < 2) return f.name;
        return f.name + " " + (same.indexOf(f) + 1);
    }

    /** HUD 줄 이름: "반려어 아기 클라이 2 외 4마리" */
    public static String label(Fish f) {
        int others = fishes().size() - 1;
        return "반려어 " + displayName(f) + (others > 0 ? " 외 " + others + "마리" : "");
    }

    /** HUD 오른쪽: "밥 줄 때!" · "밥 12분 뒤" · "다 컸어요" */
    public static String status(Fish f, long now) {
        if (f.statsAt == 0) return "어항 열면 표시";
        Forecast fc = forecast(f, now);
        if (!fc.alive()) return "어항 확인!";
        if (fc.grownSafe()) return "다 컸어요";
        if (fc.dangerAt() != 0 && now >= fc.dangerAt()) return "폐사 위험!";
        long feedAt = feedAt(f, fc);
        if (feedAt != 0 && now >= feedAt) return "밥 줄 때!";
        if (feedAt != 0) return hm(feedAt - now) + " 뒤 밥 주기";
        return "괜찮아요";
    }

    /** 위험할수록 빨갛게 */
    public static int level(Fish f, long now) {
        if (f.statsAt == 0) return 0;
        Forecast fc = forecast(f, now);
        if (fc.grownSafe()) return 1;
        if (!fc.alive() || (fc.dangerAt() != 0 && now >= fc.dangerAt())) return 3;
        long feedAt = feedAt(f, fc);
        if (feedAt != 0 && now >= feedAt) return 2;
        return 0;
    }

    /**
     * 밥 줄 때: 설정한 주기가 있으면 어항을 마지막으로 연 때 + 주기, 없으면(자동) 평균이 80% 아래로 떨어질 감소보다 3분 먼저
     * (그 전에 주면 성장 100% 가 끊기지 않는다. 이미 80% 아래면 지금)
     */
    static long feedAt(Fish f, Forecast fc) {
        int min = DdingMate.config().expert.petFishFeedMin;
        if (min > 0) return f.statsAt + min * 60_000L;
        if (fc.slowAt() == 0) return 0;
        return fc.slowAt() <= f.statsAt ? f.statsAt : Math.max(f.statsAt, fc.slowAt() - LEAD_MS);
    }

    /** 탭에 보일 자세한 한 줄 */
    public static String detail(Fish f, long now) {
        if (f.statsAt == 0) return "어항 창을 열면 허기·기분·활력을 읽어요";
        Snapshot s = snapshot(f, now);
        StringBuilder sb = new StringBuilder();
        sb.append("지금 예상: 평균 ").append(Math.round(s.avg())).append("% · 가장 낮은 스탯 ").append(Math.round(s.min())).append('%');
        if (f.growth >= 0) sb.append(" · 성장도 ").append(Math.round(s.growth()));
        if (f.insertedAt > 0) {
            long end = f.insertedAt + GROW_MS - now;
            sb.append(end > 0 ? " · 성장 " + hm(end) + " 남음" : " · 성장 시간 끝");
        }
        sb.append(" · 다음 감소 ").append(clock(nextDropAfter(f, now)));
        if (f.dropLo == 0 || f.dropHi - f.dropLo >= tickMs()) sb.append("쯤");
        else if (f.dropHi - f.dropLo > 60_000) sb.append("(±").append((f.dropHi - f.dropLo + 59_999) / 60_000).append("분)");
        String fill = fillText(f);
        if (!fill.isEmpty()) sb.append(" · 어항을 연 때 기준 다 채우려면 ").append(fill);
        return sb.toString();
    }

    /** 탭 안내: 어항을 연 때 값에서 다음 감소 뒤에도 성장 100% 를 지키려면 */
    public static String keepNote(Fish f) {
        if (f.statsAt == 0) return "";
        String keep = keepText(f);
        return keep.isEmpty() ? "다음 감소(최대 25%) 뒤에도 평균 80% 이상이에요 · 지금은 밥을 안 줘도 성장 100%"
                : "다음 감소 전에 " + keep + " 주면 성장 100% 가 이어져요(어항을 연 때 값 · 가장 많이 주는 25% 기준)";
    }

    private static String clock(long at) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(at).atZone(kr.ddingmate.schedule.ScheduleEngine.KST);
        return String.format("%d:%02d", z.getHour(), z.getMinute());
    }

    /** 스탯이 줄어드는 주기(ms): 기본 10분, "수질 유지 기술"을 배웠으면 11·14·17·20분 */
    public static long tickMs() {
        int m = DdingMate.config() == null ? 10 : DdingMate.config().expert.petFishTickMin;
        return (m >= 10 && m <= 30 ? m : 10) * 60_000L;
    }

    /** 해양 스킬 "[세레니티] 수질 유지 기술 (+N)": 설명 "반려 물고기의 스탯 하락 주기가 20분으로 증가합니다." */
    private static final Pattern SKILL_TICK = Pattern.compile("하락\\s*주기가\\s*(\\d+)\\s*분");
    private static final Pattern SKILL_PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시|다음\\s*레벨");
    private static boolean skillConfirmed;

    /** /전문가 스킬 창의 "수질 유지 기술" 칸(지금 레벨 설명만, 다음 레벨 미리보기는 건너뜀). 안 배웠으면 10분 */
    public static void readSkill(String name, List<String> lore) {
        int min = 10;
        for (String line : lore) {
            if (SKILL_PREVIEW.matcher(line).find()) break;
            Matcher m = SKILL_TICK.matcher(line);
            if (m.find()) {
                min = Integer.parseInt(m.group(1));
                break;
            }
        }
        if (name.matches(".*\\(\\s*\\+?\\s*0\\s*\\).*") || name.contains("잠김")) min = 10;
        min = Math.max(10, Math.min(30, min));
        int old = DdingMate.config().expert.petFishTickMin;
        boolean changed = old != min;
        if (changed) {
            DdingMate.config().expert.petFishTickMin = min;
            DdingMate.config().save();
            DdingMate.LOG.info("반려어 스탯 감소 주기(스킬): {}분 → {}분", old, min);
        }
        if (!skillConfirmed || changed) {
            skillConfirmed = true;
            kr.ddingmate.skill.SkillReport.add("반려어", "해양", "수질 유지 기술",
                    min > 10 ? "수질 유지 기술(" + min + "분마다)" : "수질 유지 기술 안 배움", changed);
        }
    }

    public static String hm(long ms) {
        long min = Math.max(0, (ms + 59_999) / 60_000);
        return min >= 60 ? (min / 60) + "시간 " + (min % 60) + "분" : min + "분";
    }

    /** 이 반려어 기록을 지운다(꺼냈거나 방생했을 때) */
    public static void forget(Fish f) {
        if (f != null && store().values().removeIf(x -> x == f)) save();
    }

    /** 이 계정의 반려어 기록을 모두 지운다 */
    public static void forget() {
        String k = key();
        if (k != null && store().keySet().removeIf(x -> x.startsWith(k + "#"))) save();
    }

    // ---------------------------------------------------------------- 저장

    private static String key() {
        return kr.ddingmate.craft.CraftTimers.accountKey();
    }

    private static Map<String, Fish> store() {
        if (store == null) {
            store = new LinkedHashMap<>();
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Map<String, Fish> read = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, Fish>>() {}.getType());
                    if (read != null) {
                        // 예전 판은 계정마다 한 마리("계정" 키): "계정#-1" 로 옮긴다(어항을 열면 칸 번호 기록으로 바뀐다)
                        for (Map.Entry<String, Fish> e : read.entrySet()) store.put(e.getKey().contains("#") ? e.getKey() : e.getKey() + "#-1", e.getValue());
                    }
                    // 예전 판이 반려어가 아닌 칸(신나는 풍선 뭉치)을 스탯 없이 넣어 둔 것을 지운다
                    if (store.values().removeIf(f -> f == null || f.statsAt == 0)) DdingMate.LOG.info("스탯 없는 반려어 기록을 지움");
                } catch (Exception e) {
                    DdingMate.LOG.warn("반려어 기록을 읽지 못했어요", e);
                }
            }
        }
        return store;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-petfish.json");
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(store(), w);
        } catch (Exception e) {
            DdingMate.LOG.warn("반려어 기록을 저장하지 못했어요", e);
        }
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugRead(String name, List<String> lore, long now) {
        read(name, lore, now, -1);
    }

    /** 시험용: 어항 창 칸 번호까지(여러 마리) */
    public static Fish debugReadSlot(String name, List<String> lore, long now, int slot) {
        return read(name, lore, now, slot);
    }

    /** 시험용: 지금 반려어를 모두 이 제목의 창에서 읽은 것으로 */
    public static void debugSetScreen(String rawTitle) {
        for (Fish f : fishes()) f.screen = rawTitle;
    }

    /** 시험용: 창을 본 것처럼(제목, 기억한 반려어 칸이 그대로 있었나, 칸이 하나라도 있었나) */
    public static void debugAfterScan(String rawTitle, boolean found, boolean anyItem, long now) {
        Set<Integer> slots = new HashSet<>();
        if (found) for (Fish f : fishes()) slots.add(f.slot);
        afterScan(rawTitle, ItemKeys.stripGlyphs(rawTitle), slots, anyItem, now);
    }

    /** 시험용: 창을 본 것처럼(그 창에 반려어가 있던 칸 번호들) */
    public static void debugAfterScanSlots(String rawTitle, Set<Integer> slots, long now) {
        afterScan(rawTitle, ItemKeys.stripGlyphs(rawTitle), slots, true, now);
    }

    public static void debugReset() {
        store().clear();
        save();
        lastLore = "";
        emptySince = 0;
    }
}
