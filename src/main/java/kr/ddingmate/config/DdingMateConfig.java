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
public final class DdingMateConfig {

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static final class EventSetting {
        public boolean enabled = true;
        public List<Integer> leads = new ArrayList<>();
    }

    /** 개발자 공지(중계 /notices): 받기 켜기, 이미 본 공지 번호 */
    public static final class Notices {
        public boolean enabled = true;
        public List<String> seen = new ArrayList<>();
    }

    public static final class Keywords {
        public boolean enabled = true;
        /** 내 닉네임이 다른 사람 메시지에 나오면 알림 */
        public boolean includeOwnName = true;
        public List<String> words = new ArrayList<>(List.of("오션오더", "풍선"));
        /** 제외할 말: 이 말 안에 든 키워드는 알리지 않는다(예: 키워드 "찬", 제외 "이찬" — 문의 6d1b50b0) */
        public List<String> excludes = new ArrayList<>();
        public int cooldownSeconds = 10;
    }

    public static final int PIN_COUNT = 3;

    public static final class Pin {
        /** RecipeBook 의 레시피 id. 비어 있으면 핀이 없는 칸 */
        public String recipe = "";
        /** 입력한 개수. byOutput 이 false 면 몇 번 만들지, true 면 결과물을 몇 개 얻을지 */
        public int crafts = 1;
        /** 한 번에 여러 개 나오는 레시피(예: 깐 새우 x2)에서 개수를 결과물 기준으로 받을지 */
        public boolean byOutput = false;
        /** 고정 핀. 고정하지 않은 핀은 그 레시피를 한 번 만들기 시작하면 저절로 빠진다(2026-10-22 사용자) */
        public boolean locked = false;
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
        /** 개수를 세트(64개)로 보여 줌: 80개 → "1세트 16", 40개 → "40" */
        public boolean showSets = false;
        /** 제작 시설 창 옆에 가진 재료로 만들 수 있는 레시피 판(누르면 핀) */
        public boolean facilityPanel = true;
        /** 레시피 판 줄에 마우스를 올리면 시설 창에서 눌러야 할 칸(없으면 그 레시피가 있는 탭)을 강조. 누르는 건 사용자가 직접 */
        public boolean facilityHighlight = true;
    }

    /** 추적하는 장비 하나. key 는 ItemKeys 키, name 은 화면에 보일 이름 */
    public static final class TrackedItem {
        public String key = "";
        public String name = "";
        /** 마지막으로 찾았을 때의 아이템 종류(minecraft:diamond_helmet)와 입은 칸(36~39, 안 입었으면 -1).
         * 프레스티지 치장·크기 조절을 켜면 서버가 입은 갑옷의 이름·그림을 바꿔 보내서 이름으로는 못 찾는다 */
        public String itemId = "";
        public int wornSlot = -1;

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

    /** 쉬프트 우클릭 전문가 스킬의 지속·재사용 대기 시간 HUD */
    public static final class Skills {
        public boolean enabled = true;
        public Corner corner = Corner.BOTTOM_RIGHT;
    }

    /** 할 일 초기화 시점: 매일 03시, 매주 수요일 03시, 매일 자정 */
    public enum Reset { DAILY, WEEKLY, MIDNIGHT }

    public static final class TodoItem {
        public String name = "";
        public Reset reset = Reset.DAILY;
        /** 목표 횟수. 1 이면 체크 하나짜리, 10 이면 [0/10] 처럼 센다 */
        public int target = 1;
        /** 지금까지 한 횟수. doneAt 이 마지막 초기화보다 이전이면 0 으로 본다 */
        public int progress;
        /** 마지막으로 세거나 체크한 시각(ms) */
        public long doneAt;
        /** 채팅에 이 문구가 뜨면 알아서 +1 (비워 두면 쓰지 않음) */
        public String autoPhrase = "";
        /** 어떤 할 일인지(TodoPreset.id) */
        public String preset = "";
        /** 의뢰: 마지막으로 의뢰 창을 보고 맞춘 시각(ms). 이번 초기화 뒤로 안 봤으면 개수를 모른다 */
        public long windowSeenAt;

        public TodoItem() {}

