package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

import java.util.List;

/**
 * 아일랜드 작물 15분 타이머(단축키). 채집하고 다시 심은 뒤 사용자가 정한 단축키(한 키 또는 최대 3개 조합)를 누르면 15분을 센다.
 * 세는 중에 한 번 더 누르면 취소. 위키 아일랜드 농사(2026-10-22): "작물은 다 자라기까지 15분", "마을에 사람이 있을 때만 자라요"
 * → '마을 밖에서는 멈춤'(기본 켬)이면 마을이 아닌 곳에 있는 동안 멈춘다. 키 입력만 읽고 서버로는 아무것도 보내지 않는다.
 */
public final class CropTimer {

    private CropTimer() {}

    public static final long GROW_MS = 15 * 60_000L;

    /** 끝나는 시각(0 = 안 셈). 멈춘 동안은 pausedLeft 에 남은 시간 */
    private static long endsAt;
    private static long pausedLeft;
    private static boolean comboWasDown;
    /** 15분이 다 됐다. 다음에 단축키로 다시 셀 때까지 일정 HUD 에 "다 자람"으로 남긴다(문의 96ad0fb8: 끝나자마자 사라짐) */
    private static boolean ripe;

    /** 매 틱: 단축키(눌리는 순간만), 마을 밖 멈춤, 끝 알림 */
    public static void tick(MinecraftClient client) {
        DdingMateConfig.Expert ex = DdingMate.config().expert;
        long now = System.currentTimeMillis();
        boolean down = client.currentScreen == null && client.player != null && comboDown(client, ex.cropHotkey);
        if (down && !comboWasDown) toggle(now);
        comboWasDown = down;
        if (!running()) return;
        // 마을 밖에서는 자라지 않는다(위키). 지역을 모르면(띵타이쿤 밖) 그대로 센다
        kr.ddingmate.game.ServerHud.Area area = kr.ddingmate.game.ServerHud.area();
        boolean away = ex.cropPauseAway && area != null && area != kr.ddingmate.game.ServerHud.Area.VILLAGE;
        if (away && pausedLeft == 0) {
            pausedLeft = Math.max(1, endsAt - now);
        } else if (!away && pausedLeft > 0) {
            endsAt = now + pausedLeft;
            pausedLeft = 0;
        }
        if (pausedLeft == 0 && now >= endsAt) {
            endsAt = 0;
            ripe = true;
            Notifier.cropTimerDone();
        }
    }

    private static boolean comboDown(MinecraftClient client, List<Integer> keys) {
        if (keys == null || keys.isEmpty()) return false;
        long window = client.getWindow().getHandle();
        for (int k : keys) if (!InputUtil.isKeyPressed(window, k)) return false;
        return true;
    }

    /** 단축키: 안 세고 있으면 시작, 세고 있으면 취소 */
    static void toggle(long now) {
        if (running()) {
            cancel();
            Notifier.chat("아일랜드 작물 타이머를 취소했어요. 단축키를 다시 누르면 15분을 새로 세요.");
        } else {
            start(now);
            Notifier.chat("아일랜드 작물 타이머 시작: 15분 뒤 다 자라요. 취소하려면 단축키를 한 번 더 눌러요.");
        }
    }

    public static void start(long now) {
        endsAt = now + GROW_MS;
        pausedLeft = 0;
        ripe = false;
    }

    public static void cancel() {
        endsAt = 0;
        pausedLeft = 0;
        ripe = false;
    }

    /** 다 자랐고 아직 다시 세지 않음 */
    public static boolean ripe() {
        return ripe && !running();
    }

    public static boolean running() {
        return endsAt > 0;
    }

    public static boolean paused() {
        return running() && pausedLeft > 0;
    }

    /** 남은 ms(멈춘 동안은 멈춘 값) */
    public static long remaining(long now) {
        if (!running()) return 0;
        return pausedLeft > 0 ? pausedLeft : Math.max(0, endsAt - now);
    }

    public static String remainingText(long now) {
        long sec = (remaining(now) + 999) / 1000;
        String t = sec >= 60 ? (sec / 60) + "분 " + (sec % 60) + "초" : sec + "초";
        return paused() ? t + " · 멈춤(마을 밖)" : t;
    }

    /** "Ctrl + Shift + G" (지정 안 했으면 "지정 안 함") */
    public static String keyName(List<Integer> keys) {
        if (keys == null || keys.isEmpty()) return "지정 안 함";
        StringBuilder sb = new StringBuilder();
        for (int k : keys) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(InputUtil.Type.KEYSYM.createFromCode(k).getLocalizedText().getString());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugToggle(long now) {
        toggle(now);
    }

    public static void debugSetPaused(long left) {
        pausedLeft = left;
    }
}
