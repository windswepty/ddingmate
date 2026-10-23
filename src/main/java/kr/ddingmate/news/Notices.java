package kr.ddingmate.news;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.inquiry.Inquiry;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 개발자 공지(2026-10-24 사용자 요청: "관리자 전용, 모드 사용자들한테 전달되는 공지").
 * 개발자가 디스코드에서 /공지 로 올리면 문의 중계(relay/)가 저장하고, 띵메이트는 게임에 들어가 있을 때 접속 직후·30분마다
 * GET /notices 로 최근 30일 공지를 받아 와 처음 보는 것만 내 화면에(팝업·채팅·소리, 기록 탭) 알린다.
 * 받아 올 때 띵메이트 버전만 함께 간다(닉네임·UUID·서버 주소는 안 보냄). 게임 서버로는 아무것도 보내지 않는다.
 */
public final class Notices {

    /** 공지 하나 */
    public record Notice(String id, String title, String text, long at) {}

    /** 중계가 주는 간격이 없을 때 */
    static final long DEFAULT_POLL_MS = 30 * 60 * 1000L;
    /** 접속하자마자 묻지 않고 조금 기다린다(리소스팩 받는 중) */
    static final long FIRST_DELAY_MS = 8_000;

    private static volatile List<Notice> received = List.of();
    private static long pollMs = DEFAULT_POLL_MS;
    private static long nextAt = Long.MAX_VALUE;
    private static boolean busy;
    /** 시험용: 정해 두면 인터넷 대신 이 응답(JSON)을 쓴다 */
    public static Supplier<String> testSource;

    private Notices() {}

    /** 마지막으로 받아 온 공지(새것 먼저) */
    public static List<Notice> received() {
        return received;
    }

    public static void onJoin() {
        nextAt = System.currentTimeMillis() + FIRST_DELAY_MS;
    }

    public static void onDisconnect() {
        nextAt = Long.MAX_VALUE;
    }

    /** 매 틱: 물을 때가 됐으면 묻는다 */
    public static void tick(MinecraftClient client) {
        if (client.player == null || busy || System.currentTimeMillis() < nextAt) return;
        nextAt = System.currentTimeMillis() + pollMs;
        fetch(client);
    }

    static void fetch(MinecraftClient client) {
        DdingMateConfig cfg = DdingMate.config();
        if (cfg == null || !cfg.notices.enabled) return;
        if (testSource != null) {
            apply(testSource.get());
            return;
        }
        String base = Inquiry.endpoint();
        if (base == null) return;
        busy = true;
        String v = URLEncoder.encode(UpdateNotes.currentVersion(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/notices?v=" + v)).timeout(Duration.ofSeconds(15)).GET().build();
        Inquiry.http().sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((res, err) -> client.execute(() -> {
                    busy = false;
                    // 실패하면 조용히 넘어가고 다음 차례에 다시 묻는다
                    if (err != null) DdingMate.LOG.info("공지를 받아 오지 못했어요: {}", err.toString());
                    else if (res.statusCode() != 200) DdingMate.LOG.info("공지를 받아 오지 못했어요: HTTP {}", res.statusCode());
                    else apply(res.body());
                }));
    }

    /** 중계 응답을 반영한다: 처음 보는 공지만 알리고 본 것으로 적는다. 새로 알린 개수 */
    public static int apply(String json) {
        DdingMateConfig cfg = DdingMate.config();
        List<Notice> list = new ArrayList<>();
        try {
            JsonObject top = JsonParser.parseString(json).getAsJsonObject();
            if (top.has("pollMin")) pollMs = Math.max(10, Math.min(180, top.get("pollMin").getAsLong())) * 60_000L;
            for (JsonElement e : top.getAsJsonArray("notices")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String title = o.get("title").getAsString();
                String text = o.get("text").getAsString();
                if (id.isEmpty() || title.isBlank()) continue;
                list.add(new Notice(id, cut(title, 60), cut(text, 1000), o.has("at") ? o.get("at").getAsLong() : 0));
            }
        } catch (RuntimeException e) {
            DdingMate.LOG.warn("공지를 읽지 못했어요: {}", json, e);
            return 0;
        }
        received = List.copyOf(list);
        int shown = 0;
        // 오래된 것부터 알려야 채팅에서 새 공지가 아래에 온다
        for (int i = list.size() - 1; i >= 0; i--) {
            Notice n = list.get(i);
            if (cfg.notices.seen.contains(n.id())) continue;
            cfg.notices.seen.add(n.id());
            Notifier.notice(n.title(), n.text());
            shown++;
        }
        if (shown > 0) {
            while (cfg.notices.seen.size() > 50) cfg.notices.seen.remove(0);
            cfg.save();
        }
        return shown;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugFetch(MinecraftClient client) {
        fetch(client);
    }

    public static long debugPollMs() {
        return pollMs;
    }
}
