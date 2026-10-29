package kr.ddingmate.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import kr.ddingmate.DdingMate;
import kr.ddingmate.mixin.BossBarHudAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 개발용 원본 기록(시험 재료). 서버가 보낸 채팅·액션바·제목·보스바·오른쪽 사이드바·상자형 창을 글꼴·모양 정보까지
 * 그대로(JSON) config/ddingmate-record/날짜.jsonl 에 적는다. 시험에서 이 기록을 읽어 실제 화면을 똑같이 다시 만든다.
 * <p>
 * 배포판에서는 꺼져 있다. 켜려면 config 폴더에 ddingmate-record.on 파일을 두거나 JVM 인수 -Dddingmate.record=true.
 * 내 컴퓨터 안에만 남고 어디로도 보내지 않는다(다른 사람 채팅이 들어 있으니 저장소에도 넣지 않는다). 모두 200MB 까지.
 */
public final class DevRecord {

    private DevRecord() {}

    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static Boolean enabled;
    private static long written = -1;
    private static final Map<UUID, String> BARS = new HashMap<>();
    private static String lastSidebar = "";
    private static String lastScreenSig = "";
    private static String lastWorn = "";
    private static Object lastScreen;

    public static boolean enabled() {
        if (enabled == null) {
            Path cfg = FabricLoader.getInstance().getConfigDir();
            enabled = Boolean.getBoolean("ddingmate.record") || Files.exists(cfg.resolve("ddingmate-record.on"));
            if (enabled) DdingMate.LOG.info("개발용 원본 기록을 켰어요: {}", dir());
        }
        return enabled;
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-record");
    }

    private static RegistryWrapper.WrapperLookup registries() {
        MinecraftClient c = MinecraftClient.getInstance();
        return c.world != null ? c.world.getRegistryManager() : null;
    }

    private static JsonElement text(Text t, RegistryWrapper.WrapperLookup r) {
        return com.google.gson.JsonParser.parseString(Text.Serialization.toJsonString(t, r));
    }

    /** 채팅(kind=chat)·액션바(overlay)·제목(title)·부제목(subtitle) */
    public static void message(String kind, Text t) {
        if (t == null || !enabled()) return;
        RegistryWrapper.WrapperLookup r = registries();
        if (r == null) return;
        JsonObject o = head(kind);
        o.add("text", text(t, r));
        o.addProperty("plain", t.getString());
        write(o);
    }

