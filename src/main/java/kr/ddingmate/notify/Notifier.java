package kr.ddingmate.notify;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.ui.DdingMateToast;
import kr.ddingmate.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.format.DateTimeFormatter;

/** 소리·팝업·채팅으로 알린다. 채팅은 내 화면에만 추가되고 서버로 보내지 않는다. */
public final class Notifier {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int KEYWORD_BLUE = 0xFF2BA3D9;

    private Notifier() {}

    public static void schedule(ScheduleEngine.Alert a) {
        String when = a.leadMinutes() == 0 ? "지금" : a.leadMinutes() + "분 후 (" + HM.format(a.time()) + ")";
        String title = a.rule().label() + " · " + when;
        notify("일정", title, a.rule().detail(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                new ItemStack(Items.CLOCK), Theme.BLUE, false);
    }

    /** word 가 null 이면 내 닉네임 언급. 본문은 채팅에 뜬 메시지 그대로 */
    public static void keyword(String word, String message) {
        // 서버 그림 글자(말머리·등급 아이콘)는 띵메이트 글꼴에 없어 네모로 보이므로 뺀다
        message = kr.ddingmate.counter.ItemKeys.stripGlyphs(message).replaceAll("\\s+", " ").trim();
        String shortMsg = message.length() > 60 ? message.substring(0, 60) + "…" : message;
        String title = word == null ? "누가 내 닉네임을 불렀어요" : "채팅에 '" + word + "'";
        notify("키워드", title, shortMsg, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                new ItemStack(word == null ? Items.NAME_TAG : Items.BELL), KEYWORD_BLUE, false);
    }

    public static void materialsReady(String pinTitle, ItemStack icon) {
        notify("재료", "재료 준비 완료: " + Theme.compactGrade(pinTitle), "핀에 등록한 재료가 다 모였어요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), icon == null ? new ItemStack(Items.CHEST) : icon,
                Theme.GREEN, false);
    }

    public static void durabilityLow(String name, int left, int max, ItemStack icon) {
        notify("내구도", "내구도 부족: " + name, "남은 내구도 " + left + "/" + max + " · 수리할 때가 됐어요",
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), icon == null ? new ItemStack(Items.ANVIL) : icon,
                Theme.RED, false);
    }

    /** 제작·가공 시설에서 만들던 아이템이 다 됐을 때 */
    public static void voyageReset() {
        notify("제작", "항해 초기화 완료", "모든 섬을 다시 항해할 수 있어요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), new ItemStack(Items.OAK_BOAT), Theme.GREEN, false);
    }

    /** 형광 광산 골렘 채광이 끝났을 때 */
    public static void mineDone(String golem) {
        notify("제작", "형광 광산 채광 완료", (golem == null || golem.isEmpty() ? "" : golem + " 골렘 · ") + "광물을 수령할 수 있어요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), new ItemStack(Items.AMETHYST_SHARD), Theme.GREEN, false);
    }

    public static void voyageDone(String island) {
        notify("제작", "항해 완료: " + island, "항해선이 돌아왔어요 · 항해 창에서 보상을 받으세요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), new ItemStack(Items.OAK_BOAT), Theme.GREEN, false);
    }

    public static void craftDone(String name, int count, int times) {
        ItemStack icon = kr.ddingmate.counter.IconCache.forMaterial(name);
        notify("제작", "제작 완료: " + Theme.compactGrade(name) + (count > 1 ? " ×" + count : "") + (times > 1 ? " [" + times + "]" : ""),
                times > 1 ? "넣어 둔 " + times + "번이 모두 끝났어요 · 시설에서 꺼내 가세요" : "시설에서 꺼내 가세요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), icon == null ? new ItemStack(Items.CRAFTING_TABLE) : icon,
                Theme.GREEN, false);
    }

    public static void todoReminder(int left, String names, int minutes) {
        notify("할 일", "오늘 할 일 " + left + "개 남았어요", "일일 초기화 " + minutes + "분 전 · " + names,
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), new ItemStack(Items.WRITABLE_BOOK), Theme.GOLD, false);
    }

    /** 할 일 자동 세기: 채팅 알림을 켜 둔 경우에만 내 채팅창에 한 줄 남긴다(소리·팝업 없음) */
    public static void todoProgress(String name, int progress, int target) {
        if (!DdingMate.config().chatEnabled) return;
        chat(Text.literal("할 일 " + name + " [" + progress + "/" + target + "]" + (progress >= target ? " 완료!" : ""))
                .formatted(progress >= target ? Formatting.GREEN : Formatting.WHITE));
    }

    /** 접속 시간 보상. 채팅으로 꼭 알려 달라는 요청이라 채팅 알림을 꺼 두었어도 채팅에 남긴다. */
    public static void playtimeReward(int hours, String reward) {
        notify("접속", "접속 " + hours + "시간 달성!", reward + " · /접속시간 에서 받으세요",
                SoundEvents.ENTITY_PLAYER_LEVELUP, new ItemStack(Items.NETHER_STAR), Theme.GOLD, true);
    }

    /** 반려어 밥·폐사 위험·다 큼. 놓치면 폐사할 수 있어서 채팅 알림을 꺼 두었어도 채팅에 남긴다 */
    public static void petFish(String title, String body) {
        notify("반려어", title, body, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), new ItemStack(Items.TROPICAL_FISH),
                title.contains("위험") ? Theme.RED : Theme.BLUE, true);
    }

    /** 오션오더 도착·1분 남음·실패 */
    public static void oceanOrder(String title, String body) {
        notify("오션오더", title, body, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), kr.ddingmate.skill.SkillIcons.sageTool("rod"),
                title.contains("실패") ? Theme.RED : Theme.GOLD, false);
    }

