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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 반려어(수족관) 밥 타이머. 위키 수족관 문서(2026-10-18)대로 계산한다.
 * <ul>
 * <li>허기·기분·활력은 10분마다(해양 스킬 "수질 유지 기술" Lv1~4: 11·14·17·20분) 한 가지가 20~25%씩 줄고, 위키는 가장 높은 스탯부터라고 한다
 * (플레이어 말로는 무작위 — 평균은 어느 쪽이든 같아서 밥 알림은 같다). 하나라도 0% 면 폐사</li>
 * <li>회복: 빵 허기 1%, 덩굴 기분 2%, 뼈 블록 활력 2%(위키·2026-10-31 확인)</li>
 * <li>어항에 넣은 때부터 2시간 동안만 자라고(성장도 최대 100), 세 스탯 평균이 80% 이상이면 100%, 50~79% 70%, 1~49% 30% 속도</li>
 * <li>2시간이 지났을 때 성장도가 90 이상이면 스탯이 더 줄지 않는다</li>
 * </ul>
 * 어항 창의 반려어 칸 설명(허기·기분·활력·성장도)을 읽어 맞추고, 그 뒤는 위 규칙으로 앞을 내다본다.
 * 줄어드는 양은 안전하게 25%(가장 많이 주는 경우)로 계산한다. 읽은 칸은 로그에 "어항 칸 읽음:"으로 남긴다.
 * 서버로는 아무것도 보내지 않는다.
 */
public final class PetFish {

    private PetFish() {}

    public static final long GROW_MS = 2 * 3600_000L;
    public static final long TICK_MS = 10 * 60_000L;
    /** 한 번에 주는 양: 위키 20~25% 중 큰 값(늦게 알리는 것보다 일찍 알리는 게 낫다) */
    static final double DROP = 25;
    /** 성장 속도가 느려지는 평균(이 아래면 70%) */
    static final double SLOW_AVG = 80;

    public static final class Fish {
        public String name = "";
        /** 어항에 넣은 시각(ms). 0 = 모름 */
        public long insertedAt;
        /** 스탯을 마지막으로 읽은 시각 */
        public long statsAt;
        public double hunger = -1, mood = -1, vigor = -1;
        public double growth = -1;
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