    /** 10틱마다: 보스바(바뀐 것만)·사이드바(바뀌면)·상자형 창(칸이 바뀌면) */
    public static void tick(MinecraftClient client) {
        if (!enabled() || client.player == null || client.inGameHud == null) return;
        RegistryWrapper.WrapperLookup r = registries();
        if (r == null) return;
        // 보스바: 띵타이쿤 HUD(채널·오션오더 등)
        Map<UUID, ClientBossBar> bars = ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ddingmate$bossBars();
        for (Map.Entry<UUID, ClientBossBar> e : bars.entrySet()) {
            String json = Text.Serialization.toJsonString(e.getValue().getName(), r);
            if (json.equals(BARS.get(e.getKey()))) continue;
            BARS.put(e.getKey(), json);
            JsonObject o = head("bossbar");
            o.addProperty("id", e.getKey().toString());
            o.add("text", com.google.gson.JsonParser.parseString(json));
            o.addProperty("plain", e.getValue().getName().getString());
            o.addProperty("percent", e.getValue().getPercent());
            write(o);
        }
        if (BARS.keySet().retainAll(bars.keySet())) {
            JsonObject o = head("bossbars");
            JsonArray ids = new JsonArray();
            for (UUID id : bars.keySet()) ids.add(id.toString());
            o.add("ids", ids);
            write(o);
        }
        // 오른쪽 사이드바(의뢰 트래커 등)
        var sb = client.world.getScoreboard();
        var obj = sb.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (obj != null) {
            JsonArray lines = new JsonArray();
            for (var entry : sb.getScoreboardEntries(obj)) {
                var team = sb.getScoreHolderTeam(entry.owner());
                JsonObject l = new JsonObject();
                l.add("text", text(net.minecraft.scoreboard.Team.decorateName(team, entry.name()), r));
                l.addProperty("score", entry.value());
                lines.add(l);
            }
            String sig = lines.toString();
            if (!sig.equals(lastSidebar)) {
                lastSidebar = sig;
                JsonObject o = head("sidebar");
                o.add("title", text(obj.getDisplayName(), r));
                o.add("lines", lines);
                write(o);
            }
        }
        worn(client, r);
        // 상자형 창: 처음 열 때와 칸이 바뀔 때
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            JsonArray slots = new JsonArray();
            for (Slot slot : screen.getScreenHandler().slots) {
                if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
                JsonObject s = new JsonObject();
                s.addProperty("slot", slot.id);
                s.add("item", item(slot.getStack(), r));
                slots.add(s);
            }
            String sig = slots.toString();
            if (screen != lastScreen || !sig.equals(lastScreenSig)) {
                lastScreen = screen;
                lastScreenSig = sig;
                JsonObject o = head("screen");
                o.add("title", text(screen.getTitle(), r));
                o.addProperty("plainTitle", screen.getTitle().getString());
                o.addProperty("size", screen.getScreenHandler().slots.size());
                o.addProperty("handler", String.valueOf(net.minecraft.registry.Registries.SCREEN_HANDLER.getId(
                        screenType(screen))));
                o.add("slots", slots);
                write(o);
            }
        } else {
            lastScreen = null;
            lastScreenSig = "";
        }
    }

    /**
     * 입은 장비·왼손을 원본 그대로 latest.log 에 남긴다(바꿔 입었을 때만, 내구도만 바뀐 건 빼고). 기록 폴더는 200MB 가 차서
     * 따로 적는다. 프레스티지 머리 치장을 쓰면 투구가 가죽 말 갑옷("렛서판다 모자")으로 와서, 진짜 내구도가 어디 있는지 보려고
     */
    private static void worn(MinecraftClient client, RegistryWrapper.WrapperLookup r) {
        PlayerInventory inv = client.player.getInventory();
        StringBuilder sig = new StringBuilder();
        for (int i = 36; i <= PlayerInventory.OFF_HAND_SLOT; i++) {
            ItemStack s = inv.getStack(i);
            sig.append(i).append(':').append(s.getItem()).append(':').append(s.getName().getString()).append(';');
        }
        if (sig.toString().equals(lastWorn)) return;
        lastWorn = sig.toString();
        for (int i = 36; i <= PlayerInventory.OFF_HAND_SLOT; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) DdingMate.LOG.info("[원본 기록] 입은 칸 {}: {}", i, item(s, r));
        }
    }

    private static net.minecraft.screen.ScreenHandlerType<?> screenType(HandledScreen<?> screen) {
        try {
            return screen.getScreenHandler().getType();
        } catch (UnsupportedOperationException e) {
            return null; // 플레이어 인벤토리처럼 종류가 없는 창
        }
    }

    private static JsonElement item(ItemStack stack, RegistryWrapper.WrapperLookup r) {
        return ItemStack.CODEC.encodeStart(r.getOps(JsonOps.INSTANCE), stack).result().orElse(new JsonObject());
    }

    private static JsonObject head(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("t", kind);
        o.addProperty("at", System.currentTimeMillis());
        return o;
    }

    private static void write(JsonObject o) {
        try {
            Path d = dir();
            Files.createDirectories(d);
            if (written < 0) {
                written = 0;
                try (var s = Files.list(d)) {
                    for (Path p : (Iterable<Path>) s::iterator) written += Files.size(p);
                }
            }
            if (written > MAX_BYTES) return;
            byte[] line = (o + "\n").getBytes(StandardCharsets.UTF_8);
            Path f = d.resolve(java.time.LocalDate.now() + ".jsonl");
            Files.write(f, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written += line.length;
        } catch (IOException e) {
            DdingMate.LOG.warn("개발용 기록을 남기지 못했어요: {}", e.toString());
        }
    }
}
