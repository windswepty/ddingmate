package kr.ddingmate.notify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import kr.ddingmate.DdingMate;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 알림 기록함. 소리·팝업·채팅을 꺼 두었어도 알림이 생기면 여기에 남는다.
 * 최근 100개를 config/ddingmate-log.json 에 저장해 다음 접속 때도 볼 수 있다.
 */
public final class AlertLog {

    public static final int MAX = 100;

    public static final class Entry {
        public long at;
        public String kind;
        public String title;
        public String body;

        public Entry() {}

        Entry(long at, String kind, String title, String body) {
            this.at = at;
            this.kind = kind;
            this.title = title;
            this.body = body;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static List<Entry> entries = new ArrayList<>();
    private static int unread;

    private AlertLog() {}

    public static synchronized void add(String kind, String title, String body) {
        entries.add(0, new Entry(kr.ddingmate.util.Clock.now(), kind, title, body));
        while (entries.size() > MAX) entries.remove(entries.size() - 1);
        unread++;
        save();
    }

    /** 최신순 */
    public static synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public static synchronized int unread() {
        return unread;
    }

    public static synchronized void markRead() {
        unread = 0;
    }

    public static synchronized void clear() {
        entries.clear();
        unread = 0;
        save();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-log.json");
    }

    public static synchronized void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            List<Entry> list = GSON.fromJson(r, new TypeToken<List<Entry>>() {}.getType());
            if (list != null) {
                list.removeIf(e -> e == null || e.title == null);
                entries = new ArrayList<>(list.subList(0, Math.min(MAX, list.size())));
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("알림 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(entries, w);
            }
        } catch (IOException e) {
            DdingMate.LOG.error("알림 기록 저장 실패", e);
        }
    }
}