        public TodoItem(String name, Reset reset) {
            this.name = name;
            this.reset = reset;
        }

        public TodoItem(String name, Reset reset, int target) {
            this(name, reset);
            this.target = target;
        }
    }

    public static final class Todo {
        public boolean hudEnabled = false;
        public Corner corner = Corner.TOP_RIGHT;
        /** 일일 초기화 몇 분 전에 남은 할 일을 알려 줄지 (0 = 알리지 않음) */
        public int remindMinutes = 60;
        /** 일일·주간 의뢰 줄 밑에 의뢰 내용(오른쪽 의뢰 트래커의 "레드스톤 채광하기 (0/8)")을 펼쳐 보기(문의 43c24382) */
        public boolean questDetails = true;
        public List<TodoItem> items = new ArrayList<>(defaults());
        /** 3 = 할 일은 모드가 알아서 셀 수 있는 것(TodoPreset)만. 예전 설정 파일에는 없어서 0 으로 읽히고, 한 번 정리한다 */
        public int version = 0;

        /** 기본 할 일: 알아서 셀 수 있는 것 전부(TodoPreset) */
        public static List<TodoItem> defaults() {
            List<TodoItem> out = new ArrayList<>();
            for (kr.ddingmate.todo.TodoPreset p : kr.ddingmate.todo.TodoPreset.values()) out.add(p.create());
            return out;
        }
    }

    public static final class Craft {
        /** 제작·가공 시설에서 만드는 아이템의 완료까지 남은 시간 세기 */
        public boolean enabled = true;
        /** 다 되면 소리·팝업으로 알림 */
        public boolean notify = true;
        /** 일정 HUD 에 제작 중인 아이템과 남은 시간 표시 */
        public boolean showInHud = true;
        /**
         * 같은 아이템을 여러 번 넣었을 때 먼저 끝난 것을 "[1] 완료"로 따로 빼지 않고, 전부 끝날 때까지 한 줄로 두었다가
         * "전부 완료!"로 보여 줌(2026-10-22 사용자 요청)
         */
        public boolean mergeDone = true;
        /** 제작 시간을 줄여 주는 전문가 스킬 레벨(0 = 안 배움, 1~5). 위키 기준 */
        public int mining;   // 채광 · 초고속 용광로: 채광물 가공·강화 제작 시설
        public int farming;  // 재배 · 불 더 올려!: 농작물 가공·요리 제작 시설
        public int marine;   // 해양 · 물 흐르듯 술술: 해양 제작·연금 제작 시설
        public int hunting;  // 사냥 · 고속 영혼 가공기: 영혼 가공 시설
    }