    /** 10틱마다: 연 창에 허기·기분·활력이 적힌 칸이 있으면 그 반려어를 읽는다 */
    public static void scan(MinecraftClient client) {
        if (!DdingMate.config().expert.petFish || client.player == null) return;
        // 꺼낸 반려어가 내 인벤토리에 있으면(같은 이름에 허기·기분·활력 %) 어항에서 뺀 것
        Fish cur = current();
        if (cur != null) {
            PlayerInventory inv = client.player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty() || statKinds(String.join(" | ", lore(s))) < 2) continue;
                if (sameFish(cur.name, ItemKeys.stripGlyphs(s.getName().getString()).trim())) {
                    DdingMate.LOG.info("반려어를 인벤토리로 꺼내 기록을 지움: {}", cur.name);
                    forget();
                    return;
                }
            }
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        String rawTitle = screen.getTitle().getString();
        String title = ItemKeys.stripGlyphs(rawTitle);
        boolean found = false, anyItem = false;
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
            String line = name + " | " + joined;
            if (!line.equals(lastLore)) {
                lastLore = line;
                DdingMate.LOG.info("어항 칸 읽음: {}", line);
            }
            read(name, lore, kr.ddingmate.util.Clock.now());
            Fish f = current();
            if (f != null && !rawTitle.equals(f.screen)) {
                f.screen = rawTitle;
                save();
            }
            found = true;
            break;
        }
        afterScan(rawTitle, title, found, anyItem, kr.ddingmate.util.Clock.now());
    }

    /**
     * 어항 창인데 반려어가 없으면 꺼냈거나 방생한 것(창이 다 그려진 뒤에 판단하도록 1초 지나서).
     * 어항 창은 제목이 서버 그림 글자라 "어항" 글자로는 못 알아볼 수 있어서, 반려어를 읽었던 창과 제목이 같은지로도 본다
     * (2026-10-20 사용자 문의: "어항에서 빼도 일정에서 안 빠져요")
     */
    static void afterScan(String rawTitle, String title, boolean found, boolean anyItem, long now) {
        Fish cur = current();
        boolean aquarium = title.contains("어항") || title.contains("수족관")
                || (cur != null && cur.screen != null && !cur.screen.isEmpty() && cur.screen.equals(rawTitle));
        if (aquarium && !found && anyItem) {
            if (emptySince == 0) emptySince = now;
            else if (now - emptySince > 1000 && current() != null) {
                DdingMate.LOG.info("어항에 반려어가 없어 기록을 지움: {}", title.trim());
                forget();
            }
        } else emptySince = 0;
    }

    private static long emptySince;

    /** 설명에 "허기 N%"·"기분 N%"·"활력 N%" 중 몇 가지가 있나 */
    static int statKinds(String joined) {
        java.util.Set<String> kinds = new java.util.HashSet<>();
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

    /** 칸 설명을 반영한다(시험에서도 부른다) */
    static void read(String name, List<String> lore, long now) {
        if (statKinds(String.join(" | ", lore)) < 2) return;
        Fish f = current();
        boolean newFish = f == null || !sameFish(f.name, name);
        if (newFish) {
            f = new Fish();
            f.name = name;
            store().put(key(), f);
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
        if (h < 0 && m < 0 && v < 0) return;
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
        // 넣은 시각: 창에 남은 성장 시간이 있으면 거기서, 처음 본 반려어가 막 넣은 것(성장도 1 이하)이면 지금
        if (growLeft >= 0) f.insertedAt = now + growLeft - GROW_MS;
        else if (f.insertedAt == 0 && g >= 0 && g <= 1) f.insertedAt = now;
        save();
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
     * 마지막으로 읽은 스탯에서 at 시각까지 위키 규칙대로 굴려 본다. 줄어드는 때는 넣은 시각부터 10분마다
     * (넣은 시각을 모르면 읽은 때부터 10분마다). slowAt: 평균이 80% 아래로 내려가는 때, dangerAt: 다음 감소에 0% 가 될 수 있는 때
     */
    public static Forecast forecast(Fish f, long at) {
        double[] s = {f.hunger, f.mood, f.vigor};
        for (int i = 0; i < 3; i++) if (s[i] < 0) s[i] = 100;
        double g = Math.max(0, f.growth);
        long anchor = f.insertedAt > 0 ? f.insertedAt : f.statsAt;
        long growEnd = f.insertedAt > 0 ? f.insertedAt + GROW_MS : Long.MAX_VALUE;
        long t = f.statsAt;
        long nextDrop = anchor + ((Math.max(0, t - anchor) / tickMs()) + 1) * tickMs();
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
                int hi = 0;
                for (int i = 1; i < 3; i++) if (s[i] > s[hi]) hi = i;
                s[hi] = Math.max(0, s[hi] - DROP);
                if (min(s) <= 0) alive = false;
                if (slowAt == 0 && avg(s) < SLOW_AVG) slowAt = t;
                if (dangerAt == 0 && min(s) <= DROP) dangerAt = t;
                nextDrop += tickMs();
            } else if (t == nextDrop) {
                nextDrop += tickMs();
            }
            if (t >= at && slowAt != 0 && dangerAt != 0 && firstNext != 0) break;
        }
        // at 시각의 모습은 다시 굴려서(위 반복은 앞날까지 봤으므로) — 단순화를 위해 at 까지만 다시 계산
        Snapshot now = snapshot(f, at);
        boolean grownSafe = f.insertedAt > 0 && at >= f.insertedAt + GROW_MS && now.growth >= 90;
        return new Forecast(now.avg, now.min, now.growth, now.alive, firstNext, slowAt, dangerAt, grownSafe);
    }

    private record Snapshot(double avg, double min, double growth, boolean alive) {}

    /** at 시각의 스탯·성장도 */
    static Snapshot snapshot(Fish f, long at) {
        double[] s = {f.hunger, f.mood, f.vigor};
        for (int i = 0; i < 3; i++) if (s[i] < 0) s[i] = 100;
        double g = Math.max(0, f.growth);
        long anchor = f.insertedAt > 0 ? f.insertedAt : f.statsAt;
        long growEnd = f.insertedAt > 0 ? f.insertedAt + GROW_MS : Long.MAX_VALUE;
        long t = f.statsAt;
        long nextDrop = anchor + ((Math.max(0, t - anchor) / tickMs()) + 1) * tickMs();
        boolean alive = min(s) > 0;
        while (t < at && alive) {
            long step = Math.min(nextDrop, Math.min(growEnd > t ? growEnd : Long.MAX_VALUE, at));
            if (t < growEnd) g = Math.min(100, g + speed(avg(s)) * 100.0 * (Math.min(step, growEnd) - t) / GROW_MS);
            t = step;
            boolean stopped = growEnd != Long.MAX_VALUE && t >= growEnd && g >= 90;
            if (t == nextDrop) {
                if (!stopped) {
                    int hi = 0;
                    for (int i = 1; i < 3; i++) if (s[i] > s[hi]) hi = i;
                    s[hi] = Math.max(0, s[hi] - DROP);
                    if (min(s) <= 0) alive = false;
                }
                nextDrop += tickMs();
            }
        }
        return new Snapshot(avg(s), min(s), g, alive);
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

    private static double avg(Fish f) {
        return avgOf(f.hunger, f.mood, f.vigor);
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

    /** 1초마다 */
    public static void tick(long now) {
        if (!DdingMate.config().expert.petFish) return;
        Fish f = current();
        if (f == null || f.statsAt == 0) return;
        Forecast fc = forecast(f, now);
        if (!fc.alive() || fc.grownSafe()) {
            if (!fc.alive() && !fc.grownSafe() && !f.warnedDanger) {
                // 접속하지 않은 사이 예상상 스탯이 0% 가 됐다: 알림 없이 HUD 만 바뀌지 않게 한 번 알린다
                f.warnedDanger = true;
                f.warnedFeed = true;
                save();
                Notifier.petFish("반려어 " + f.name + " 어항을 확인해 주세요", "예상으로는 스탯이 0% 가 됐을 수 있어요 · 어항을 열면 실제 값으로 맞춰요");
            }
            if (fc.grownSafe() && !f.notifiedGrown) {
                f.notifiedGrown = true;
                save();
                Notifier.petFish("반려어 " + f.name + " 다 컸어요", "성장도 90 이상이라 이제 스탯이 줄지 않아요 · 포스에게 방생할 수 있어요");
            }
            return;
        }
        if (f.insertedAt > 0 && now >= f.insertedAt + GROW_MS && !f.notifiedGrown) {
            f.notifiedGrown = true;
            save();
            Notifier.petFish("반려어 " + f.name + " 성장 시간 끝", "성장도 90 아래면 스탯이 계속 줄어요 · 밥을 계속 챙겨 주세요");
        }
        if (!f.warnedDanger && fc.dangerAt() != 0 && now >= fc.dangerAt()) {
            f.warnedDanger = true;
            f.warnedFeed = true;
            save();
            Notifier.petFish("반려어 " + f.name + " 폐사 위험!", "다음 감소에 스탯이 0% 가 될 수 있어요 · 어항에서 빵·덩굴·뼈 블록으로 채워 주세요");
            return;
        }
        long feedAt = feedAt(f, fc);
        if (!f.warnedFeed && feedAt != 0 && now >= feedAt) {
            f.warnedFeed = true;
            save();
            Notifier.petFish("반려어 " + f.name + " 밥 줄 때예요", "스탯 평균이 80% 아래라 성장이 느려져요 · 빵 1개 허기 1%, 덩굴 1개 기분 2%, 뼈 블록 1개 활력 2%");
        }
    }

    // ---------------------------------------------------------------- HUD·탭에 보일 글

    /** 지켜보는 반려어(없으면 null) */
    public static Fish current() {
        String k = key();
        return k == null ? null : store().get(k);
    }

    public static String label(Fish f) {
        return "반려어 " + f.name;
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

    /** 밥 줄 때: 설정한 주기가 있으면 어항을 마지막으로 연 때 + 주기, 없으면(자동) 스탯 평균이 80% 아래로 내려갈 때 */
    static long feedAt(Fish f, Forecast fc) {
        int min = DdingMate.config().expert.petFishFeedMin;
        return min > 0 ? f.statsAt + min * 60_000L : fc.slowAt();
    }

    /** 탭에 보일 자세한 한 줄 */
    public static String detail(Fish f, long now) {
        if (f.statsAt == 0) return "어항 창을 열면 허기·기분·활력을 읽어요";
        Snapshot s = snapshot(f, now);
        StringBuilder sb = new StringBuilder();
        sb.append("지금 예상: 허기·기분·활력 평균 ").append(Math.round(s.avg())).append("% · 가장 낮은 스탯 ").append(Math.round(s.min())).append('%');
        if (f.growth >= 0) sb.append(" · 성장도 ").append(Math.round(s.growth()));
        if (f.insertedAt > 0) {
            long end = f.insertedAt + GROW_MS - now;
            sb.append(end > 0 ? " · 성장 " + hm(end) + " 남음" : " · 성장 시간 끝");
        }
        String fill = fillText(f);
        if (!fill.isEmpty()) sb.append(" · 어항을 연 때 기준 다 채우려면 ").append(fill);
        return sb.toString();
    }

    /** 회복 아이템 하나가 채우는 양(2026-10-31 사용자 확인): 빵 = 허기 1%, 덩굴 = 기분 2%, 뼈 블록 = 활력 2% */
    static final double BREAD = 1, VINE = 2, BONE = 2;

    /** 마지막으로 읽은 값을 100% 로 채우는 데 드는 아이템: "빵 25개 · 뼈 블록 3개"(다 차 있으면 빈 글) */
    public static String fillText(Fish f) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int bread = need(f.hunger, BREAD), vine = need(f.mood, VINE), bone = need(f.vigor, BONE);
        if (bread > 0) parts.add("빵 " + bread + "개");
        if (vine > 0) parts.add("덩굴 " + vine + "개");
        if (bone > 0) parts.add("뼈 블록 " + bone + "개");
        return String.join(" · ", parts);
    }

    private static int need(double stat, double per) {
        return stat < 0 ? 0 : (int) Math.ceil(Math.max(0, 100 - stat) / per - 1e-9);
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
    public static void readSkill(String name, java.util.List<String> lore) {
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

    /** 지금 반려어 기록을 지운다(꺼냈거나 방생했을 때) */
    public static void forget() {
        String k = key();
        if (k != null && store().remove(k) != null) save();
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
                    if (read != null) store.putAll(read);
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
        read(name, lore, now);
    }

    /** 시험용: 지금 반려어를 이 제목의 창에서 읽은 것으로 */
    public static void debugSetScreen(String rawTitle) {
        Fish f = current();
        if (f != null) f.screen = rawTitle;
    }

    /** 시험용: 창을 본 것처럼(제목, 반려어 칸이 있었나, 칸이 하나라도 있었나) */
    public static void debugAfterScan(String rawTitle, boolean found, boolean anyItem, long now) {
        afterScan(rawTitle, ItemKeys.stripGlyphs(rawTitle), found, anyItem, now);
    }

    public static void debugReset() {
        store().clear();
        save();
        lastLore = "";
    }
}
