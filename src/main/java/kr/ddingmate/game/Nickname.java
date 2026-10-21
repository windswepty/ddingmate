package kr.ddingmate.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 띵타이쿤 안에서 쓰는 내 한글 닉네임(계정 이름 windswepty 와 따로, 예: 멜트). 서버가 보내 준 글에서만 배운다.
 * <ul>
 * <li>귓속말(2026 실제): "(멜트 → 단무지&lt;보냄&gt;) 내용" 이면 보낸 쪽, "(바다 → 멜트&lt;받음&gt;) 내용" 이면 받는 쪽이 나</li>
 * <li>내가 채팅을 치고 바로 돌아온 "[칭호] 멜트 : 같은 내용"</li>
 * </ul>
 * 계정(UUID)마다 기억한다. 서버로는 아무것도 보내지 않는다.
 */
public final class Nickname {

    private Nickname() {}

    private static final Pattern WHISPER = Pattern.compile("^\\(\\s*([^()→]+?)\\s*→\\s*([^()<]+?)\\s*<\\s*(받음|보냄)\\s*>\\s*\\)");
    /** "[칭호] 멜트 : 내용" → 멜트. 앞의 [..] 꼬리표는 떼고 마지막 낱말 */
    private static final Pattern CHAT = Pattern.compile("^(?:\\s*\\[[^\\]]*\\])*\\s*([^\\s:\\[\\]]{1,16})\\s+:\\s+(.+)$");
    private static final Pattern NICK = Pattern.compile("^[\\p{IsHangul}A-Za-z0-9_]{1,16}$");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, String> store;
    private static String lastSent;
    private static long lastSentAt;

    /** 내가 보낸 채팅(돌아온 줄과 맞춰 보려고 내용만 잠깐 기억) */
    public static void onSent(String message) {
        if (message == null || message.startsWith("/")) return;
        lastSent = message.trim();
        lastSentAt = System.currentTimeMillis();
    }

    public static void onChat(String raw) {
        if (raw == null) return;
        String t = ItemKeys.stripGlyphs(net.minecraft.util.Formatting.strip(raw)).replaceAll("\\s+", " ").trim();
        Matcher w = WHISPER.matcher(t);
        if (w.find()) {
            learn(w.group(3).equals("보냄") ? w.group(1) : w.group(2));
            return;
        }
        if (lastSent == null || System.currentTimeMillis() - lastSentAt > 5_000) return;
        Matcher c = CHAT.matcher(t);
        if (c.find() && c.group(2).trim().equals(lastSent)) {
            lastSent = null;
            learn(c.group(1));
        }
    }

    /** 배운 한글 닉네임. 모르거나 계정 이름과 같으면 null */
    public static String get() {
        String uuid = uuid();
        if (uuid == null) return null;
        return store().get(uuid);
    }

    static void learn(String nick) {
        if (nick == null) return;
        nick = nick.trim();
        String uuid = uuid();
        MinecraftClient client = MinecraftClient.getInstance();
        String account = client.player != null ? client.player.getGameProfile().getName() : null;
        // 한글이 들어간 닉네임만(영어 닉네임이면 계정 이름과 같으니 덧붙일 게 없다)
        if (uuid == null || !NICK.matcher(nick).matches() || !nick.matches(".*\\p{IsHangul}.*") || nick.equalsIgnoreCase(account)) return;
        if (nick.equals(store().get(uuid))) return;
        store().put(uuid, nick);
        DdingMate.LOG.info("한글 닉네임을 알았어요: {}", nick);
        save();
    }

    private static String uuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID id = client.player != null ? client.player.getUuid() : null;
        return id == null ? null : id.toString();
    }

    private static Map<String, String> store() {
        if (store == null) {
            store = new LinkedHashMap<>();
            if (Files.exists(path())) {
                try (Reader r = Files.newBufferedReader(path(), StandardCharsets.UTF_8)) {
                    Map<String, String> read = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
                    if (read != null) store.putAll(read);
                } catch (Exception e) {
                    DdingMate.LOG.warn("닉네임 기록을 읽지 못했어요", e);
                }
            }
        }
        return store;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-nickname.json");
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(store(), w);
        } catch (Exception e) {
            DdingMate.LOG.warn("닉네임 기록을 저장하지 못했어요", e);
        }
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugReset() {
        store().clear();
        save();
        lastSent = null;
    }
}
