package kr.ddingmate.notify;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.game.Nickname;
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

        // 서버 채팅에는 계정 이름(windswepty)이 아니라 한글 닉네임(멜트)이 나온다(2026-10 실제 로그 81줄). 둘 다 내 이름으로 본다
        Nickname.Line line = senderName != null ? null
                : Nickname.parse(ItemKeys.stripGlyphs(java.util.Objects.requireNonNullElse(net.minecraft.util.Formatting.strip(text), "")));
        String sender = senderName != null ? senderName : line != null ? line.sender() : null;
        boolean fromMe = Nickname.isMe(sender) || (line != null && line.sent());
        if (fromMe) return; // 내가 쓴 채팅·내가 보낸 귓속말에는 반응하지 않는다

        if (kw.includeOwnName && mentionsMe(senderName != null ? lower : line != null ? line.body().toLowerCase(Locale.ROOT) : null)) {
            fire(MENTION, text, kw.cooldownSeconds);
            return;
        }
        String body = withoutExcludes(lower, kw.excludes);
        for (String word : kw.words) {
            if (word == null || word.isBlank()) continue;
            if (body.contains(word.toLowerCase(Locale.ROOT))) {
                fire(word, text, kw.cooldownSeconds);
                return;
            }
        }
    }

    /** 제외할 말을 지운 글. "이찬"을 지우면 "이찬 왔어"의 "찬"은 안 울리고 "찬 왔어"는 울린다 */
    static String withoutExcludes(String lower, java.util.List<String> excludes) {
        if (excludes == null) return lower;
        for (String ex : excludes) {
            if (ex != null && !ex.isBlank()) lower = lower.replace(ex.toLowerCase(Locale.ROOT), " ");
        }
        return lower;
    }

    /** 시험용 */
    public static String debugWithoutExcludes(String text, java.util.List<String> excludes) {
        return withoutExcludes(text.toLowerCase(Locale.ROOT), excludes);
    }

    /** 닉네임 언급 알림의 기록 키 */
    static final String MENTION = "@닉네임";

    /**
     * 다른 사람이 채팅에서 내 닉네임을 불렀는가. "닉네임님이 업적을 달성했습니다" 같은 시스템 메시지에
     * 이름만 들어간 경우는 빼고, "[칭호] 보낸사람 : ...내 닉네임..." 처럼 채팅 내용 부분에 있을 때만 센다.
     */
    private static boolean mentionsMe(String body) {
        if (body == null) return false; // 채팅 줄 모양이 아니면 시스템 메시지로 본다
        for (String name : Nickname.myNames()) {
            if (body.contains(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 시험용: 이 줄이 알림을 울리면 그 키(키워드 또는 "@닉네임"), 아니면 null. 쿨타임은 보지 않는다 */
    public static String debugCheck(String text) {
        LAST_FIRED.clear();
        String[] fired = {null};
        DEBUG_FIRE = w -> fired[0] = w;
        try {
            onMessage(Text.literal(text), null);
        } finally {
            DEBUG_FIRE = null;
        }
        return fired[0];
    }

    private static java.util.function.Consumer<String> DEBUG_FIRE;

    private static void fire(String word, String text, int cooldownSeconds) {
        if (DEBUG_FIRE != null) {
            DEBUG_FIRE.accept(word);
            return;
        }
        long now = kr.ddingmate.util.Clock.now();
        Long last = LAST_FIRED.get(word);
        if (last != null && now - last < cooldownSeconds * 1000L) return;
        LAST_FIRED.put(word, now);
        Notifier.keyword(MENTION.equals(word) ? null : word, text);
    }
}
