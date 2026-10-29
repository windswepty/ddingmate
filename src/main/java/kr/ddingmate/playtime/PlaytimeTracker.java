package kr.ddingmate.playtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 오늘 접속 시간을 내 게임에서 세어, 위키의 접속 시간 보상 구간(1·2·3·4·5·8시간)을 넘길 때 알린다.
 * 서버가 세는 시간과 몇 분 차이가 날 수 있는 추정값이다. 자정(한국 시간)에 0으로 돌아간다.
 * 보상을 받는 건 /접속시간 에서 직접 해야 하며, 모드가 대신 받지 않는다.
 */
public final class PlaytimeTracker {

    /** 위키 [접속시간] 기준 보상 구간과 보상 */
    public static final Map<Integer, String> REWARDS = new LinkedHashMap<>();
    static {
        REWARDS.put(1, "루키 열쇠 1개");
        REWARDS.put(2, "노멀 열쇠 1개 + 10,000 골드");
        REWARDS.put(3, "골드하프 1개");
        REWARDS.put(4, "골드하프 2개");
        REWARDS.put(5, "골드하프 3개");
        REWARDS.put(8, "골드하프 4개");
    }

    private static final LocalTime MIDNIGHT_REMIND = LocalTime.of(23, 30);
    /** 한 번에 더할 수 있는 최대 시간(초). 게임이 멈췄다 돌아온 경우 크게 튀지 않게 */
    private static final long MAX_STEP_MS = 5000;

    static final class Day {
        String date = "";
        long seconds;
        List<Integer> notified = new ArrayList<>();
        boolean reminded;
        /** 서버 기록(/접속시간 창)에 마지막으로 맞춘 시각(ms). 0 이면 오늘은 아직 안 맞춤(추정값) */
        long syncedAt;
        /** 접속하면 서버가 채팅으로 보내는 "일일 접속 시간"에 맞춘 시각(ms). 받은 보상은 알 수 없다 */
        long chatSyncedAt;
        /** /접속시간 창에서 확인한 이미 받은 보상 구간. syncedAt 이 0 이면 모름 */
        List<Integer> claimed = new ArrayList<>();
    }

    /** /접속시간 창의 보상 아이템 이름: 받은 칸 "[ 1시간 접속 보상 ]", 안 받은 칸 "[ 1시간 접속 보상 받기 ]"(2026-10-23~25 원본 기록) */
    private static final Pattern REWARD_NAME = Pattern.compile("\\[\\s*(\\d+)\\s*시간\\s*접속\\s*보상(?:\\s*받기)?\\s*]");
    /** 보상 아이템 설명: "- 오늘 접속 시간 : 11시간 49분 33초" (시간·분은 없을 수 있음) */
    private static final Pattern TODAY = Pattern.compile(
            "오늘\\s*접속\\s*시간\\s*[:：]?\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?");
    private static final String CLAIMED = "이미 수령";
    /** 게임 서버에 들어가면 서버가 보내는 채팅: "├ 일일 접속 시간 : 16시간 37분 57초" */
    private static final Pattern CHAT_TODAY = Pattern.compile(
            "[├└]?\\s*일일\\s*접속\\s*시간\\s*[:：]\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?");
    /** 보상을 받으면 서버가 보내는 채팅: "3시간 접속 보상을 수령하였습니다." */
    private static final Pattern CHAT_CLAIMED = Pattern.compile("(\\d+)\\s*시간\\s*접속\\s*보상을\\s*수령");

    private static final class Store {
        Map<String, Day> accounts = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Store store = new Store();
    private static long lastMs;
    private static long lastSaveMs;

    private PlaytimeTracker() {}

    /** 매 틱. 월드에 들어가 있는 동안만 시간을 더한다. */
    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        // 띵타이쿤 대기 서버에 있는 동안은 서버도 접속 시간을 세지 않는다. 잠수 채널은 센다(서버 [답변] 2026-10-16·10-24,
        // 10-23 실제: 잠수 8시간 20분이 서버 접속 시간에 들어감). 잠수 채널엔 채널 표시가 없어서 inGame() 이 거짓이다
        boolean counting = kr.ddingmate.game.ServerHud.inGame() || kr.ddingmate.game.ServerHud.afk();
        if (client.world == null || client.player == null || !counting) {
            if (lastMs != 0) save();
            lastMs = 0;
            return;
        }
        Day d = today();
        if (d == null) return;
        if (lastMs != 0) {
            long step = Math.min(MAX_STEP_MS, Math.max(0, now - lastMs));
            d.seconds += step / 1000;
            // 1초 미만은 다음 틱으로 넘긴다
            lastMs = now - (step % 1000);
        } else {
            lastMs = now;
        }
        check(d);
        if (now - lastSaveMs > 60_000) save();
    }

