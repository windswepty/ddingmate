package kr.ddingmate.skill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 쉬프트 우클릭으로 쓰는 전문가 스킬(곡괭이 나가신다!·쓸어담는 광질·떡밥을 뿌려라! 등)의 지속 시간과 재사용 대기 시간.
 * <p>
 * 근거(2026-05~10 실제 로그·창):
 * <ul>
 *   <li>전문가 스킬 창 칸 "[야생] 곡괭이 나가신다! (+5)" 설명: ". 15초 간 3x3를 부실 수 있습니다." "..(재사용 대기 시간 : 180초)",
 *       그 아래 "────" 부터는 다음 레벨 미리보기</li>
 *   <li>채팅: "X 스킬을 활성화했습니다." → (지속 시간이 있는 스킬만) "X 스킬 지속시간이 종료되었습니다."
 *       → 다시 쓰려 하면 "X 스킬의 쿨타임이 2분 59초 남았습니다." → "X 스킬을 사용할 수 있습니다."</li>
 *   <li>재사용 대기는 활성화한 순간부터 센다(활성화 14:54:58 → 사용 가능 14:59:58, 300초).</li>
 *   <li>창에 적힌 지속 시간과 실제가 다를 수 있다(쓸어담는 광질 +1: 창 3초, 실제 5초) → 끝났다는 메시지로 실제 시간을 배운다.</li>
 * </ul>
 * 서버에 아무것도 보내지 않고, 받은 메시지와 내가 연 창만 읽는다.
 */
public final class SkillTimers {

    private SkillTimers() {}

    static final Pattern ACTIVATED = Pattern.compile("^([^:]+?)\\s*스킬을\\s*활성화했습니다");
    static final Pattern ENDED = Pattern.compile("^([^:]+?)\\s*스킬\\s*지속\\s*시간이\\s*종료되었습니다");
    static final Pattern COOLDOWN = Pattern.compile(
            "^([^:]+?)\\s*스킬의\\s*쿨\\s*타임이\\s*(?:(\\d+)\\s*시간\\s*)?(?:(\\d+)\\s*분\\s*)?(\\d+)\\s*초\\s*남았습니다");
    static final Pattern READY = Pattern.compile("^([^:]+?)\\s*스킬을\\s*사용할\\s*수\\s*있습니다");