    /** 전문가 탭: 바리스타 주문 도우미·요리 효율 */
    public static final class Expert {
        /** 손님 주문 창 옆에 원하는 커피·가진 잔 수·판매 합계 판 */
        public boolean baristaPanel = true;
        /** "[세레니티] 커피값이 밥값" 스킬의 커피 판매가 증가(%) — 전문가 창에서 자동으로 읽음 */
        public int coffeeBonus;
        /** 요리 효율 순위 기준: true = 시간당 골드, false = 원재료당 골드 */
        public boolean cookingByTime = false;
        /** 밀키 요리 상점 창 옆에 효율·비싼 요리·가진 요리 판매 합계 판 */
        public boolean cookingPanel = true;
        /** 요리 시세가 바뀌면(1·3·6…일 03시) 채팅으로 알리기. 접속해 있지 않았으면 다음 접속 때 한 번 */
        public boolean cookingChangeChat = true;
        /** 마지막으로 알린 요리 시세 변동 시각(ms) */
        public long cookingNotifiedAt = 0;
        /** 판매품 상점(로니·샤키·포스·픽스·카이) 창 옆에 보유 판매품과 팔면 받는 금액 판 */
        public boolean shopPanel = true;
        /** 반려어(수족관) 밥 타이머: 어항 창의 허기·기분·활력을 읽어 밥 줄 때·폐사 위험·다 컸을 때 알림 */
        public boolean petFish = true;
        /** 반려어 밥 주기(분). 0 = 자동(위키 규칙으로 스탯 평균이 80% 아래로 내려갈 때). 그 밖엔 어항을 마지막으로 연 때부터 이만큼 뒤 */
        public int petFishFeedMin = 0;
        /** 해양 탐사: 출항 준비 중인 탐사선의 남은 재료를 일정 HUD 에(출항 중인 탐사선 남은 시간은 제작 줄로 늘 보임) */
        public boolean exploration = true;
        /** 오션오더 타이머: 도착하면 5분 남은 시간과 오늘 받은 횟수(하루 한도: oceanLimit) */
        public boolean oceanOrder = true;
        /** 광석 골렘 출현 쿨타임(5분, 프레스티지 1분)을 일정 HUD 에 */
        public boolean oreGolem = true;
        /**
         * 마을 경작지 아일랜드 작물을 주변 블록(리소스팩의 트립와이어 단계)으로 자동으로 읽기. 실제 마을에서 아직 확인 전이라
         * 기본은 끔(실험). 단축키 타이머(cropHotkey)가 기본
         */
        public boolean islandCrops = false;
        /** 아일랜드 작물 15분 타이머 단축키: GLFW 키 번호 1~3개(함께 눌렀을 때). 비어 있으면 단축키 없음 */
        public List<Integer> cropHotkey = new ArrayList<>();
        /** 마을 밖에 있는 동안 작물 타이머를 멈춤(위키: 마을에 사람이 있을 때만 자람) */
        public boolean cropPauseAway = true;
        /** 판매가 전문가 스킬(이름 → 지금 증가율 %). /전문가 스킬 창에서 자동으로 읽음 */
        public Map<String, Double> priceSkills = new LinkedHashMap<>();
        /** 오션오더 하루 한도(기본 10, 해양 스킬 "오션오더 더 더!" Lv1~5 → 12·14·16·18·20). /전문가 스킬 창이나 서버 "[오션오더] n/m" 에서 읽음 */
        public int oceanLimit = 10;
    }

    public static final class Playtime {
        /** 접속 1·2·3·4·5·8시간을 넘길 때마다 보상을 받으라고 알림 */
        public boolean enabled = true;
        /** 일정 HUD 맨 위에 오늘 접속 시간과 다음 보상까지 남은 시간 표시 */
        public boolean showInHud = true;
        /** 23:30 에 "자정에 초기화돼요" 알림 */
        public boolean midnightReminder = true;
    }

    /**
     * 띵메이트 글꼴(0.3.2, 사용자 문의 "갈무리가 잘 안 읽힌다"). 1920×1080 GUI 2·3·4 에서 같은 문구를 비교해 셋만 남김:
     * 갈무리11·14 는 획이 가늘어 더 흐렸고 GUI 2 에서 글자가 깨졌다. 모두 OFL 글꼴
     */
    public enum FontStyle {
        /** 온글잎 손글씨(2026-10-22 사용자 요청, 온글잎 라이선스: SW 탑재·배포 가능, 수정 불가 → 파일 그대로) */
        GALMURI("갈무리", ""), REDIO("레디오 볼펜체", "_redio"), BERRY("베리려원", "_berry"),
        /** 온글잎 호주나리체(사용자가 준 파일), 그리운 몽토리체·묘은또박체(그리운: 모든 사용 범위 가능, 개작·수정·판매 금지 → 파일 그대로) */
        NARI("호주나리체", "_nari"), MONGTORI("몽토리체", "_mongtori"), DDOBAK("묘은또박체", "_ddobak"),
        /** G마켓 산스(누구나 제약 없이 수정·재배포 가능, 유료 판매만 금지). 본문 Medium · 굵게 Bold */
        GMARKET("G마켓 산스", "_gmarket");

        public final String label;
        /** assets/ddingmate/font 의 body·bold·title 뒤에 붙는 이름 */
        public final String suffix;

        FontStyle(String label, String suffix) {
            this.label = label;
            this.suffix = suffix;
        }
    }

    /** DARK=서버 HUD 처럼 어두운 판, LIGHT=서버 창처럼 밝은 판, CLASSIC=예전 반투명 검은 상자 */
    public enum HudStyle { DARK, LIGHT, CLASSIC }

    /** 편집 화면에서 직접 옮긴 HUD 자리. 화면에서 남는 공간에 대한 비율(0~1) */
    public static final class HudPos {
        public float x;
        public float y;
    }

