package kr.ddingmate.notify;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.schedule.ScheduleEngine;
import kr.ddingmate.ui.AlarmToast;
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
        notify(title, a.rule().detail(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                new ItemStack(Items.CLOCK), Theme.BLUE);
    }

    public static void keyword(String word, String message) {
        String shortMsg = message.length() > 60 ? message.substring(0, 60) + "…" : message;
        notify("키워드 감지: " + word, shortMsg, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
                new ItemStack(Items.BELL), KEYWORD_BLUE);
    }

    public static void materialsReady(String recipe, int crafts, ItemStack icon) {
        notify("재료 준비 완료: " + recipe + " ×" + crafts, "핀에 등록한 재료가 다 모였어요",
                SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), icon == null ? new ItemStack(Items.CHEST) : icon,
                Theme.GREEN);
    }

    public static void test() {
        notify("테스트 알림", "소리·팝업·채팅이 이렇게 나와요", SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                new ItemStack(Items.BELL), Theme.BLUE);
    }

    private static void notify(String title, String body, SoundEvent sound, ItemStack icon, int accent) {
        MinecraftClient client = MinecraftClient.getInstance();
        AlarmConfig cfg = DdingMate.config();
        if (cfg.soundEnabled && cfg.soundVolume > 0f) {
            client.getSoundManager().play(PositionedSoundInstance.master(sound, 1.0f, cfg.soundVolume));
        }
        if (cfg.toastEnabled) {
            client.getToastManager().add(new AlarmToast(title, body, icon, accent));
        }
        if (cfg.chatEnabled) {
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
