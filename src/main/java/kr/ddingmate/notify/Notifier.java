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

    public static void keyword(String word, String message) {
        String shortMsg = message.length() > 60 ? message.substring(0, 60) + "…" : message;
        notify("키워드", "키워드 감지: " + word, shortMsg, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                new ItemStack(Items.BELL), KEYWORD_BLUE, false);
    }

    public static void materialsReady(String recipe, int crafts, ItemStack icon) {
        notify("재료", "재료 준비 완료: " + recipe + " ×" + crafts, "핀에 등록한 재료가 다 모였어요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), icon == null ? new ItemStack(Items.CHEST) : icon,
                Theme.GREEN, false);
    }

    public static void durabilityLow(String name, int left, int max, ItemStack icon) {
        notify("내구도", "내구도 부족: " + name, "남은 내구도 " + left + "/" + max + " · 수리할 때가 됐어요",
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), icon == null ? new ItemStack(Items.ANVIL) : icon,
                Theme.RED, false);
    }

    public static void todoReminder(int left, String names, int minutes) {
        notify("할 일", "오늘 할 일 " + left + "개 남았어요", "일일 초기화 " + minutes + "분 전 · " + names,
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), new ItemStack(Items.WRITABLE_BOOK), Theme.GOLD, false);
    }

    /** 접속 시간 보상. 채팅으로 꼭 알려 달라는 요청이라 채팅 알림을 꺼 두었어도 채팅에 남긴다. */
    public static void playtimeReward(int hours, String reward) {
        notify("접속", "접속 " + hours + "시간 달성!", reward + " · /접속시간 에서 받으세요",
                SoundEvents.ENTITY_PLAYER_LEVELUP, new ItemStack(Items.NETHER_STAR), Theme.GOLD, true);
    }

    public static void playtimeMidnight(String detail) {
        notify("접속", "자정에 접속 시간이 초기화돼요", detail + " · /접속시간 에서 보상을 받으세요",
                SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), new ItemStack(Items.CLOCK), Theme.GOLD, true);
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
            client.getToastManager().add(new DdingMateToast(title, body, icon, accent));
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
