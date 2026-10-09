package kr.ddingmate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.schedule.EventRule;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** config/ddingmate.json 에 저장되는 사용자 설정. */
public final class AlarmConfig {

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static final class EventSetting {
        public boolean enabled = true;
        public List<Integer> leads = new ArrayList<>();
    }

    public static final class Keywords {
        public boolean enabled = true;
        /** 내 닉네임이 다른 사람 메시지에 나오면 알림 */
        public boolean includeOwnName = true;
        public List<String> words = new ArrayList<>(List.of("오션오더", "풍선", "청소"));
        public int cooldownSeconds = 10;
    }

    public static final int PIN_COUNT = 3;

    public static final class Pin {
        /** RecipeBook 의 레시피 id. 비어 있으면 핀이 없는 칸 */
        public String recipe = "";
        /** 몇 번 만들지 */
        public int crafts = 1;
    }

    public static final class Counter {
        public boolean enabled = true;
        /** 열어 봤던 모험 가방 내용도 합산 */
        public boolean includeBags = true;
        /** 핀 재료가 다 모이면 한 번 알림 */
        public boolean notifyComplete = true;
        public Corner corner = Corner.TOP_LEFT;
        /** 가방을 여는 단축어. 뒤에 붙은 번호로 몇 번 가방인지 안다. 예: ㄱ1, /가방 1 */
        public List<String> bagAliases = new ArrayList<>(List.of("ㄱ", "가방"));
        public List<Pin> pins = new ArrayList<>();
        /** 모자란 재료가 다른 레시피로 만드는 재료면 원재료까지 한 단계 펼쳐 보여 줌 */
        public boolean expandSubMaterials = true;
        /** 열어 본 아이템 저장소 내용도 합산 */
        public boolean includeStorage = true;
    }

    /** 추적하는 장비 하나. key 는 ItemKeys 키, name 은 화면에 보일 이름 */
    public static final class TrackedItem {
        public String key = "";
        public String name = "";

        public TrackedItem() {}

        public TrackedItem(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    public static final class Durability {
        public boolean enabled = true;
        public Corner corner = Corner.BOTTOM_RIGHT;
        /** 남은 내구도가 이 % 이하로 떨어지면 한 번 알림 (0 = 알리지 않음) */
        public int warnPercent = 10;
        public List<TrackedItem> tracked = new ArrayList<>();
    }

    /** 할 일 초기화 시점: 매일 03시, 매주 목요일 03시, 매일 자정 */
    public enum Reset { DAILY, WEEKLY, MIDNIGHT }

    public static final class TodoItem {
        public String name = "";
        public Reset reset = Reset.DAILY;
        /** 마지막으로 체크한 시각(ms). 초기화 시점보다 이전이면 안 한 것 */
        public long doneAt;

        public TodoItem() {}

        public TodoItem(String name, Reset reset) {
            this.name = name;
            this.reset = reset;
        }
    }

    public static final class Todo {
        public boolean hudEnabled = false;
        public Corner corner = Corner.TOP_RIGHT;
        /** 일일 초기화 몇 분 전에 남은 할 일을 알려 줄지 (0 = 알리지 않음) */
        public int remindMinutes = 60;
        public List<TodoItem> items = new ArrayList<>(List.of(
                new TodoItem("일일 의뢰", Reset.DAILY),
                new TodoItem("오션오더", Reset.DAILY),
                new TodoItem("미니보스", Reset.DAILY),
                new TodoItem("바리스타", Reset.DAILY),
                new TodoItem("인피니티 타워", Reset.DAILY),
                new TodoItem("주간 의뢰", Reset.WEEKLY),
                new TodoItem("접속 시간 보상 받기", Reset.MIDNIGHT)));
    }

    public static final class Playtime {
        /** 접속 1·2·3·4·5·8시간을 넘길 때마다 보상을 받으라고 알림 */
        public boolean enabled = true;
        /** 일정 HUD 맨 위에 오늘 접속 시간과 다음 보상까지 남은 시간 표시 */
        public boolean showInHud = true;
        /** 23:30 에 "자정에 초기화돼요" 알림 */
        public boolean midnightReminder = true;
    }

    /** DARK=서버 HUD 처럼 어두운 판, LIGHT=서버 창처럼 밝은 판, CLASSIC=예전 반투명 검은 상자 */
    public enum HudStyle { DARK, LIGHT, CLASSIC }

    public boolean hudEnabled = true;
    public HudStyle hudStyle = HudStyle.DARK;
    /** HUD 판 진하기 30~100 (%) */
    public int hudOpacity = 85;
    /** 설정 화면·HUD·팝업을 갈무리 글꼴(띵타이쿤과 같은 글꼴)로 그릴지 */
    public boolean galmuriFont = true;
    public Corner hudCorner = Corner.TOP_LEFT;
    public int hudLines = 4;
    public boolean soundEnabled = true;
    public float soundVolume = 1.0f;
    public boolean toastEnabled = true;
    public boolean chatEnabled = true;
    public int villageChannel = 0;
    public int huntChannel = 0;
    public int wildChannel = 0;
    public int islandChannel = 0;
    public int spawnChannel = 0;
    public Map<String, EventSetting> events = new LinkedHashMap<>();
    public Keywords keywords = new Keywords();
    public Counter counter = new Counter();
    public Durability durability = new Durability();
    public Todo todo = new Todo();
    public Playtime playtime = new Playtime();

    // ---------------------------------------------------------------- 저장·불러오기

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate.json");
    }