    public boolean hudEnabled = true;
    /** /띵메이트 창을 한 번이라도 열었는지. 열기 전에는 HUD 를 그리지 않고 채팅 안내만 한다 */
    public boolean opened = false;
    /** 처음 /띵메이트 를 열 때 나오는 튜토리얼을 끝냈거나 건너뛰었는지 */
    public boolean tutorialDone = false;
    /** 홈 탭의 "처음 사용 안내"를 숨겼는지 */
    public boolean guideSeen = false;
    /** 월드에 들어갈 때 "/띵메이트 로 열 수 있어요" 안내를 채팅에 한 번 남길지 */
    public boolean joinTip = true;
    /** HUD 이름(schedule·todo·counter·durability) → 옮긴 자리. 없으면 정해 둔 구석 */
    public Map<String, HudPos> hudPos = new LinkedHashMap<>();
    /** HUD 이름 → 편집 화면에서 정한 HUD 별 크기(%). 없으면 100 */
    public Map<String, Integer> hudSizes = new LinkedHashMap<>();
    /** HUD 이름 → 가로로 늘린 너비(HUD 좌표 px). 내용보다 좁으면 내용 너비 */
    public Map<String, Integer> hudWidths = new LinkedHashMap<>();
    /** 편집 화면에서 아래 가장자리로 정한 HUD 높이(HUD 좌표 px). 넘치는 줄은 옆 칸으로 */
    public Map<String, Integer> hudHeights = new LinkedHashMap<>();
    public HudStyle hudStyle = HudStyle.DARK;
    /** HUD 테마(색 한 벌). 없으면 예전 hudStyle 에서 고른다. 바꾸면 hudStyle 도 그 테마의 판 모양으로 */
    public kr.ddingmate.ui.HudTheme hudTheme;
    /** HUD 판: NPC 판 아이디(hud_skins.json, 예: "hani"). 없으면 위 HUD 테마 판 */
    public String hudPanel;
    /** HUD 제목 줄: "auto"(판에 맞춤) · "plain"(띠 없이 글자만) · NPC 아이디 */
    public String hudHeader = kr.ddingmate.ui.HudSkins.AUTO;
    /** 판 자동 바꾸기: "off" · "region"(지역) · "season"(계절·행사) · "expert"(켜진 전문가 스킬) · "time"(게임 시간) */
    public String hudAuto = "off";
    /** HUD 친구(판 윗변에 기대는 NPC 그림): "none" · NPC 아이디 */
    public String hudMascot = kr.ddingmate.ui.HudSkins.NONE;
    /** 띵메이트 창·HUD·알림 글꼴 */
    public FontStyle font = FontStyle.GALMURI;
    /** 고른 글꼴을 게임 전체(채팅·아이템 이름·메뉴 등 기본 글꼴로 그리는 글자)에도. 서버 아이콘·창 그림은 그대로 */
    public boolean fontEverywhere = false;
    /**
     * 내가 넣은 글꼴(config/ddingmate/fonts) 키 또는 {@link kr.ddingmate.ui.UserFonts#GAME}(게임 기본 글꼴 따라가기).
     * 비어 있으면 위의 font(띵메이트 글꼴 7종). 내 컴퓨터에서만 쓰인다
     */
    public String customFont = "";
    /** 내 글꼴 크기 70~130 (%) */
    public int userFontSize = 100;
    /** 새 버전으로 처음 켤 때 바뀐 점 창을 보여 줄지 */
    public boolean updateNotes = true;
    /** 바뀐 점 창을 마지막으로 보여 준(또는 처음 설치한) 띵메이트 버전. 비어 있으면 아직 모름 */
    public String notesSeenVersion = "";
    /** 바닥 아이템 청소까지 남은 시간을 일정 HUD 에(서버 [청소] 안내로 맞춤) */
    public boolean cleanupTimer = true;
    /** 펫 전부 소환 해제까지 남은 시간을 일정 HUD 에(서버 "[초기화] N분 후 펫이 전부 소환 해제됩니다." 안내로 맞춤) */
    public boolean petResetTimer = true;
    /** 서버 창 옆 판(보유 재료로 만들기·카페·부엌·상점·보석) 크기 50~150 (%). 판 위에서 Ctrl+휠로도 바꾼다 */
    public int sidePanelScale = 100;
    /** 서버 창 옆 판도 HUD 꾸미기(판·제목 줄·글자색)를 따라갈지. 끄면 창에 맞춘 색(카페 갈색·부엌 토마토색 등) */
    public boolean sidePanelFollowHud = false;
    /** HUD 판 진하기 30~100 (%) */
    public int hudOpacity = 85;
    /** HUD 크기 50~100 (%) */
    public int hudScale = 100;
    /** 서버 HUD(위쪽 재화 바·날짜/채널, 왼쪽 아래 채팅창)를 피해서 구석 HUD 를 놓음 */
    public boolean avoidServerHud = true;
    /** 예전 판의 글꼴 설정(지금은 늘 갈무리 글꼴, 끄는 설정 없음). 예전 설정 파일을 읽을 때만 남겨 둔다 */
    public boolean galmuriFont = true;
    public Corner hudCorner = Corner.TOP_LEFT;
    public int hudLines = 4;
    /** HUD 위치 설정 화면 아래 조작 바 진하기 20~100 (%). 바 아래(가운데 아래)에 HUD 를 둘 때 낮춘다 */
    public int editorBarOpacity = 100;
    /** 일정 HUD 의 제작·작물·오션오더 줄 앞에 분류([채광] · [강화] · [농사] …) */
    public boolean hudCategory = true;
    /** 일정 HUD '보일 일정 수'의 '제한 없음'(켜 둔 일정 모두) */
    public static final int HUD_LINES_ALL = 99;
    public boolean soundEnabled = true;
    public float soundVolume = 1.0f;
    public boolean toastEnabled = true;
    public boolean chatEnabled = true;
    /** 화면 오른쪽 위 채널 표시를 읽어 지역별 "내 채널"을 알아서 맞출지 */
    public boolean autoChannel = true;
    public int villageChannel = 0;
    public int huntChannel = 0;
    public int wildChannel = 0;
    public int islandChannel = 0;
    public int spawnChannel = 0;
    public Map<String, EventSetting> events = new LinkedHashMap<>();
    public Keywords keywords = new Keywords();
    public Counter counter = new Counter();
    public Durability durability = new Durability();
    public Skills skills = new Skills();
    public Expert expert = new Expert();
    public Todo todo = new Todo();
    public Craft craft = new Craft();
    public Playtime playtime = new Playtime();
    public Notices notices = new Notices();

