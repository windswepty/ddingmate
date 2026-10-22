package kr.ddingmate.expert;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.ddingmate.DdingMate;
import kr.ddingmate.notify.Notifier;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마을 경작지의 아일랜드 작물(토마토·양파·마늘) 타이머.
 * <p>
 * 띵타이쿤 리소스팩(2026-10-21)은 아일랜드 작물을 트립와이어 블록 상태로 그린다: blockstates/tripwire.json 의 상태마다
 * "crops/crops/tomato/tomato_stage4" 같은 모델. 지금 켜진 리소스팩에서 이 짝을 읽어(서버가 팩을 바꿔도 따라감) 주변 블록이
 * 몇 단계인지 본다. 위키 아일랜드 농사(2026-10-22): 다 자라기까지 15분, 토마토 6단계·양파·마늘 4단계, 마을에 사람이 있을 때만 자람.
 * → 단계마다 15분 ÷ (단계 수 − 1). 단계가 바뀌는 걸 보면 그 순간으로 정확히 맞추고, 마을 밖에 있는 동안은 멈춘다.
 * 블록을 읽기만 하고 서버로는 아무것도 보내지 않는다.
 */
public final class IslandCrops {

    private IslandCrops() {}

    public static final long GROW_MS = 15 * 60_000L;
    /** 작물 종류(모델 폴더 이름) → 화면 이름 */
    static final Map<String, String> NAMES = Map.of("tomato", "토마토", "onion", "양파", "garlic", "마늘");
    private static final Pattern MODEL = Pattern.compile("crops/crops/(tomato|onion|garlic)/\\1_stage(\\d+)$");
    static final int SCAN_CHUNKS = 6;

    record Stage(String crop, int stage, int max) {}

    /** 트립와이어 상태 → 작물 단계(리소스팩에서 읽음) */
    private static Map<BlockState, Stage> table = Map.of();
    private static boolean tableLoaded;

    static final class Plant {
        String crop;
        int stage;
        int max;
        /** 다 자라는 예상 시각 */
        long readyAt;
        /** 단계가 바뀌는 걸 직접 봐서 정확한가 */
        boolean exact;
        boolean notified;
    }

    private static final Map<BlockPos, Plant> PLANTS = new HashMap<>();
    private static Object world;
    private static long pausedSince;
    /** 이 종류가 다 자랐다고 이미 알렸나 */
    private static final Map<String, Boolean> ANNOUNCED = new HashMap<>();

    // ---------------------------------------------------------------- 리소스팩에서 단계 표 읽기

    /** 리소스를 다시 읽으면(서버 리소스팩을 받거나 바꾸면) 표를 새로 만든다 */
    public static void onResourceReload() {
        tableLoaded = false;
    }