    public static AlarmConfig load() {
        Path p = path();
        AlarmConfig cfg = null;
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(r, AlarmConfig.class);
            } catch (IOException | RuntimeException e) {
                DdingMate.LOG.warn("설정 파일을 읽지 못해 기본값을 씁니다: {}", p, e);
            }
        }
        if (cfg == null) cfg = new AlarmConfig();
        cfg.sanitize();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            DdingMate.LOG.error("설정 파일 저장 실패", e);
        }
    }

    /** 빠진 항목은 기본값으로 채우고 범위를 벗어난 값은 바로잡는다. */
    public void sanitize() {
        if (hudCorner == null) hudCorner = Corner.TOP_LEFT;
        hudLines = Math.max(1, Math.min(10, hudLines));
        hudOpacity = Math.max(30, Math.min(100, hudOpacity));
        if (hudStyle == null) hudStyle = HudStyle.DARK;
        soundVolume = Math.max(0f, Math.min(1f, soundVolume));
        if (events == null) events = new LinkedHashMap<>();
        if (keywords == null) keywords = new Keywords();
        if (keywords.words == null) keywords.words = new ArrayList<>();
        keywords.cooldownSeconds = Math.max(0, keywords.cooldownSeconds);
        if (counter == null) counter = new Counter();
        if (counter.corner == null) counter.corner = Corner.TOP_LEFT;
        if (counter.bagAliases == null) counter.bagAliases = new ArrayList<>();
        counter.bagAliases.removeIf(a -> a == null || a.isBlank());
        if (counter.pins == null) counter.pins = new ArrayList<>();
        counter.pins.removeIf(java.util.Objects::isNull);
        while (counter.pins.size() < PIN_COUNT) counter.pins.add(new Pin());
        while (counter.pins.size() > PIN_COUNT) counter.pins.remove(counter.pins.size() - 1);
        for (Pin p : counter.pins) {
            if (p.recipe == null) p.recipe = "";
            p.crafts = Math.max(1, Math.min(9999, p.crafts));
        }
        if (durability == null) durability = new Durability();
        if (durability.corner == null) durability.corner = Corner.BOTTOM_RIGHT;
        if (durability.tracked == null) durability.tracked = new ArrayList<>();
        durability.tracked.removeIf(t -> t == null || t.key == null || t.key.isBlank());
        durability.warnPercent = Math.max(0, Math.min(90, durability.warnPercent));
        if (todo == null) todo = new Todo();
        if (todo.corner == null) todo.corner = Corner.TOP_RIGHT;
        if (todo.items == null) todo.items = new ArrayList<>();
        todo.items.removeIf(t -> t == null || t.name == null || t.name.isBlank());
        for (TodoItem t : todo.items) if (t.reset == null) t.reset = Reset.DAILY;
        todo.remindMinutes = Math.max(0, Math.min(600, todo.remindMinutes));
        if (playtime == null) playtime = new Playtime();
        // 채널 재시작 규칙까지 모두 만들 수 있도록 임의 채널로 전체 규칙을 받아 기본 설정을 채운다.
        for (EventRule r : ScheduleEngine.buildRules(new ScheduleEngine.Channels(1, 1, 1, 1, 1))) {
            EventSetting s = events.get(r.id());
            if (s == null) {
                s = new EventSetting();
                s.leads = new ArrayList<>(r.defaultLeads());
                events.put(r.id(), s);
            }
            if (s.leads == null) s.leads = new ArrayList<>(r.defaultLeads());
            s.leads.removeIf(v -> v == null || v < 0 || v > 24 * 60);
        }
    }

    public ScheduleEngine.Channels channels() {
        return new ScheduleEngine.Channels(villageChannel, huntChannel, wildChannel, islandChannel, spawnChannel);
    }

    public boolean isEnabled(EventRule r) {
        EventSetting s = events.get(r.id());
        return s == null || s.enabled;
    }

    public List<Integer> leadsOf(EventRule r) {
        EventSetting s = events.get(r.id());
        return s == null ? r.defaultLeads() : s.leads;
    }
}
