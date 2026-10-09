package kr.ddingmate.playtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

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
    }

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
        if (client.world == null || client.player == null) {
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
        AlarmConfig.Playtime cfg = DdingMate.config().playtime;
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
                Notifier.playtimeMidnight((int) (d.seconds / 3600));
                save();
            }
        }
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
                for (Day d : s.accounts.values()) if (d.notified == null) d.notified = new ArrayList<>();
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
