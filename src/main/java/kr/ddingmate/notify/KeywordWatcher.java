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

        if (kw.includeOwnName && me != null && mentionsMe(lower, me.toLowerCase(Locale.ROOT), senderName)) {
            fire(MENTION, text, kw.cooldownSeconds);
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

    /** 닉네임 언급 알림의 기록 키 */
    static final String MENTION = "@닉네임";

    /**
     * 다른 사람이 채팅에서 내 닉네임을 불렀는가. "닉네임님이 업적을 달성했습니다" 같은 시스템 메시지에
     * 이름만 들어간 경우는 빼고, "[칭호] 보낸사람 : ...내 닉네임..." 처럼 채팅 내용 부분에 있을 때만 센다.
     */
    private static boolean mentionsMe(String lower, String me, String senderName) {
        if (senderName != null) return lower.contains(me); // 바닐라 채팅: 보낸 사람이 따로 오면 본문만 들어옴
        int sep = lower.indexOf(" : ");
        if (sep < 0) sep = lower.indexOf(": ");
        if (sep < 0) return false; // 채팅 줄 모양이 아니면 시스템 메시지로 본다
        return lower.indexOf(me, sep) >= 0;
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
        Notifier.keyword(MENTION.equals(word) ? null : word, text);
    }
}
