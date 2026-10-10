package kr.ddingmate.game;

import java.util.regex.Pattern;

/** 받은 채팅 줄이 서버 안내인지 사람이 쓴 것인지 가린다(서버 그림 글자를 지운 뒤의 글자로) */
public final class ServerText {

    private ServerText() {}

    /** 귓속말(2026 실제 로그): "(멜트 → 단무지<보냄>) 내용", "(단무지 → 멜트<받음>) 내용" */
    private static final Pattern WHISPER = Pattern.compile("^\\(\\s*[^()]*?→[^()]*?<\\s*(?:받음|보냄)\\s*>\\s*\\)");

    /**
     * 사람이 쓴 채팅이면 true. 일반·지역·마을 채팅은 "[칭호] 닉네임 : 내용", 귓속말은 "(보낸 사람 → 받는 사람<받음>) 내용".
     * 다른 사람이 서버 문구를 흉내 내 보내도 세지 않게 한다
     */
    public static boolean fromPlayer(String stripped) {
        if (stripped == null) return false;
        String t = stripped.trim();
        return t.contains(" : ") || WHISPER.matcher(t).find();
    }
}