    private static void check(Day d) {
        DdingMateConfig.Playtime cfg = DdingMate.config().playtime;
        if (!cfg.enabled) return;
        for (Map.Entry<Integer, String> e : REWARDS.entrySet()) {
            int h = e.getKey();
            if (d.seconds >= h * 3600L && !d.notified.contains(h)) {
                d.notified.add(h);
                Notifier.playtimeReward(h, e.getValue());
                save();
            }
        }
        if (cfg.midnightReminder && !d.reminded && d.seconds >= 3600) {
            ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
            if (!now.toLocalTime().isBefore(MIDNIGHT_REMIND)) {
                d.reminded = true;
                // 받은 보상을 알고 있으면 안 받은 것만, 모르면 채운 시간만 알린다. 다 받았으면 알리지 않는다.
                List<Integer> left = unclaimed(d);
                if (d.syncedAt == 0) Notifier.playtimeMidnight("오늘 " + (d.seconds / 3600) + "시간 채웠어요");
                else if (!left.isEmpty()) Notifier.playtimeMidnight("안 받은 보상: " + hours(left));
                save();
            }
        }
    }

    // ---------------------------------------------------------------- 서버 기록 맞추기

    /**
     * 내가 직접 연 /접속시간 창에서 보상 아이템 설명을 읽어 서버 기록에 맞춘다.
     * 설명의 "오늘 접속 시간 : 11시간 49분 33초" 로 시간을, "이미 수령한 보상입니다." 로 받은 보상을 안다.
     * 창을 대신 열거나 명령어를 보내지 않는다.
     */
    /**
     * 서버 채팅으로 접속 시간을 맞춘다. 게임 서버에 들어가면 서버가 "일일 접속 시간"을 알려 주므로
     * /접속시간 창을 열지 않아도 정확한 값으로 시작한다. 보상을 받았다는 메시지는 받은 보상으로 기록한다.
     */
    public static void onChat(String message) {
        if (message == null) return;
        String t = Formatting.strip(message);
        if (t == null) return;
        // 서버 그림 글자를 지운 뒤 줄 맨 앞이 "├ 일일 접속 시간" 이어야 서버 안내다(다른 사람이 채팅으로 흉내 내도 앞에 이름이 붙는다)
        t = kr.ddingmate.counter.ItemKeys.stripGlyphs(t).trim();
        Day d = MinecraftClient.getInstance().player == null ? null : today();
        if (d == null) return;
        Matcher m = CHAT_TODAY.matcher(t);
        if (m.lookingAt() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
            d.seconds = num(m.group(1)) * 3600L + num(m.group(2)) * 60L + num(m.group(3));
            d.chatSyncedAt = System.currentTimeMillis();
            lastMs = System.currentTimeMillis();
            // 이미 넘긴 구간은 접속하자마자 한꺼번에 울리지 않게 알린 것으로 친다
            for (int h : REWARDS.keySet()) if (d.seconds >= h * 3600L && !d.notified.contains(h)) d.notified.add(h);
            save();
            return;
        }
        Matcher c = CHAT_CLAIMED.matcher(t);
        if (!kr.ddingmate.game.ServerText.fromPlayer(t) && c.lookingAt()) {
            int h = Integer.parseInt(c.group(1));
            if (!d.claimed.contains(h)) d.claimed.add(h);
            if (!d.notified.contains(h)) d.notified.add(h);
            save();
        }
    }