    private static Map<BlockState, Stage> table() {
        if (tableLoaded) return table;
        tableLoaded = true;
        Map<BlockState, Stage> out = new HashMap<>();
        try {
            Resource res = MinecraftClient.getInstance().getResourceManager()
                    .getResource(Identifier.ofVanilla("blockstates/tripwire.json")).orElse(null);
            if (res != null) {
                try (Reader r = res.getReader()) {
                    JsonObject variants = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("variants");
                    Map<String, Stage> byKey = new HashMap<>();
                    Map<String, Integer> max = new HashMap<>();
                    for (String key : variants.keySet()) {
                        var v = variants.get(key);
                        JsonObject o = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                        if (!o.has("model")) continue;
                        Matcher m = MODEL.matcher(o.get("model").getAsString());
                        if (!m.find()) continue;
                        int st = Integer.parseInt(m.group(2));
                        byKey.put(key, new Stage(m.group(1), st, 0));
                        max.merge(m.group(1), st, Math::max);
                    }
                    for (BlockState s : Blocks.TRIPWIRE.getStateManager().getStates()) {
                        for (Map.Entry<String, Stage> e : byKey.entrySet()) {
                            if (!matches(s, e.getKey())) continue;
                            Stage st = e.getValue();
                            out.put(s, new Stage(st.crop(), st.stage(), max.get(st.crop())));
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            DdingMate.LOG.warn("아일랜드 작물 표를 읽지 못했어요", e);
        }
        table = out;
        if (!out.isEmpty()) DdingMate.LOG.info("아일랜드 작물 단계 표: 트립와이어 상태 {}개", out.size());
        return table;
    }

    /** "east=true,west=false,…" 의 모든 속성이 이 상태와 같은가 */
    private static boolean matches(BlockState s, String key) {
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            Property<?> p = s.getBlock().getStateManager().getProperty(part.substring(0, eq).trim());
            if (p == null || !name(s, p).equals(part.substring(eq + 1).trim())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> String name(BlockState s, Property<T> p) {
        return p.name(s.get(p));
    }

    // ---------------------------------------------------------------- 주변 읽기

    /** 1초마다: 주변 청크의 트립와이어 중 작물 단계를 읽는다 */
    public static void tick(MinecraftClient client) {
        if (!DdingMate.config().expert.islandCrops || client.world == null || client.player == null) return;
        long now = System.currentTimeMillis();
        if (client.world != world) {
            world = client.world;
            PLANTS.clear();
            ANNOUNCED.clear();
        }
        // 마을 밖(다른 지역으로 옮김)이면 자라지 않는다: 그동안 끝나는 시각을 미룬다
        kr.ddingmate.game.ServerHud.Area area = kr.ddingmate.game.ServerHud.area();
        boolean away = area != null && area != kr.ddingmate.game.ServerHud.Area.VILLAGE;
        if (away) {
            if (pausedSince == 0) pausedSince = now;
            return;
        }
        if (pausedSince > 0) {
            long gap = now - pausedSince;
            for (Plant p : PLANTS.values()) p.readyAt += gap;
            pausedSince = 0;
        }
        Map<BlockState, Stage> t = table();
        if (t.isEmpty()) return;
        ChunkPos center = client.player.getChunkPos();
        for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
            for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) {
                WorldChunk chunk = (WorldChunk) client.world.getChunkManager().getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                scanChunk(client, chunk, t, now);
            }
        }
        announce(now);
    }

    private static void scanChunk(MinecraftClient client, WorldChunk chunk, Map<BlockState, Stage> t, long now) {
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        // 이 청크에서 본 칸(없어진 작물 = 수확한 것)
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection sec = sections[i];
            if (sec == null || sec.isEmpty() || !sec.hasAny(s -> s.isOf(Blocks.TRIPWIRE))) continue;
            int baseY = client.world.sectionIndexToCoord(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Stage st = t.get(sec.getBlockState(x, y, z));
                        if (st == null) continue;
                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        seen.add(pos);
                        observe(pos, st, now);
                    }
                }
            }
        }
        PLANTS.keySet().removeIf(p -> (p.getX() >> 4) == chunk.getPos().x && (p.getZ() >> 4) == chunk.getPos().z && !seen.contains(p));
    }

    static long stageMs(int max) {
        return max <= 1 ? GROW_MS : GROW_MS / (max - 1);
    }

    /** 한 칸을 봤다 */
    static void observe(BlockPos pos, Stage st, long now) {
        Plant p = PLANTS.get(pos);
        long step = stageMs(st.max());
        if (p == null || p.crop == null || !p.crop.equals(st.crop()) || st.stage() < p.stage) {
            // 처음 보거나 새로 심음: 1단계면 막 심은 것(정확), 그 밖에는 이 단계가 막 시작됐다고 보고(늦게 알리는 쪽) 센다
            p = new Plant();
            p.crop = st.crop();
            p.max = st.max();
            p.stage = st.stage();
            p.readyAt = now + (long) (st.max() - st.stage()) * step;
            p.exact = st.stage() == 1;
            p.notified = st.stage() >= st.max();
            PLANTS.put(pos, p);
            ANNOUNCED.remove(st.crop());
            return;
        }
        if (st.stage() > p.stage) {
            // 단계가 바뀌는 순간을 봤다 → 정확히
            p.stage = st.stage();
            p.readyAt = now + (long) (st.max() - st.stage()) * step;
            p.exact = true;
        }
    }

    /** 한 종류가 모두 다 자라면 한 번 알린다 */
    private static void announce(long now) {
        for (Map.Entry<String, Summary> e : summaries(now).entrySet()) {
            Summary s = e.getValue();
            boolean all = s.ready() == s.count();
            if (all && !ANNOUNCED.getOrDefault(e.getKey(), false)) {
                ANNOUNCED.put(e.getKey(), true);
                boolean fresh = PLANTS.values().stream().anyMatch(p -> p.crop.equals(e.getKey()) && !p.notified);
                PLANTS.values().stream().filter(p -> p.crop.equals(e.getKey())).forEach(p -> p.notified = true);
                if (fresh) Notifier.cropsReady(NAMES.get(e.getKey()), s.count());
            } else if (!all) {
                ANNOUNCED.put(e.getKey(), false);
            }
        }
    }

    // ---------------------------------------------------------------- HUD·탭

    /** 종류별: 심은 칸 수, 다 자란 칸 수, 마지막 칸이 다 자라는 시각, 모두 정확한가 */
    public record Summary(String name, int count, int ready, long allReadyAt, boolean exact) {}

    public static Map<String, Summary> summaries(long now) {
        Map<String, Summary> out = new LinkedHashMap<>();
        for (String crop : List.of("tomato", "onion", "garlic")) {
            List<Plant> ps = new ArrayList<>();
            for (Plant p : PLANTS.values()) if (crop.equals(p.crop)) ps.add(p);
            if (ps.isEmpty()) continue;
            int ready = 0;
            long last = 0;
            boolean exact = true;
            for (Plant p : ps) {
                boolean done = p.stage >= p.max || now >= p.readyAt;
                if (done) ready++;
                else {
                    last = Math.max(last, p.readyAt);
                    exact &= p.exact;
                }
            }
            out.put(crop, new Summary(NAMES.get(crop), ps.size(), ready, last, exact));
        }
        return out;
    }

    /** HUD 오른쪽: "다 자람!" · "6분 뒤" · "약 6분 뒤"(단계 시작을 못 봐서 넉넉히 잡은 값) */
    public static String status(Summary s, long now) {
        if (s.ready() == s.count()) return "다 자람!";
        long min = Math.max(1, (s.allReadyAt() - now + 59_999) / 60_000);
        return (s.exact() ? "" : "최대 ") + min + "분 뒤";
    }

    public static String label(Summary s) {
        return s.name() + " " + s.count() + "칸" + (s.ready() > 0 && s.ready() < s.count() ? " (수확 " + s.ready() + ")" : "");
    }

    public static boolean tableReady() {
        return !table().isEmpty();
    }

    // ---------------------------------------------------------------- 시험용

    public static void debugReset() {
        PLANTS.clear();
        ANNOUNCED.clear();
        pausedSince = 0;
    }

    public static void debugObserve(BlockPos pos, String crop, int stage, int max, long now) {
        observe(pos, new Stage(crop, stage, max), now);
    }

    public static void debugAnnounce(long now) {
        announce(now);
    }

    public static int debugTableSize() {
        tableLoaded = false;
        return table().size();
    }

    /** 시험용: 리소스팩에서 읽은 표의 작물별 최대 단계 */
    public static Map<String, Integer> debugMaxStages() {
        Map<String, Integer> m = new HashMap<>();
        for (Stage s : table().values()) m.merge(s.crop(), s.max(), Math::max);
        return m;
    }
}