    public static void playtimeMidnight(String detail) {
        notify("접속", "자정에 접속 시간이 초기화돼요", detail + " · /접속시간 에서 보상을 받으세요",
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), new ItemStack(Items.CLOCK), Theme.GOLD, true);
    }

    /**
     * 보낸 문의에 개발자가 디스코드에서 답했을 때. 귓속말처럼 내 채팅창에만 남기고(채팅 알림을 꺼 두었어도), 소리·팝업도 낸다.
     * 서버로는 아무것도 보내지 않는다. 기록 탭에서 다시 볼 수 있다
     */
    public static void devReply(String category, String preview, String text) {
        AlertLog.add("문의", "개발자 답변 · [" + category + "] " + preview, text);
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig cfg = DdingMate.config();
        if (cfg.soundEnabled && cfg.soundVolume > 0f) {
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, cfg.soundVolume));
        }
        if (cfg.toastEnabled) {
            String body = text.replaceAll("\\s+", " ").trim();
            ItemStack icon = new ItemStack(Items.WRITABLE_BOOK);
            if (toastReady(client)) client.getToastManager().add(new DdingMateToast("개발자 답변이 왔어요", body, icon, Theme.BLUE));
            else HELD.add(new Held("개발자 답변이 왔어요", body, icon, Theme.BLUE));
        }
        chat(Text.literal("개발자 → 나").formatted(Formatting.LIGHT_PURPLE)
                .append(Text.literal(" (문의: [" + category + "] " + preview + ")").formatted(Formatting.GRAY)));
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            chat(Text.literal(line.strip()).formatted(Formatting.LIGHT_PURPLE));
        }
    }

    // ---------------------------------------------------------------- 접속 직후 팝업 미루기

    /**
     * 접속 직후에는 서버 리소스팩(띵타이쿤 아이템 그림)을 받아 적용하는 중이라, 이때 뜬 팝업은 아이콘이 그림 없는 종이로 보인다
     * (2026-10-16 실제: 접속하자마자 "제작 완료" 팝업 두 개가 종이 아이콘). 리소스팩 적용이 끝날 때까지 팝업만 미뤘다가 띄운다.
     * 소리·채팅은 바로 낸다.
     */
    private record Held(String title, String body, ItemStack icon, int accent) {}

    private static final java.util.List<Held> HELD = new java.util.ArrayList<>();
    private static long joinedAt;
    private static long lastReloadAt;
    private static boolean reloadedSinceJoin;
    /** 리소스팩이 없는 서버면 이만큼 기다린 뒤 띄운다 */
    static final long NO_PACK_WAIT_MS = 15_000;
    /** 리소스를 다시 읽은 직후 그림이 자리 잡을 시간 */
    static final long AFTER_RELOAD_MS = 1_500;

    /** 서버에 접속했을 때 */
    public static void onJoin() {
        joinedAt = System.currentTimeMillis();
        reloadedSinceJoin = false;
    }

    /** 리소스(리소스팩)를 다시 읽었을 때 */
    public static void onResourceReload() {
        lastReloadAt = System.currentTimeMillis();
        if (joinedAt > 0) reloadedSinceJoin = true;
    }

    /** 지금 팝업을 띄워도 아이콘이 제대로 보이나 */
    static boolean toastReady(MinecraftClient client) {
        if (client.getOverlay() != null) return false; // 리소스 다시 읽는 중
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.DownloadingTerrainScreen
                || client.currentScreen instanceof net.minecraft.client.gui.screen.ProgressScreen) return false;
        long now = System.currentTimeMillis();
        if (now - lastReloadAt < AFTER_RELOAD_MS) return false;
        // 혼자 하기(내장 서버)에는 서버 리소스팩이 없다
        if (client.getCurrentServerEntry() == null || joinedAt == 0) return true;
        return reloadedSinceJoin || now - joinedAt >= NO_PACK_WAIT_MS;
    }

    /** 매 틱: 미뤄 둔 팝업을 띄울 수 있으면 띄운다 */
    public static void tick(MinecraftClient client) {
        if (HELD.isEmpty() || client.player == null || !toastReady(client)) return;
        for (Held h : HELD) client.getToastManager().add(new DdingMateToast(h.title(), h.body(), h.icon(), h.accent()));
        HELD.clear();
    }

    /** 시험용 */
    public static int heldCount() {
        return HELD.size();
    }

    /** 시험용: 접속 직후·리소스 다시 읽기 상태를 흉내 낸다(joinedAgoMs 전에 접속, reloaded = 그 뒤에 리소스를 다시 읽었나) */
    public static boolean debugReady(boolean remote, long joinedAgoMs, boolean reloaded, long reloadAgoMs) {
        long now = System.currentTimeMillis();
        long keepJoin = joinedAt, keepReload = lastReloadAt;
        boolean keepFlag = reloadedSinceJoin;
        joinedAt = now - joinedAgoMs;
        lastReloadAt = reloaded ? now - reloadAgoMs : 0;
        reloadedSinceJoin = reloaded;
        boolean ready;
        if (now - lastReloadAt < AFTER_RELOAD_MS) ready = false;
        else ready = !remote || reloadedSinceJoin || now - joinedAt >= NO_PACK_WAIT_MS;
        joinedAt = keepJoin;
        lastReloadAt = keepReload;
        reloadedSinceJoin = keepFlag;
        return ready;
    }

    public static void test() {
        notify("테스트", "테스트 알림", "소리·팝업·채팅이 이렇게 나와요", SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                new ItemStack(Items.BELL), Theme.BLUE, false);
    }

    /** kind: 기록함 분류. alwaysChat: 채팅 알림 설정과 상관없이 채팅에 남김 */
    private static void notify(String kind, String title, String body, SoundEvent sound, ItemStack icon, int accent,
                               boolean alwaysChat) {
        AlertLog.add(kind, title, body);
        MinecraftClient client = MinecraftClient.getInstance();
        DdingMateConfig cfg = DdingMate.config();
        if (cfg.soundEnabled && cfg.soundVolume > 0f) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0f, cfg.soundVolume));
        }
        if (cfg.toastEnabled) {
            if (toastReady(client)) client.getToastManager().add(new DdingMateToast(title, body, icon, accent));
            else HELD.add(new Held(title, body, icon, accent));
        }
        if (cfg.chatEnabled || alwaysChat) {
            chat(Text.literal(title).formatted(Formatting.YELLOW)
                    .append(Text.literal(" — " + body).formatted(Formatting.GRAY)));
        }
    }

    public static void chat(String msg) {
        chat(Text.literal(msg).formatted(Formatting.WHITE));
    }

    public static void chat(Text msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) return;
        MutableText prefix = Text.literal("[띵메이트] ").formatted(Formatting.GOLD);
        client.inGameHud.getChatHud().addMessage(prefix.append(msg));
    }
}
