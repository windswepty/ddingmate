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

    /** 업데이트 공지(/업데이트공지): 새 버전 · 다운로드 주소(https, 없으면 빈 문자열) · 바뀐 점 */
    public record Update(String id, String version, String url, UpdateNotes.Release release) {}

    /** 지금 쓰는 버전보다 새 버전이 나왔으면 그 공지(가장 새 버전), 아니면 null */
    private static volatile Update newer;
    /** 이번 접속에서 새 버전을 채팅으로 한 번 알렸나(처음 알릴 때는 팝업까지, 그 뒤 접속마다 채팅 한 줄) */
    private static boolean remindedThisJoin;
    /** 채팅 [바뀐 점 보기] → /띵메이트 를 열면 새 버전 창을 먼저 */
    private static boolean openNewVersion;

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

    public static Update newer() {
        return newer;
    }

    /** /띵메이트 를 열 때 새 버전 창을 먼저 보여 줄지(한 번 쓰면 끔) */
    public static boolean takeOpenNewVersion() {
        boolean v = openNewVersion && newer != null;
        openNewVersion = false;
        return v;
    }

    public static void requestOpenNewVersion() {
        openNewVersion = true;
    }

    /** 마지막으로 받아 온 공지(새것 먼저) */
    public static List<Notice> received() {
        return received;
    }

    public static void onJoin() {
        nextAt = System.currentTimeMillis() + FIRST_DELAY_MS;
        remindedThisJoin = false;
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
        List<Update> updates = new ArrayList<>();
        try {
            JsonObject top = JsonParser.parseString(json).getAsJsonObject();
            if (top.has("pollMin")) pollMs = Math.max(10, Math.min(180, top.get("pollMin").getAsLong())) * 60_000L;
            for (JsonElement e : top.getAsJsonArray("notices")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String title = o.get("title").getAsString();
                String text = o.get("text").getAsString();
                if (id.isEmpty() || title.isBlank()) continue;
                long at = o.has("at") ? o.get("at").getAsLong() : 0;
                if (o.has("kind") && "update".equals(o.get("kind").getAsString())) {
                    Update u = update(id, o, at);
                    if (u != null) updates.add(u);
                    continue;
                }
                list.add(new Notice(id, cut(title, 60), cut(text, 1000), at));
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
        // 업데이트 공지: 지금 버전보다 새 버전 가운데 가장 새것만. 이미 그 버전 이상이면 조용히 넘어간다
        String current = UpdateNotes.currentVersion();
        Update best = null;
        for (Update u : updates) {
            if (UpdateNotes.compare(u.version(), current) <= 0) {
                if (!cfg.notices.seen.contains(u.id())) cfg.notices.seen.add(u.id());
                continue;
            }
            if (best == null || UpdateNotes.compare(u.version(), best.version()) > 0) best = u;
        }
        newer = best;
        if (best != null) {
            boolean first = !cfg.notices.seen.contains(best.id());
            if (first || !remindedThisJoin) {
                if (first) cfg.notices.seen.add(best.id());
                remindedThisJoin = true;
                Notifier.updateAvailable(best, first);
                shown++;
            }
        }
        if (shown > 0) {
            while (cfg.notices.seen.size() > 50) cfg.notices.seen.remove(0);
            cfg.save();
        }
        return shown;
    }

    /** 업데이트 공지 하나 읽기(버전 형식이 이상하거나 https 가 아닌 주소는 버린다) */
    static Update update(String id, JsonObject o, long at) {
        String version = o.has("version") ? o.get("version").getAsString().trim() : "";
        if (!version.matches("\\d+(\\.\\d+){1,3}")) return null;
        String url = o.has("url") && !o.get("url").isJsonNull() ? o.get("url").getAsString().trim() : "";
        if (!url.startsWith("https://") || url.contains(" ")) url = "";
        String date = at > 0 ? java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString() : "";
        UpdateNotes.Release r = new UpdateNotes.Release(version, date, items(o, "added"), items(o, "changed"), List.of(), items(o, "removed"));
        return new Update(id, version, cut(url, 300), r);
    }

    private static List<String> items(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                String s = e.getAsString().strip();
                if (!s.isEmpty() && out.size() < 20) out.add(cut(s, 200));
            }
        }
        return out;
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

    /** 시험용: 받은 공지·새 버전 알림을 비운다 */
    public static void debugReset() {
        received = List.of();
        newer = null;
        openNewVersion = false;
    }
}
