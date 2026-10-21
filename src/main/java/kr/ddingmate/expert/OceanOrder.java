package kr.ddingmate.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.game.ServerText;
import kr.ddingmate.notify.Notifier;
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
 * 오션오더 타이머. 위키(2026-10-18): 야생 낚시 중 30% 확률로 도착, 제한시간 300초, 하루 10번(03시 초기화),
 * 받은 뒤 다른 채널로 옮기면 실패. 서버 문구(채팅·액션바·제목·보스바)에 "오션오더"가 들어가면 읽어서
 * 도착이면 5분 타이머를 켜고, 성공·실패면 끈다. 실제 문구를 아직 못 봐서(로그 6개월치에 없음) 읽은 문구를 로그에
 * "오션오더 문구:"로 남겨 맞춰 간다. 서버로는 아무것도 보내지 않는다.
 */
public final class OceanOrder {

    private OceanOrder() {}

    public static final long LIMIT_MS = 300_000;
    public static final int DAILY_MAX = 10;

    private static final Pattern FAIL = Pattern.compile("실패|취소|만료|시간\\s*초과|시간이\\s*(?:다\\s*)?(?:지나|끝)");
    private static final Pattern SUCCESS = Pattern.compile("성공|완료|달성|클리어");
    private static final Pattern START = Pattern.compile("도착|시작|발동|발생|수령|받았|등장");
    /** "남은 시간 : 4분 32초", "272초" */
    private static final Pattern MIN_SEC = Pattern.compile("(\\d+)\\s*분\\s*(\\d+)\\s*초");
    private static final Pattern SEC = Pattern.compile("(\\d+)\\s*초");
    /** 오션오더일지 모르는 서버 문구(로그만) */
    private static final Pattern CANDIDATE = Pattern.compile("주문.*(?:물고기|낚)|(?:물고기|낚).*주문|골든\\s*티켓");
    /** "(2/3)" 같은 진행 */
    private static final Pattern PROGRESS = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private static long endsAt;
    private static long startedAt;
    private static String progress = "";
    private static boolean warned;
    private static String lastLogged = "";
    private static int lastChannel = -1;

    private static final class Day {
        String day = "";
        int count;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Day> days;

    // ---------------------------------------------------------------- 문구 읽기

    /** 채팅(overlay=false)·액션바·제목·보스바 글. 사람이 쓴 채팅은 거른다 */
    public static void onMessage(String raw) {
        if (raw == null || !DdingMate.config().expert.oceanOrder) return;
        String t = ItemKeys.stripGlyphs(raw).replaceAll("\\s+", " ").trim();
        if (ServerText.fromPlayer(t)) return;
        if (!t.replace(" ", "").contains("오션오더")) {
            // 실제 도착 문구를 아직 못 봤다: "오션오더"가 없는 주문·물고기 안내도 로그에 남겨 맞춰 간다
            if (CANDIDATE.matcher(t).find() && !t.equals(lastLogged)) {
                lastLogged = t;
                DdingMate.LOG.info("오션오더 후보 문구: {}", t);
            }
            return;
        }
        if (!t.equals(lastLogged)) {
            lastLogged = t;
            DdingMate.LOG.info("오션오더 문구: {}", t);
        }
        handle(t, System.currentTimeMillis());
    }

    static void handle(String t, long now) {
        // 끝 → 성공 → 도착 순서로 본다("오션오더 성공! 보상을 수령했습니다"를 도착으로 착각하지 않게)
        if (FAIL.matcher(t).find()) {
            if (active(now)) DdingMate.LOG.info("오션오더 끝(실패): {}", t);
            clear();
            return;
        }
        if (SUCCESS.matcher(t).find()) {
            clear();
            return;
        }
        long left = remainingIn(t);
        if (active(now)) {
            // 진행 중에 온 문구: 남은 시간·진행만 맞춘다(물고기를 낚을 때마다 오는 알림 등)
            if (left > 0) endsAt = now + left;
            Matcher p = PROGRESS.matcher(t);
            if (p.find()) progress = p.group(1) + "/" + p.group(2);
            return;
        }
        if (START.matcher(t).find()) start(now, left > 0 && left <= LIMIT_MS ? left : LIMIT_MS);
    }

