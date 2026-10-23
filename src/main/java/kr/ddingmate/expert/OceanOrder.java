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
    /** 스킬 없이 하루 한도(위키). 지금 한도는 limit() */
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
    /**
     * 서버가 오션오더 중 띄우는 오늘 횟수 "[오션오더] 8/10"(2026-10-23 사용자 화면). 물고기 진행 "(2/2)"와는 분모로 가른다:
     * 물고기 요구량은 많아야 3마리·5종이고, 하루 한도는 10~20
     */
    private static final Pattern DAILY = Pattern.compile("오션오더\\s*]?\\s*:?\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** 해양 스킬 "오션오더 더 더!": 설명 "수령할 수 있는 오션오더의 일일제한이 12회로 증가합니다." */
    private static final Pattern SKILL_LIMIT = Pattern.compile("일일\\s*제한이\\s*(\\d+)\\s*회");
    private static final Pattern SKILL_PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시|다음\\s*레벨");

    /** 성공·실패 문구를 본 뒤: 서버 오션오더 HUD 가 아직 남아 있어도 새로 도착한 것으로 보지 않는다(2026-10-23 끝난 것을 또 셈) */
    private static boolean finished;
    private static String finishedHud = "";

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
        // 오늘 횟수 "[오션오더] 8/10": 서버 값으로 맞추고 물고기 진행으로는 쓰지 않는다
        Matcher daily = DAILY.matcher(t);
        if (daily.find() && Integer.parseInt(daily.group(2)) >= DAILY_MAX) {
            syncDaily(Integer.parseInt(daily.group(1)), Integer.parseInt(daily.group(2)));
            return;
        }
        // 끝 → 성공 → 도착 순서로 본다("오션오더 성공! 보상을 수령했습니다"를 도착으로 착각하지 않게)
        if (FAIL.matcher(t).find()) {
            if (active(now)) DdingMate.LOG.info("오션오더 끝(실패): {}", t);
            finish();
            return;
        }
        if (SUCCESS.matcher(t).find()) {
            finish();
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
        d.count = Math.min(limit(), d.count + 1);
        save();
        Notifier.oceanOrder("오션오더 도착!", (limitMs / 1000 / 60) + "분 " + (limitMs / 1000 % 60) + "초 안에 주문한 물고기를 낚아 주세요 · 오늘 "
                + d.count + "/" + limit());
    }

    private static void clear() {
        endsAt = 0;
        startedAt = 0;
        progress = "";
        lastChannel = -1;
    }

    /** 성공·실패로 끝남: 남아 있는 서버 HUD 로 다시 켜지지 않게 표시해 둔다 */
    private static void finish() {
        clear();
        finished = true;
        finishedHud = lastHud;
    }

    /** 서버가 알려 준 오늘 횟수·한도로 맞춘다 */
    private static void syncDaily(int count, int max) {
        boolean save = false;
        if (max >= DAILY_MAX && max <= 30 && DdingMate.config().expert.oceanLimit != max) {
            DdingMate.config().expert.oceanLimit = max;
            DdingMate.config().save();
            DdingMate.LOG.info("오션오더 하루 한도(서버): {}", max);
        }
        Day d = today();
        if (count >= 0 && count <= max && d.count != count) {
            DdingMate.LOG.info("오션오더 오늘 횟수(서버): {} → {}", d.count, count);
            d.count = count;
            save = true;
        }
        if (save) save();
    }

    /** 오늘 받을 수 있는 오션오더 수: 10, "오션오더 더 더!"를 배웠으면 12~20 */
    public static int limit() {
        return Math.max(DAILY_MAX, DdingMate.config().expert.oceanLimit);
    }

    // ---------------------------------------------------------------- 서버 오션오더 HUD(보스바)

    /** 오션오더 HUD 글꼴(서버 리소스팩 2026-10-21: 배경 hud_oceanorder_hud_image + 글자 7줄 hud_oceanorder_hud_text_N) */
    private static final String HUD_FONT = "hud_oceanorder_hud_";
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");
    private static boolean hudShown;
    private static long hudSeenAt;
    private static String lastHud = "";

    /** 보스바 글이 오션오더 HUD 면 그 글자들(글꼴 줄마다 " | "로), 아니면 null */
    public static String hudText(net.minecraft.text.Text name) {
        if (name == null) return null;
        java.util.Map<String, StringBuilder> lines = new java.util.LinkedHashMap<>();
        boolean[] any = {false};
        name.visit((style, s) -> {
            net.minecraft.util.Identifier f = style.getFont();
            if (f != null && f.getPath().startsWith(HUD_FONT)) {
                any[0] = true;
                if (f.getPath().contains("_text")) lines.computeIfAbsent(f.getPath(), k -> new StringBuilder()).append(s);
            }
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        if (!any[0]) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (StringBuilder sb : lines.values()) {
            String l = ItemKeys.stripGlyphs(sb.toString()).replaceAll("\\s+", " ").trim();
            if (!l.isEmpty()) out.add(l);
        }
        return String.join(" | ", out);
    }

    /**
     * 보스바 글에서 오션오더 HUD 글꼴 부분을 뺀 나머지. 띵타이쿤은 재화 바·날짜·채널 표시를 보스바 한 줄로 보내서
     * (2026-10-23 기록), 오션오더 HUD 가 같은 줄에 실리면 이걸로 채널 표시를 계속 읽어야 한다
     * (오션오더 중 채널 표시를 못 읽어 '게임 밖'으로 보고 띵메이트 HUD 가 모두 사라졌다)
     */
    public static String withoutHud(net.minecraft.text.Text name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        name.visit((style, s) -> {
            net.minecraft.util.Identifier f = style.getFont();
            if (f == null || !f.getPath().startsWith(HUD_FONT)) sb.append(s);
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return sb.toString();
    }

    /** 오션오더 HUD 가 보이면: 진행 중. 처음 보면 시작(도착 문구를 놓쳐도), 적힌 남은 시간·진행으로 맞춘다 */
    public static void onHud(String text, long now) {
        if (!DdingMate.config().expert.oceanOrder) return;
        if (!text.equals(lastHud)) {
            lastHud = text;
            DdingMate.LOG.info("오션오더 HUD: {}", text);
        }
        hudShown = true;
        hudSeenAt = now;
        long left = hudRemaining(text);
        if (finished) {
            // 끝난 뒤 남아 있는 HUD: 새 주문(글이 바뀌고 남은 시간이 거의 5분)일 때만 다시 센다
            if (text.equals(finishedHud) || left < LIMIT_MS - 15_000) return;
            finished = false;
        }
        if (!active(now)) start(now, left > 0 && left <= LIMIT_MS ? left : LIMIT_MS);
        else if (left > 0 && left <= LIMIT_MS) endsAt = now + left;
        // 물고기 줄마다 "(n/m)": 하나면 그대로, 여럿이면 다 잡은 줄 수/줄 수
        Matcher p = PROGRESS.matcher(text);
        int lines = 0, done = 0;
        String one = "";
        while (p.find()) {
            lines++;
            one = p.group(1) + "/" + p.group(2);
            if (Integer.parseInt(p.group(1)) >= Integer.parseInt(p.group(2))) done++;
        }
        if (lines == 1) progress = one;
        else if (lines > 1) progress = done + "/" + lines + "종";
    }

    /** HUD 글의 남은 시간: "4분 32초" · "4:32" · "272초" */
    static long hudRemaining(String t) {
        long left = remainingIn(t);
        if (left > 0) return left;
        Matcher c = CLOCK.matcher(t);
        if (c.find()) return (Long.parseLong(c.group(1)) * 60 + Long.parseLong(c.group(2))) * 1000;
        return 0;
    }

    /** 10틱마다: HUD 가 3초 넘게 안 보이면 오션오더가 끝난 것(성공·실패·채널 이동) */
    public static void hudPresent(boolean shown, long now) {
        if (shown || !hudShown || now - hudSeenAt < 3000) return;
        hudShown = false;
        finished = false; // HUD 가 사라졌으니 다음에 보이는 HUD 는 새 주문
        if (active(now)) {
            DdingMate.LOG.info("오션오더 HUD 가 사라져 타이머를 끔");
            clear();
        }
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
            if (name.replace(" ", "").contains("오션오더더더")) {
                readSkill(name, s.get(net.minecraft.component.DataComponentTypes.LORE));
                continue;
            }
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

    private static boolean skillConfirmed;

    /**
     * /전문가 스킬 창의 해양 스킬 "오션오더 더 더!"(위키 해양 전문가: Lv1~5 일일제한 12·14·16·18·20회).
     * 설명에서 지금 레벨의 "일일제한이 N회"를 읽는다(다음 레벨 미리보기 줄은 건너뜀). 안 배웠으면 10회
     */
    static void readSkill(String name, net.minecraft.component.type.LoreComponent lore) {
        int limit = DAILY_MAX;
        if (lore != null) {
            for (net.minecraft.text.Text l : lore.lines()) {
                String line = ItemKeys.stripGlyphs(l.getString());
                if (SKILL_PREVIEW.matcher(line).find()) break;
                Matcher m = SKILL_LIMIT.matcher(line);
                if (m.find()) {
                    limit = Integer.parseInt(m.group(1));
                    break;
                }
            }
        }
        if (name.matches(".*\\(\\s*\\+?\\s*0\\s*\\).*") || name.contains("잠김")) limit = DAILY_MAX;
        limit = Math.max(DAILY_MAX, Math.min(30, limit));
        int old = DdingMate.config().expert.oceanLimit;
        boolean changed = old != limit;
        if (changed) {
            DdingMate.config().expert.oceanLimit = limit;
            DdingMate.config().save();
            DdingMate.LOG.info("오션오더 하루 한도(스킬): {} → {}", old, limit);
        }
        if (!skillConfirmed || changed) {
            skillConfirmed = true;
            kr.ddingmate.skill.SkillReport.add("오션오더", "해양", "오션오더 더 더!",
                    limit > DAILY_MAX ? "오션오더 더 더!(하루 " + limit + "번)" : "오션오더 더 더! 안 배움", changed);
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
        return "오션오더" + (progress.isEmpty() ? "" : " " + progress) + " (오늘 " + today().count + "/" + limit() + ")";
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
        finished = false;
        finishedHud = "";
        skillConfirmed = false;
        warned = false;
        lastChannel = -1;
        if (days == null) load();
        days.clear();
        save();
    }

    public static void debugHandle(String text, long now) {
        handle(text, now);
    }

    /** 시험용: 스킬 창 칸 하나를 읽은 것처럼 */
    public static void debugSkill(String name, java.util.List<String> lore) {
        readSkill(name, new net.minecraft.component.type.LoreComponent(lore.stream().map(net.minecraft.text.Text::literal)
                .map(t -> (net.minecraft.text.Text) t).toList()));
    }

    public static void debugHudReset() {
        hudShown = false;
        hudSeenAt = 0;
        lastHud = "";
    }
}