    /** 스킬 창 설명: "15초 간 …", "(재사용 대기 시간 : 180초)" */
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*초\\s*간");
    private static final Pattern COOL_LORE = Pattern.compile("재사용\\s*대기\\s*시간\\s*:?\\s*(?:(\\d+)\\s*분\\s*)?(?:(\\d+)\\s*초)?");
    private static final Pattern ACTIVE_USE = Pattern.compile("쉬프트\\s*우\\s*클릭");
    private static final Pattern PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시");
    private static final Pattern LEVEL = Pattern.compile("\\(\\s*\\+?\\s*(\\d+)\\s*\\)|\\(\\s*MAX\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** "사용 가능"을 보여 주는 시간 */
    static final long READY_SHOW_MS = 5_000;
    /** 창에 적힌 시간이 지났는데 끝났다는 메시지가 아직 없을 때 "사용 중"으로 더 기다려 주는 시간 */
    static final long END_GRACE_MS = 10_000;
    /** 지속 시간을 모르는 스킬을 "사용 중"으로 두는 최대 시간(종료 메시지를 기다림) */
    static final long UNKNOWN_ACTIVE_MS = 5 * 60_000L;
    /** 재사용 시간을 모르는 스킬을 "대기 중"으로 두는 최대 시간 */
    static final long UNKNOWN_COOLDOWN_MS = 60 * 60_000L;

    /** 스킬 하나의 정보와 지금 상태 */
    public static final class Skill {
        public String name = "";
        /** 창에서 읽은 레벨(MAX 는 99). 0 = 아직 창을 못 봄 */
        public int level;
        /** 창에 적힌 지속 시간(초). 0 = 지속 시간이 없거나 모름 */
        public int durationSec;
        /** 창에 적힌 재사용 대기 시간(초). 0 = 모름 */
        public int cooldownSec;
        /**
         * 활성화 → 종료 메시지까지 잰 시간 중 가장 짧은 것(초)과 그때의 레벨. 종료 메시지는 시간이 다 된 뒤 다음 블록을 캘 때
         * 와서 실제보다 길게 잡히므로, 스킬 창을 못 봤을 때만 쓴다
         */
        public int measuredSec;
        public int measuredLevel = -1;
        /** 지속 시간이 끝나면 서버가 알려 주는 스킬인가(한 번이라도 종료 메시지를 봤으면 true) */
        public boolean endMessage;
        /** 활성화 시각, 지속이 끝나는 시각(0 = 끝남), 다시 쓸 수 있는 시각(0 = 모름·없음) */
        public long activatedAt;
        public long activeUntil;
        public long readyAt;
        /** 재사용 시간을 몰라(창을 못 봄) 언제 끝날지 모르는 채로 기다리는 중 */
        public boolean cooldownPending;
        /** 서버가 직접 알려 준 남은 쿨타임으로 맞췄는지 */
        public boolean readySynced;
        /** "사용 가능"을 이때까지 보여 준다 */
        public long readyShownUntil;
        /** 스킬 동안 서버가 거는 상태 효과(짝지었으면 id). 효과가 남은 시간이 곧 정확한 지속 시간 */
        public String effect = "";
        /** 스킬을 쓰면 서버가 거는 아이템 쿨타임 묶음(짝지었으면 id). 그 길이가 곧 정확한 재사용 대기 */
        public String cooldownGroup = "";
        /** 지금 "사용 중" 끝 시각이 서버 효과로 맞춘 정확한 값인지 */
        public transient boolean activeExact;
        /** 도구 종류(아이콘): pickaxe·hoe·rod·sword·axe */
        public String tool = "";
    }

    private static final class Store {
        int version = 1;
        Map<String, Map<String, Skill>> accounts = new LinkedHashMap<>();
    }

    private static Store store = new Store();
    private static boolean dirty;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ---------------------------------------------------------------- 채팅

    public static void onChat(String message) {
        if (message == null) return;
        String t = Formatting.strip(message);
        if (t == null) return;
        t = ItemKeys.stripGlyphs(t).trim();
        if (kr.ddingmate.game.ServerText.fromPlayer(t)) return; // 사람이 쓴 채팅·귓속말은 무시
        onChat(t, System.currentTimeMillis());
    }

    static void onChat(String t, long now) {
        Matcher m = ACTIVATED.matcher(t);
        if (m.find()) {
            activated(m.group(1), now);
            return;
        }
        m = ENDED.matcher(t);
        if (m.find()) {
            ended(m.group(1), now);
            return;
        }
        m = COOLDOWN.matcher(t);
        if (m.find()) {
            long sec = parse(m.group(2)) * 3600 + parse(m.group(3)) * 60 + parse(m.group(4));
            cooldownLeft(m.group(1), sec, now);
            return;
        }
        m = READY.matcher(t);
        if (m.find()) ready(m.group(1), now);
    }

    private static void activated(String rawName, long now) {
        Skill s = skillForWrite(rawName);
        if (s == null) return;
        s.activatedAt = now;
        long dur = durationMs(s);
        // 지속 시간을 모르면(창을 못 봄·잰 적 없음) 끝났다는 메시지가 오는 스킬일 때만 "사용 중"으로 기다린다
        s.activeUntil = dur > 0 ? now + dur : (s.endMessage ? now + UNKNOWN_ACTIVE_MS : 0);
        // 재사용 대기는 활성화한 순간부터 센다
        s.readyAt = s.cooldownSec > 0 ? now + s.cooldownSec * 1000L : 0;
        s.cooldownPending = s.cooldownSec <= 0;
        s.readySynced = false;
        s.readyShownUntil = 0;
        s.activeExact = false;
        SkillSignals.activated(s, now); // 서버가 함께 보내는 효과·아이템 쿨타임으로 더 정확히 맞춘다
        changed();
    }

    /** SkillSignals 가 시각을 바꿨을 때 저장 */
    static void touched() {
        changed();
    }

    /** 지금 쓰는 중이거나 방금 켠 스킬 */
    static List<Skill> activeSkills() {
        Map<String, Skill> map = skills(false);
        if (map == null) return List.of();
        long now = System.currentTimeMillis();
        List<Skill> out = new ArrayList<>();
        for (Skill s : map.values()) {
            if (s.activatedAt > 0 && (s.activeUntil > now || now - s.activatedAt <= SkillSignals.WATCH_MS)) out.add(s);
        }
        return out;
    }

    private static void ended(String rawName, long now) {
        Skill s = skillForWrite(rawName);
        if (s == null) return;
        s.endMessage = true;
        if (s.activeExact && s.activeUntil > now) {
            changed();
            return; // 서버 효과가 아직 남아 있으면 그쪽이 정확하다
        }
        if (s.activatedAt > 0 && now - s.activatedAt < 10 * 60_000L) {
            int m = (int) Math.round((now - s.activatedAt) / 1000.0);
            // 창 값보다 짧거나 MAX_LATE_SEC 넘게 긴 것은 버린다: 종료 메시지가 늦게 오기도 한다
            // (2026-10-10 실측: 창 15초인데 활성화 20:09:17 → 종료 메시지 20:09:56, 39초)
            if (plausible(s, m)) {
                if (s.measuredLevel != s.level || s.measuredSec <= 0 || m < s.measuredSec) s.measuredSec = m;
                s.measuredLevel = s.level;
            }
        }
        s.activeUntil = 0;
        changed();
    }

    private static void cooldownLeft(String rawName, long sec, long now) {
        Skill s = skillForWrite(rawName);
        if (s == null) return;
        // 서버는 남은 시간을 초 단위로 버려서 알려 준다(활성화 직후 "2분 59초"). 진짜 남은 시간은 [sec, sec+1) 초
        long lo = now + sec * 1000L, hi = lo + 1000L;
        if (s.readyAt < lo || s.readyAt > hi) s.readyAt = lo + 500L;
        s.readySynced = true;
        s.cooldownPending = false;
        s.readyShownUntil = 0;
        changed();
    }

    private static void ready(String rawName, long now) {
        Skill s = skillForWrite(rawName);
        if (s == null) return;
        // 창을 못 봐 재사용 시간을 몰랐다면 활성화부터 지금까지로 배운다
        if (s.cooldownSec == 0 && s.activatedAt > 0 && now - s.activatedAt < 3 * 3600_000L) {
            s.cooldownSec = (int) Math.round((now - s.activatedAt) / 1000.0);
        }
        s.readyAt = 0;
        s.readySynced = false;
        s.cooldownPending = false;
        s.readyShownUntil = now + READY_SHOW_MS;
        changed();
    }

    // ---------------------------------------------------------------- 스킬 창

    /** 전문가 스킬 창의 칸 하나. 쉬프트 우클릭 스킬이면 레벨·지속·재사용 시간을 기억한다. 읽었으면 true */
    public static boolean read(ItemStack stack) {
        String name = ItemKeys.stripGlyphs(stack.getName().getString()).trim();
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        List<String> lines = new ArrayList<>();
        for (Text t : lore.lines()) lines.add(ItemKeys.stripGlyphs(t.getString()));
        return read(name, lines);
    }

    static boolean read(String name, List<String> lore) {
        // 구분선 위(지금 효과)만 본다
        StringBuilder now = new StringBuilder();
        for (String l : lore) {
            if (PREVIEW.matcher(l).find()) break;
            now.append(' ').append(l.replaceFirst("^[.\\s]+", ""));
        }
        String text = now.toString();
        Matcher c = COOL_LORE.matcher(text);
        // 쉬프트 우클릭으로 직접 쓰는 스킬만(확률로 저절로 터지는 스킬은 메시지가 없어 셀 수 없다)
        if (!ACTIVE_USE.matcher(text).find()) return false;
        if (!c.find() || (c.group(1) == null && c.group(2) == null)) return false;
        int cooldown = (int) (parse(c.group(1)) * 60 + parse(c.group(2)));
        Matcher lv = LEVEL.matcher(name);
        int level = 0;
        if (lv.find()) level = lv.group(1) != null ? Integer.parseInt(lv.group(1)) : 99;
        String clean = displayName(name);
        if (clean.isEmpty()) return false;
        Skill s = skillForWrite(clean);
        if (s == null) return false;
        Matcher d = DURATION.matcher(text);
        int duration = d.find() ? Integer.parseInt(d.group(1)) : 0;
        String tool = text.contains("곡괭이") ? "pickaxe" : text.contains("괭이") ? "hoe" : text.contains("낚싯대") ? "rod"
                : text.contains("도끼") ? "axe" : text.contains("검") ? "sword" : "";
        boolean changed = s.level != level || s.durationSec != duration || s.cooldownSec != cooldown || !s.tool.equals(tool);
        if (s.level != level || s.durationSec != duration) {
            // 레벨이나 창 값이 바뀌면 전에 잰 지속 시간은 버린다
            s.measuredSec = 0;
            s.measuredLevel = -1;
        }
        s.level = level;
        s.durationSec = duration;
        s.cooldownSec = cooldown;
        s.tool = tool;
        if (changed) changed();
        // 제작 시간 스킬처럼, 이번 접속에서 처음 읽었거나 값이 바뀌었으면 채팅으로 한 번 알려 준다
        if (CONFIRMED.add(s.name) || changed) {
            long dur = durationMs(s) / 1000;
            String what = (dur > 0 ? "지속 " + clock(dur) + " · " : "") + "재사용 " + clock(cooldown);
            kr.ddingmate.notify.Notifier.chat("전문가 스킬 '" + s.name + "'" + levelText(level) + " " + what
                    + (changed ? " 을 스킬 타이머에 반영했어요." : " 을 확인했어요(이미 반영됨)."));
        }
        return true;
    }

    /** 이번 접속에서 이미 읽고 알려 준 스킬 */
    private static final java.util.Set<String> CONFIRMED = new java.util.HashSet<>();

    private static String levelText(int level) {
        return level >= 99 ? " (MAX)" : level > 0 ? " (+" + level + ")" : "";
    }

    /** "[야생] 곡괭이 나가신다! (+5)" → "곡괭이 나가신다!" */
    static String displayName(String name) {
        String t = ItemKeys.stripGlyphs(name).trim();
        t = t.replaceFirst("^(\\s*\\[[^\\]]*\\])+", "");
        t = LEVEL.matcher(t).replaceAll("");
        return t.replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- 보여 주기

    /** HUD 한 줄 */
    public record Row(Skill skill, Phase phase, long untilMs, long totalMs) {
        public String name() {
            return skill.name;
        }
    }

    public enum Phase { ACTIVE, COOLDOWN, READY }

    /** 지금 보여 줄 스킬들: 사용 중 → 재사용 대기(빨리 끝나는 순) → 사용 가능 */
    public static List<Row> rows(long now) {
        Map<String, Skill> map = skills(false);
        List<Row> out = new ArrayList<>();
        if (map == null) return out;
        boolean touched = false;
        for (Skill s : map.values()) {
            // 창에 적힌 지속 시간이 지나면 끝(종료 메시지는 늦게 올 수 있어 기다리지 않는다).
            // 지속 시간을 모르면 종료 메시지가 올 때까지(최대 5분) "사용 중"
            if (s.activeUntil > 0 && now >= s.activeUntil) {
                s.activeUntil = 0;
                touched = true;
            }
            if (s.readyAt > 0 && now >= s.readyAt + END_GRACE_MS) {
                // 서버가 "사용할 수 있습니다"를 보내지 않았어도(접속을 끊었다 들어옴 등) 시간이 지났으면 쓸 수 있다
                s.readyAt = 0;
                touched = true;
            }
            if (s.cooldownPending && now - s.activatedAt > UNKNOWN_COOLDOWN_MS) {
                s.cooldownPending = false;
                touched = true;
            }
            if (s.activeUntil > 0) {
                out.add(new Row(s, Phase.ACTIVE, s.activeUntil, durationMs(s)));
            } else if (s.cooldownPending) {
                out.add(new Row(s, Phase.COOLDOWN, 0, 0));
            } else if (s.readyAt > 0) {
                out.add(new Row(s, Phase.COOLDOWN, s.readyAt, s.cooldownSec * 1000L));
            } else if (s.readyShownUntil > now) {
                out.add(new Row(s, Phase.READY, s.readyShownUntil, READY_SHOW_MS));
            }
        }
        if (touched) changed();
        // 모르는 시간(0)은 같은 단계의 맨 뒤로
        out.sort((a, b) -> a.phase != b.phase ? a.phase.compareTo(b.phase)
                : Long.compare(a.untilMs == 0 ? Long.MAX_VALUE : a.untilMs, b.untilMs == 0 ? Long.MAX_VALUE : b.untilMs));
        return out;
    }

    /** 남은 시간 글자. 확실하지 않으면 숫자 대신 상태만 */
    public static String text(Row r, long now) {
        return switch (r.phase) {
            case READY -> "사용 가능";
            case ACTIVE -> {
                if (durationMs(r.skill) <= 0 || now >= r.untilMs) yield "사용 중";
                yield "사용 중 " + clock((r.untilMs - now + 999) / 1000);
            }
            // 서버와 같은 방식(초 아래 버림)으로 센다: 서버가 "쿨타임이 1분 36초 남았습니다"라고 할 때 HUD 도 1분 36초
            case COOLDOWN -> r.untilMs == 0 ? "스킬 창 열면 표시"
                    : now >= r.untilMs ? "곧 사용 가능" : clock((r.untilMs - now) / 1000);
        };
    }

    /** 0~1, 막대 길이(남은 비율). 모르면 -1 */
    public static double ratio(Row r, long now) {
        if (r.phase == Phase.READY || r.totalMs <= 0) return -1;
        return Math.max(0, Math.min(1, (r.untilMs - now) / (double) r.totalMs));
    }

    /** 125 → "2분 5초", 42 → "42초" */
    public static String clock(long sec) {
        sec = Math.max(0, sec);
        if (sec >= 3600) return (sec / 3600) + "시간 " + (sec % 3600) / 60 + "분";
        if (sec >= 60) return (sec / 60) + "분 " + (sec % 60) + "초";
        return sec + "초";
    }

    /** 도구 모양 아이콘 */
    public static ItemStack icon(Skill s) {
        return new ItemStack(switch (s.tool) {
            case "pickaxe" -> Items.NETHERITE_PICKAXE;
            case "hoe" -> Items.NETHERITE_HOE;
            case "rod" -> Items.FISHING_ROD;
            case "axe" -> Items.NETHERITE_AXE;
            case "sword" -> Items.NETHERITE_SWORD;
            default -> Items.EXPERIENCE_BOTTLE;
        });
    }

    /** 스킬 창에 적힌 지속 시간(ms). 모르면 0 */
    /**
     * 지속 시간(ms): 스킬 창에 적힌 값. 종료 메시지는 시간이 다 된 뒤 다음 블록을 캘 때 오므로 재지 않는다
     * (2026-10-10 실측: 전문가 창을 열어 채광을 멈춘 사이 39초·49초 뒤에 옴, 광석은 캐는 데 걸려 쓸어담는 광질 3초 → 5~7초).
     * 창을 못 봤을 때만 잰 값 중 가장 짧은 것을 쓴다. 모르면 0
     */
    public static long durationMs(Skill s) {
        if (s.durationSec > 0) return s.durationSec * 1000L;
        if (s.measuredSec > 0 && s.measuredLevel == s.level) return s.measuredSec * 1000L;
        return 0;
    }

    /** 잰 지속 시간이 창 값과 견줘 믿을 만한가: 창 값 이상, 창 값 + MAX_LATE_SEC 이하(창 값을 모르면 10분 안) */
    static boolean plausible(Skill s, int sec) {
        if (sec <= 0) return false;
        if (s.durationSec <= 0) return sec <= 600;
        return sec >= s.durationSec && sec <= s.durationSec + MAX_LATE_SEC;
    }

    /** 실제 지속 시간이 창 값보다 길 수 있는 한도(초). 쓸어담는 광질 3초 → 실측 5~7초 */
    static final int MAX_LATE_SEC = 5;

    /** 이 계정에서 지금까지 알게 된 스킬(설정 창 목록용), 이름순 */
    public static List<Skill> known() {
        Map<String, Skill> map = skills(false);
        if (map == null) return List.of();
        return map.values().stream().sorted((a, b) -> a.name.compareTo(b.name)).toList();
    }

    // ---------------------------------------------------------------- 저장

    private static Skill skillForWrite(String rawName) {
        String name = displayName(rawName);
        if (name.isEmpty() || name.length() > 40) return null;
        Map<String, Skill> map = skills(true);
        if (map == null) return null;
        String key = name.replaceAll("\\s+", "");
        Skill s = map.get(key);
        if (s == null) {
            s = new Skill();
            map.put(key, s);
        }
        s.name = name;
        return s;
    }

    private static Map<String, Skill> skills(boolean create) {
        String key = accountKey();
        if (key == null) return null;
        Map<String, Skill> map = store.accounts.get(key);
        if (map == null && create) {
            map = new LinkedHashMap<>();
            store.accounts.put(key, map);
        }
        return map;
    }

    private static String accountKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID id = client.player != null ? client.player.getUuid() : null;
        if (id == null) return null;
        ServerInfo server = client.getCurrentServerEntry();
        String where = server == null ? "singleplayer" : server.address.toLowerCase(Locale.ROOT);
        return id + "@" + where;
    }

    private static long parse(String s) {
        return s == null ? 0 : Long.parseLong(s);
    }

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
            s.accounts.values().removeIf(java.util.Objects::isNull);
            for (Map<String, Skill> m : s.accounts.values()) {
                m.values().removeIf(k -> k == null || k.name == null || k.name.isBlank());
                for (Skill k : m.values()) {
                    if (k.tool == null) k.tool = "";
                    if (k.effect == null) k.effect = "";
                    if (k.cooldownGroup == null) k.cooldownGroup = "";
                    // 예전 판이 늦은 종료 메시지로 잘못 잰 값(39초·49초 등)은 버린다
                    if (!plausible(k, k.measuredSec)) {
                        k.measuredSec = 0;
                        k.measuredLevel = -1;
                    }
                }
            }
            store = s;
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("스킬 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    public static void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(store, w);
            }
            dirty = false;
        } catch (IOException e) {
            DdingMate.LOG.error("스킬 기록 저장 실패", e);
        }
    }

    /** 시험용 */
    public static void debugClear() {
        CONFIRMED.clear();
        store = new Store();
        dirty = true;
    }

    /** 시험용: 지정한 시각에 메시지를 받은 것으로 */
    public static void debugChat(String message, long now) {
        onChat(ItemKeys.stripGlyphs(message).trim(), now);
    }

    /** 시험용 */
    public static Skill debugSkill(String name) {
        Map<String, Skill> map = skills(false);
        return map == null ? null : map.get(displayName(name).replaceAll("\\s+", ""));
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-skills.json");
    }
}
