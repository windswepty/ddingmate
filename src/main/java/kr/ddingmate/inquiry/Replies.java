package kr.ddingmate.inquiry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import kr.ddingmate.DdingMate;
import kr.ddingmate.notify.Notifier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 보낸 문의에 개발자가 디스코드에서 단 답변을 받아 와 내 화면에만(채팅·팝업·소리, 기록 탭) 보여 준다.
 * 게임에 들어가 있을 때 접속 직후 한 번, 그 뒤 10분마다 중계에 묻는다. 접속해 있지 않을 때 단 답변은 다음 접속 때 받는다.
 * 묻는 것은 최근 30일 안에 보낸 문의뿐이고, 묻는 데 쓰는 비밀 번호표는 config/ddingmate-inquiries.json 에만 둔다.
 */
public final class Replies {

    private Replies() {}

    public static final class Ticket {
        public String id = "";
        public String token = "";
        public String category = "";
        public String preview = "";
        public long sentAt;
        /** 중계에 기록이 없어(90일 지남) 더 묻지 않음 */
        public boolean gone;
        public List<Reply> replies = new ArrayList<>();

        int lastN() {
            int n = 0;
            for (Reply r : replies) n = Math.max(n, r.n);
            return n;
        }
    }

    public static final class Reply {
        public int n;
        public String text = "";
        public long at;
        public long receivedAt;
    }

    static final long WATCH_MS = 30L * 24 * 3600 * 1000;
    static final long POLL_MS = 10 * 60 * 1000;
    /** 접속하자마자 묻지 않고 조금 기다린다(리소스팩 받는 중) */
    static final long FIRST_DELAY_MS = 5_000;
    static final int MAX_TICKETS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static List<Ticket> tickets;
    private static long nextPollAt = Long.MAX_VALUE;
    private static boolean polling;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-inquiries.json");
    }

    private static List<Ticket> tickets() {
        if (tickets == null) {
            tickets = new ArrayList<>();
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    List<Ticket> read = GSON.fromJson(r, new TypeToken<List<Ticket>>() {}.getType());
                    if (read != null) for (Ticket t : read) if (t != null && t.token != null && !t.token.isEmpty()) tickets.add(t);
                } catch (Exception e) {
                    DdingMate.LOG.warn("보낸 문의 기록을 읽지 못했어요: {}", p, e);
                }
            }
        }
        return tickets;
    }

    static void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(tickets(), w);
            }
        } catch (Exception e) {
            DdingMate.LOG.warn("보낸 문의 기록을 저장하지 못했어요", e);
        }
    }

    /** 문의를 보냈을 때: 답변을 받아 갈 번호표를 남긴다(오래된 것부터 20개 넘으면 지움) */
    static void add(String id, String token, Inquiry.Category category, String text) {
        Ticket t = new Ticket();
        t.id = id == null ? "" : id;
        t.token = token;
        t.category = category.label;
        String one = text.strip().replaceAll("\\s+", " ");
        t.preview = one.length() > 40 ? one.substring(0, 40) + "…" : one;
        t.sentAt = System.currentTimeMillis();
        List<Ticket> list = tickets();
        list.add(t);
        while (list.size() > MAX_TICKETS) list.remove(0);
        save();
    }

    /** 지금 답변을 기다리는(묻는) 문의 */
    static List<Ticket> watched() {
        long now = System.currentTimeMillis();
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets()) if (!t.gone && now - t.sentAt < WATCH_MS) out.add(t);
        return out;
    }

    /** 서버에 들어갔을 때 */
    public static void onJoin() {
        nextPollAt = System.currentTimeMillis() + FIRST_DELAY_MS;
    }

    public static void onDisconnect() {
        nextPollAt = Long.MAX_VALUE;
    }

    /** 매 틱: 물을 때가 됐으면 묻는다 */
    public static void tick(MinecraftClient client) {
        if (client.player == null || polling || System.currentTimeMillis() < nextPollAt) return;
        nextPollAt = System.currentTimeMillis() + POLL_MS;
        poll(client);
    }

    static void poll(MinecraftClient client) {
        List<Ticket> asked = watched();
        if (asked.isEmpty() || (Inquiry.endpoint() == null && Inquiry.testSender == null)) return;
        JsonObject root = new JsonObject();
        JsonArray tokens = new JsonArray();
        for (Ticket t : asked) tokens.add(t.token);
        root.add("tokens", tokens);
        String body = root.toString();
        if (Inquiry.testSender != null) {
            Inquiry.Http res = Inquiry.testSender.apply("/replies", body);
            if (res != null && res.status() == 200) apply(asked, res.body());
            return;
        }
        polling = true;
        HttpRequest req = HttpRequest.newBuilder(URI.create(Inquiry.endpoint() + "/replies")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        Inquiry.http().sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((res, err) -> client.execute(() -> {
                    polling = false;
                    // 실패하면 조용히 넘어가고 다음 차례(10분 뒤·다음 접속)에 다시 묻는다
                    if (err != null) DdingMate.LOG.info("문의 답변을 확인하지 못했어요: {}", err.toString());
                    else if (res.statusCode() != 200) DdingMate.LOG.info("문의 답변을 확인하지 못했어요: HTTP {}", res.statusCode());
                    else apply(asked, res.body());
                }));
    }

    /** 중계 응답을 반영한다: 처음 보는 답변만 알리고 저장. 알린 답변 수를 돌려준다 */
    static int apply(List<Ticket> asked, String json) {
        int shown = 0;
        boolean changed = false;
        try {
            JsonArray list = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("tickets");
            for (int i = 0; i < Math.min(asked.size(), list.size()); i++) {
                Ticket t = asked.get(i);
                JsonObject o = list.get(i).getAsJsonObject();
                if (!o.get("found").getAsBoolean()) {
                    // 방금 보낸 문의가 아직 안 보이는 일은 없지만, 하루 지나도록 없으면 그만 묻는다
                    if (System.currentTimeMillis() - t.sentAt > 24L * 3600 * 1000) {
                        t.gone = true;
                        changed = true;
                    }
                    continue;
                }
                int seen = t.lastN();
                for (JsonElement e : o.getAsJsonArray("replies")) {
                    JsonObject r = e.getAsJsonObject();
                    int n = r.get("n").getAsInt();
                    if (n <= seen) continue;
                    Reply reply = new Reply();
                    reply.n = n;
                    reply.text = r.get("text").getAsString();
                    reply.at = r.get("at").getAsLong();
                    reply.receivedAt = System.currentTimeMillis();
                    t.replies.add(reply);
                    changed = true;
                    shown++;
                    Notifier.devReply(t.category, t.preview, reply.text);
                }
            }
        } catch (RuntimeException e) {
            DdingMate.LOG.warn("문의 답변을 읽지 못했어요: {}", json, e);
        }
        if (changed) save();
        return shown;
    }

    // ---------------------------------------------------------------- 시험용

    /** 시험용: 기록을 비우고 다시 읽게 한다 */
    public static void debugReset() {
        tickets = new ArrayList<>();
        save();
    }

    public static List<Ticket> debugTickets() {
        return tickets();
    }

    public static void debugPoll(MinecraftClient client) {
        poll(client);
    }

    public static long debugNextPollAt() {
        return nextPollAt;
    }
}