    // ---------------------------------------------------------------- 저장·불러오기

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate.json");
    }

    public static DdingMateConfig load() {
        Path p = path();
        DdingMateConfig cfg = null;
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(r, DdingMateConfig.class);
            } catch (IOException | RuntimeException e) {
                DdingMate.LOG.warn("설정 파일을 읽지 못해 기본값을 씁니다: {}", p, e);
            }
        }
        if (cfg == null) cfg = new DdingMateConfig();
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
        if (hudLines != HUD_LINES_ALL) hudLines = Math.max(1, Math.min(10, hudLines));
        hudOpacity = Math.max(30, Math.min(100, hudOpacity));
        sidePanelScale = Math.max(kr.ddingmate.ui.SidePanel.MIN_SCALE, Math.min(kr.ddingmate.ui.SidePanel.MAX_SCALE,
                sidePanelScale <= 0 ? 100 : sidePanelScale));
        if (hudStyle == null) hudStyle = HudStyle.DARK;
        if (hudTheme == null) hudTheme = kr.ddingmate.ui.HudTheme.of(hudStyle);
        hudStyle = hudTheme.style;
        // NPC 판·제목 줄·친구: 모르는 아이디(지운 세트 등)는 기본으로, 글자 밝기는 NPC 판에 맞춰
        if (hudPanel != null && kr.ddingmate.ui.HudSkins.byId(hudPanel) == null) hudPanel = null;
        kr.ddingmate.ui.HudSkins.Skin panelSkin = kr.ddingmate.ui.HudSkins.byId(hudPanel);
        if (panelSkin != null) hudStyle = panelSkin.dark() ? HudStyle.DARK : HudStyle.LIGHT;
        if (hudHeader == null || !(kr.ddingmate.ui.HudSkins.AUTO.equals(hudHeader) || kr.ddingmate.ui.HudSkins.PLAIN.equals(hudHeader)
                || kr.ddingmate.ui.HudSkins.byId(hudHeader) != null)) hudHeader = kr.ddingmate.ui.HudSkins.AUTO;
        if (hudMascot == null || kr.ddingmate.ui.HudSkins.byId(hudMascot) == null) hudMascot = kr.ddingmate.ui.HudSkins.NONE;
        if (kr.ddingmate.ui.HudAuto.Mode.of(hudAuto) == null) hudAuto = "off";
        if (customFont == null) customFont = "";
        editorBarOpacity = Math.max(20, Math.min(100, editorBarOpacity <= 0 ? 100 : editorBarOpacity));
        userFontSize = Math.max(70, Math.min(130, userFontSize <= 0 ? 100 : userFontSize));
        if (notesSeenVersion == null) notesSeenVersion = "";
        if (notices == null) notices = new Notices();
        if (notices.seen == null) notices.seen = new ArrayList<>();
        while (notices.seen.size() > 50) notices.seen.remove(0);
        if (font == null) font = FontStyle.GALMURI; // 빼 버린 글꼴(프리텐다드·네오둥근모)을 골라 두었던 설정은 갈무리로
        if (expert != null && expert.cropHotkey == null) expert.cropHotkey = new ArrayList<>();
        hudScale = Math.max(50, Math.min(100, hudScale));
        if (hudPos == null) hudPos = new LinkedHashMap<>();
        if (hudSizes == null) hudSizes = new LinkedHashMap<>();
        if (hudWidths == null) hudWidths = new LinkedHashMap<>();
        hudWidths.values().removeIf(v -> v == null || v <= 0);
        if (hudHeights == null) hudHeights = new LinkedHashMap<>();
        if (expert != null && expert.priceSkills == null) expert.priceSkills = new LinkedHashMap<>();
        if (expert != null && (expert.oceanLimit < 10 || expert.oceanLimit > 30)) expert.oceanLimit = 10;
        hudHeights.values().removeIf(v -> v == null || v <= 0);
        hudSizes.values().removeIf(java.util.Objects::isNull);
        hudSizes.replaceAll((k, v) -> Math.max(50, Math.min(150, v)));
        hudPos.values().removeIf(java.util.Objects::isNull);
        for (HudPos p : hudPos.values()) {
            p.x = Math.max(0f, Math.min(1f, p.x));
            p.y = Math.max(0f, Math.min(1f, p.y));
        }
        soundVolume = Math.max(0f, Math.min(1f, soundVolume));
        if (events == null) events = new LinkedHashMap<>();
        if (keywords == null) keywords = new Keywords();
        if (keywords.words == null) keywords.words = new ArrayList<>();
        if (keywords.excludes == null) keywords.excludes = new ArrayList<>();
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
            // 위키 2026-10-15 이름 수정: 활기찬 가죽 지갑 → 장갑
            if (p.recipe.equals("가죽 공방/활기찬 가죽 지갑")) p.recipe = "가죽 공방/활기찬 가죽 장갑";
            // 게임 레시피북 표기(2026-10-16 확인): 그린트리 → 그린 트리 프라페
            if (p.recipe.equals("바리스타 · 커피 머신/그린트리 프라페")) p.recipe = "바리스타 · 커피 머신/그린 트리 프라페";
            // 위키에서 옮길 때 섞여 들어온 보이지 않는 글자(BOM)를 레시피 이름에서 지웠다(형광 광산·모루 모듈)
            p.recipe = p.recipe.replace("\uFEFF", "");
            p.crafts = Math.max(1, Math.min(9999, p.crafts));
        }
        if (durability == null) durability = new Durability();
        if (durability.corner == null) durability.corner = Corner.BOTTOM_RIGHT;
        if (durability.tracked == null) durability.tracked = new ArrayList<>();
        durability.tracked.removeIf(t -> t == null || t.key == null || t.key.isBlank());
        // 이름 규칙(서버 그림 글자 지우기)이 바뀌기 전에 저장한 장비는 키·이름을 다시 만들고, 같은 장비가 둘이면 하나만 남긴다
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        durability.tracked.removeIf(t -> {
            t.key = kr.ddingmate.counter.ItemKeys.rekey(t.key);
            t.name = t.name == null ? "" : kr.ddingmate.counter.ItemKeys.stripGlyphs(t.name).trim();
            return !seenKeys.add(t.key);
        });
        durability.warnPercent = Math.max(0, Math.min(90, durability.warnPercent));
        if (skills == null) skills = new Skills();
        if (expert == null) expert = new Expert();
        expert.coffeeBonus = Math.max(0, Math.min(100, expert.coffeeBonus));
        if (skills.corner == null) skills.corner = Corner.BOTTOM_RIGHT;
        if (todo == null) todo = new Todo();
        if (todo.corner == null) todo.corner = Corner.TOP_RIGHT;
        if (todo.items == null) todo.items = new ArrayList<>();
        todo.items.removeIf(t -> t == null || t.name == null || t.name.isBlank());
        if (todo.version < 3) {
            // 알아서 셀 수 있는 것만 남긴다: 예전 이름을 해당 항목으로 바꾸고(센 값 유지), 나머지(출석 체크·직접 만든 것)는 뺀다
            List<TodoItem> kept = new ArrayList<>();
            for (TodoItem t : todo.items) {
                kr.ddingmate.todo.TodoPreset p = kr.ddingmate.todo.TodoPreset.of(t);
                if (p == null) p = kr.ddingmate.todo.TodoPreset.byOldName(t.name);
                if (p == null) continue;
                kr.ddingmate.todo.TodoPreset found = p;
                if (kept.stream().anyMatch(k -> found.id.equals(k.preset))) continue;
                t.preset = p.id;
                kept.add(t);
            }
            if (todo.version == 0 && kept.isEmpty()) kept.addAll(Todo.defaults());
            todo.items = kept;
            todo.version = 3;
        }
        if (todo.version < 4) {
            // 0.3.2: 스태미나 소모하기(3,000) 추가 — 이미 쓰던 사람 목록에도 넣는다(빼고 싶으면 할 일 탭에서 빼기)
            if (todo.items.stream().noneMatch(t -> kr.ddingmate.todo.TodoPreset.STAMINA.id.equals(t.preset))) {
                todo.items.add(kr.ddingmate.todo.TodoPreset.STAMINA.create());
            }
            todo.version = 4;
        }
        // 할 일은 직접 고치지 않는다: 이름·초기화·목표·문구는 늘 정해진 값
        todo.items.removeIf(t -> kr.ddingmate.todo.TodoPreset.of(t) == null);
        java.util.Set<String> presetSeen = new java.util.HashSet<>();
        todo.items.removeIf(t -> !presetSeen.add(t.preset));
        for (TodoItem t : todo.items) {
            kr.ddingmate.todo.TodoPreset p = kr.ddingmate.todo.TodoPreset.of(t);
            t.name = p.label;
            t.reset = p.reset;
            t.target = p.target;
            t.autoPhrase = p.phrase;
            // 예전 설정(체크만 있던 때): 체크 시각만 있으면 다 한 것으로
            if (t.progress <= 0 && t.doneAt > 0 && t.target == 1) t.progress = 1;
            t.progress = Math.max(0, Math.min(t.target, t.progress));
        }
        todo.remindMinutes = Math.max(0, Math.min(600, todo.remindMinutes));
        if (playtime == null) playtime = new Playtime();
        if (craft == null) craft = new Craft();
        craft.mining = Math.max(0, Math.min(5, craft.mining));
        craft.farming = Math.max(0, Math.min(5, craft.farming));
        craft.marine = Math.max(0, Math.min(5, craft.marine));
        craft.hunting = Math.max(0, Math.min(5, craft.hunting));
        // 0.3.0 까지 있던 근거 없는 야생·아일랜드·스폰 재시작 일정 설정은 지운다
        events.keySet().removeAll(List.of("wild_reboot", "island_reboot", "spawn_reboot", "serenity_reset", "hunt_reboot", "maintenance_end"));
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