    /** 문구에 적힌 남은 시간(ms). 없으면 0 */
    public static long remainingIn(String t) {
        Matcher m = MIN_SEC.matcher(t);
        if (m.find()) return (Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000;
        Matcher s = SEC.matcher(t);
        if (s.find()) {
            long sec = Long.parseLong(s.group(1));
            if (sec > 0 && sec <= LIMIT_MS / 1000) return sec * 1000;
        }
        return 0;
    }

    private static void start(long now, long limitMs) {
        startedAt = now;
        endsAt = now + limitMs;
        progress = "";
        warned = false;
        lastChannel = kr.ddingmate.game.ServerHud.channel(); // 이 채널에서 받았다(다른 채널로 옮기면 실패)
        Day d = today();
        d.count = Math.min(DAILY_MAX, d.count + 1);
        save();
        Notifier.oceanOrder("오션오더 도착!", (limitMs / 1000 / 60) + "분 " + (limitMs / 1000 % 60) + "초 안에 주문한 물고기를 낚아 주세요 · 오늘 "
                + d.count + "/" + DAILY_MAX);
    }

    private static void clear() {
        endsAt = 0;
        startedAt = 0;
        progress = "";
        lastChannel = -1;
    }

    private static String lastScreen = "";

    /**
     * 10틱마다: /오션오더 창이나 "오션오더"가 이름에 든 칸이 있으면 설명을 로그에 남기고, 남은 시간이 적혀 있으면
     * 진행 중인 오션오더 시간을 맞춘다(도착 문구를 놓쳤어도 창을 열면 HUD 에 남은 시간이 뜨게)
     */
    public static void scanScreen(net.minecraft.client.MinecraftClient client) {
        if (!DdingMate.config().expert.oceanOrder) return;
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen)) return;
        String title = ItemKeys.stripGlyphs(screen.getTitle().getString()).replace(" ", "");
        for (net.minecraft.screen.slot.Slot slot : screen.getScreenHandler().slots) {
            if (!slot.hasStack()) continue;
            net.minecraft.item.ItemStack s = slot.getStack();
            String name = ItemKeys.stripGlyphs(s.getName().getString()).trim();
            if (!title.contains("오션오더") && !name.replace(" ", "").contains("오션오더")) continue;
            net.minecraft.component.type.LoreComponent lore = s.get(net.minecraft.component.DataComponentTypes.LORE);
            StringBuilder sb = new StringBuilder(name);
            if (lore != null) for (net.minecraft.text.Text l : lore.lines()) sb.append(" | ").append(ItemKeys.stripGlyphs(l.getString()).trim());
            String line = sb.toString().replaceAll("\\s+", " ");
            if (line.length() > 2 && !line.equals(lastScreen)) {
                lastScreen = line;
                DdingMate.LOG.info("오션오더 창 칸: {}", line);
            }
            if (line.contains("남은") || line.contains("제한")) {
                long left = remainingIn(line);
                long now = System.currentTimeMillis();
                if (left > 0 && left <= LIMIT_MS) {
                    if (!active(now)) {
                        startedAt = now;
                        progress = "";
                        warned = false;
                        lastChannel = kr.ddingmate.game.ServerHud.channel();
                    }
                    endsAt = now + left;
                }
            }
        }
    }

    /** 1초마다: 1분 남으면 한 번 알리고, 시간이 지나면 끈다. 채널을 옮기면 실패(위키) */
    public static void tick(long now) {
        if (endsAt == 0) return;
        int ch = kr.ddingmate.game.ServerHud.channel();
        if (lastChannel > 0 && ch > 0 && ch != lastChannel) {
            Notifier.oceanOrder("오션오더 실패", "다른 채널로 옮기면 오션오더는 실패로 처리돼요");
            clear();
        }
        lastChannel = ch;
        if (endsAt == 0) return;
        long left = endsAt - now;
        if (left <= 0) {
            clear();
            return;
        }
        if (!warned && left <= 60_000) {
            warned = true;
            Notifier.oceanOrder("오션오더 1분 남았어요", progress.isEmpty() ? "주문한 물고기를 서둘러 낚아 주세요" : "지금 " + progress);
        }
    }

    public static boolean active(long now) {
        return endsAt > now;
    }

    /** HUD 줄: "오션오더 (오늘 3/10)" · "4분 32초" */
    public static String label() {
        return "오션오더" + (progress.isEmpty() ? "" : " " + progress) + " (오늘 " + today().count + "/" + DAILY_MAX + ")";
    }

    public static String remainingText(long now) {
        long sec = Math.max(0, (endsAt - now + 999) / 1000);
        return sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초";
    }

    public static long endsAt() {
        return endsAt;
    }

    /** 오늘(03시 기준) 받은 오션오더 수 */
    public static int todayCount() {
        return today().count;
    }

    // ---------------------------------------------------------------- 하루 횟수 저장(03시 초기화)

    static String gameDay(ZonedDateTime now) {
        return now.minusHours(3).toLocalDate().toString();
    }

    private static Day today() {
        if (days == null) load();
        String key = kr.ddingmate.craft.CraftTimers.accountKey();
        Day d = days.computeIfAbsent(key == null ? "?" : key, k -> new Day());
        String day = gameDay(ZonedDateTime.now(ScheduleEngine.KST));
        if (!day.equals(d.day)) {
            d.day = day;
            d.count = 0;
        }
        return d;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-ocean.json");
    }

    private static void load() {
        days = new LinkedHashMap<>();
        if (!Files.exists(path())) return;
        try (Reader r = Files.newBufferedReader(path(), StandardCharsets.UTF_8)) {
            Map<String, Day> read = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, Day>>() {}.getType());
            if (read != null) days.putAll(read);
        } catch (Exception e) {
            DdingMate.LOG.warn("오션오더 기록을 읽지 못했어요", e);
        }
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(days, w);
        } catch (Exception e) {
            DdingMate.LOG.warn("오션오더 기록을 저장하지 못했어요", e);
        }
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugReset() {
        clear();
        warned = false;
        lastChannel = -1;
        if (days == null) load();
        days.clear();
        save();
    }

    public static void debugHandle(String text, long now) {
        handle(text, now);
    }
}