    public static void scanScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen) || client.player == null) return;
        Inventory inv = screen.getScreenHandler().getInventory();
        long serverSeconds = -1;
        List<Integer> claimed = new ArrayList<>();
        boolean sawReward = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Matcher name = REWARD_NAME.matcher(plain(s.getName()));
            if (!name.find()) continue;
            sawReward = true;
            int hour = Integer.parseInt(name.group(1));
            LoreComponent lore = s.get(DataComponentTypes.LORE);
            if (lore == null) continue;
            for (Text line : lore.lines()) {
                String t = plain(line);
                if (t.contains(CLAIMED) && !claimed.contains(hour)) claimed.add(hour);
                Matcher m = TODAY.matcher(t);
                if (m.find() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
                    serverSeconds = num(m.group(1)) * 3600L + num(m.group(2)) * 60L + num(m.group(3));
                }
            }
        }
        if (!sawReward) return;
        Day d = today();
        if (d == null) return;
        if (serverSeconds >= 0) {
            d.seconds = serverSeconds;
            lastMs = System.currentTimeMillis();
        }
        d.syncedAt = System.currentTimeMillis();
        d.claimed = claimed;
        kr.ddingmate.todo.TodoList.sync(kr.ddingmate.todo.TodoPreset.PLAYTIME, claimed.size()); // 할 일 "접속 시간 보상 받기"
        // 이미 넘긴 구간은 알림이 한꺼번에 울리지 않게 알린 것으로 친다(창을 보고 있으니 따로 알릴 필요 없음).
        for (int h : REWARDS.keySet()) if (d.seconds >= h * 3600L && !d.notified.contains(h)) d.notified.add(h);
        if (System.currentTimeMillis() - lastSaveMs > 5000) save();
    }

    private static String plain(Text t) {
        String s = Formatting.strip(t.getString());
        return s == null ? "" : s;
    }

    private static int num(String s) {
        return s == null ? 0 : Integer.parseInt(s);
    }

    /** 채웠지만 아직 안 받은 보상 구간(받은 보상을 모르면 채운 구간 전부) */
    private static List<Integer> unclaimed(Day d) {
        List<Integer> out = new ArrayList<>();
        for (int h : REWARDS.keySet()) if (d.seconds >= h * 3600L && !d.claimed.contains(h)) out.add(h);
        return out;
    }

    private static String hours(List<Integer> hs) {
        StringBuilder sb = new StringBuilder();
        for (int h : hs) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(h).append("시간");
        }
        return sb.toString();
    }

    /** 오늘 서버 기록에 맞춘 적이 있으면 그 시각(ms), 아니면 0 */
    public static long syncedAt() {
        Day d = MinecraftClient.getInstance().player == null ? null : today();
        return d == null ? 0 : Math.max(d.syncedAt, d.chatSyncedAt);
    }

    /** 채웠지만 안 받은 보상 구간. 오늘 서버 기록을 본 적이 없으면 null(모름) */
    public static List<Integer> unclaimedRewards() {
        Day d = MinecraftClient.getInstance().player == null ? null : today();
        if (d == null || d.syncedAt == 0) return null;
        return unclaimed(d);
    }

    /** 오늘(한국 날짜) 접속 기록. 날짜가 바뀌었으면 새로 시작 */
    private static Day today() {
        String key = accountKey();
        if (key == null) return null;
        String date = ZonedDateTime.now(ScheduleEngine.KST).toLocalDate().toString();
        Day d = store.accounts.get(key);
        if (d == null || !date.equals(d.date)) {
            d = new Day();
            d.date = date;
            store.accounts.put(key, d);
        }
        return d;
    }

    /** 오늘 접속 시간(초). 월드 밖이면 -1 */
    public static long seconds() {
        Day d = MinecraftClient.getInstance().player == null ? null : today();
        return d == null ? -1 : d.seconds;
    }

    /** 다음 보상 구간(시간). 다 채웠으면 0 */
    public static int nextRewardHour(long seconds) {
        for (int h : REWARDS.keySet()) if (seconds < h * 3600L) return h;
        return 0;
    }

    /** 시험용: 오늘 접속 시간을 정한다 */
    public static void debugSetSeconds(long seconds) {
        Day d = today();
        if (d != null) {
            d.seconds = seconds;
            d.notified.removeIf(h -> h * 3600L > seconds);
        }
    }

    private static String accountKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID id = client.player != null ? client.player.getUuid() : null;
        if (id == null) return null;
        ServerInfo server = client.getCurrentServerEntry();
        String where = server == null ? "singleplayer" : server.address.toLowerCase(Locale.ROOT);
        return id + "@" + where;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-playtime.json");
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s != null && s.accounts != null) {
                s.accounts.values().removeIf(d -> d == null || d.date == null);
                for (Day d : s.accounts.values()) {
                    if (d.notified == null) d.notified = new ArrayList<>();
                    if (d.claimed == null) d.claimed = new ArrayList<>();
                }
                store = s;
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("접속 시간 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    public static void save() {
        lastSaveMs = System.currentTimeMillis();
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(store, w);
            }
        } catch (IOException e) {
            DdingMate.LOG.error("접속 시간 기록 저장 실패", e);
        }
    }
}
