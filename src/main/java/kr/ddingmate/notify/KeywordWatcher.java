package kr.ddingmate.notify;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 이미 내 채팅창에 표시되는 메시지 중 지정한 단어가 들어간 것을 소리·토스트로 한 번 더 알려 준다.
 * 새로운 정보를 얻거나 자동으로 반응하지 않고, 알림만 한다.
 */
public final class KeywordWatcher {

    private static final Map<String, Long> LAST_FIRED = new HashMap<>();

    private KeywordWatcher() {}

    /** @param senderName 플레이어 채팅이면 보낸 사람 이름, 시스템 메시지면 null */
    public static void onMessage(Text message, String senderName) {
        DdingMateConfig.Keywords kw = DdingMate.config().keywords;
        if (!kw.enabled || message == null) return;
        String text = message.getString();
        if (text.isBlank()) return;
        String lower = text.toLowerCase(Locale.ROOT);

        MinecraftClient client = MinecraftClient.getInstance();
        String me = client.getSession() == null ? null : client.getSession().getUsername();
        boolean fromMe = me != null && (me.equalsIgnoreCase(senderName) || looksLikeMyOwnChatLine(lower, me));
        if (fromMe) return; // 내가 쓴 채팅에는 반응하지 않는다

        if (kw.includeOwnName && me != null && lower.contains(me.toLowerCase(Locale.ROOT))) {
            fire("@" + me, text, kw.cooldownSeconds);
            return;
        }
        for (String word : kw.words) {
            if (word == null || word.isBlank()) continue;
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                fire(word, text, kw.cooldownSeconds);
                return;
            }
        }
    }

    /** 서버 채팅 플러그인은 내 채팅도 시스템 메시지로 돌려보낸다. "닉네임:" 이나 "<닉네임>" 으로 시작하면 내 채팅으로 본다. */
    private static boolean looksLikeMyOwnChatLine(String lower, String me) {
        String m = me.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(m);
        if (idx < 0) return false;
        String after = lower.substring(idx + m.length()).stripLeading();
        return after.startsWith(":") || after.startsWith(">") || after.startsWith("»") || after.startsWith("|");
    }

    private static void fire(String word, String text, int cooldownSeconds) {
        long now = System.currentTimeMillis();
        Long last = LAST_FIRED.get(word);
        if (last != null && now - last < cooldownSeconds * 1000L) return;
        LAST_FIRED.put(word, now);
        Notifier.keyword(word, text);
    }
}
