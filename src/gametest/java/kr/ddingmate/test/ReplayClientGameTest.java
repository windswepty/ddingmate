package kr.ddingmate.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.ddingmate.DdingMate;
import kr.ddingmate.notify.AlertLog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 실제 플레이 기록 재생 시험(build/testdata 가 있을 때만, 또는 DDINGMATE_TESTDATA=폴더). 저장소에는 넣지 않는 내 컴퓨터 자료:
 * <ul>
 * <li>chat-corpus*.tsv: 지금까지 모든 플레이 로그의 채팅 줄(서버 안내·사람 채팅, 순서 그대로) → 실제 채팅 이벤트로 흘려 보낸다</li>
 * <li>ddingmate-screens.txt: 예전에 기록한 서버 창(칸 이름·설명·모양 번호) → 상자 창으로 다시 만들어 연다</li>
 * <li>record/*.jsonl: 개발용 원본 기록(DevRecord: 글꼴·모양 정보까지) → 채팅·보스바·창을 똑같이 다시 만든다</li>
 * </ul>
 * 오류 없이 다 지나가야 하고, 무엇이 어떤 줄에서 알림을 냈는지 build/testdata/replay-report.txt 에 적는다(잘못 울린 알림 찾기).
 * 재생 동안 바뀐 설정·기록 파일은 끝나면 원래대로 돌려놓는다.
 */
public class ReplayClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path data = dataDir();
        if (data == null) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            Map<Path, byte[]> backup = new LinkedHashMap<>();
            StringBuilder report = new StringBuilder("띵메이트 실제 기록 재생 보고 " + java.time.LocalDateTime.now().withNano(0) + "\n\n");
            ctx.runOnClient(c -> {
                backup.putAll(backupConfig());
                freshInstall();
            });

            // 1) 채팅 전부
            ctx.runOnClient(c -> replayChat(c, data, report));
            // 2) 예전 창 기록(글만)
            List<Window> windows = readScreens(data.resolve("ddingmate-screens.txt"));
            report.append("\n== 창 기록 ").append(windows.size()).append("개 다시 열기\n");
            for (Window w : windows) {
                ctx.runOnClient(c -> open(c, Text.literal(w.title), w.stacks));
                ctx.waitTicks(12);
                ctx.runOnClient(c -> report.append(describe(c, w.title)).append('\n'));
            }
            // 3) 원본 기록(DevRecord)
            List<JsonObject> records = readRecords(data.resolve("record"));
            report.append("\n== 원본 기록 ").append(records.size()).append("줄\n");
            for (JsonObject r : records) {
                String kind = r.get("t").getAsString();
                ctx.runOnClient(c -> replayRecord(c, r, kind, report));
                if (kind.equals("screen")) {
                    ctx.waitTicks(12);
                    ctx.runOnClient(c -> report.append(describe(c, r.get("plainTitle").getAsString())).append('\n'));
                }
            }
            ctx.runOnClient(c -> {
                c.setScreen(null);
                // 재생한 서버 HUD(보스바)·제목·채팅을 비운다: 남겨 두면 다음 시험 월드에서도 띵타이쿤 HUD 가 떠 있었다
                ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars().clear();
                c.inGameHud.clearTitle();
                c.inGameHud.getChatHud().clear(false);
                restoreConfig(backup);
                try {
                    Files.writeString(data.resolve("replay-report.txt"), report, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                DdingMate.LOG.info("실제 기록 재생 보고: {}", data.resolve("replay-report.txt"));
            });
        }
    }

    // ---------------------------------------------------------------- 채팅

    private static void replayChat(MinecraftClient c, Path data, StringBuilder report) {
        List<Path> files;
        try (Stream<Path> s = Files.list(data)) {
            files = s.filter(p -> p.getFileName().toString().startsWith("chat-corpus") && p.toString().endsWith(".tsv")).sorted().toList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Map<String, Integer> byTitle = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        Map<String, Integer> changes = new LinkedHashMap<>();
        Map<String, List<String>> changeEx = new LinkedHashMap<>();
        Map<String, String> state = snapshot();
        int lines = 0;
        long t0 = System.currentTimeMillis();
        AlertLog.Entry top = AlertLog.entries().isEmpty() ? null : AlertLog.entries().get(0);
        for (Path f : files) {
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length < 3) continue;
                    String msg = parts[2].replace("\\\\", "\\");
                    lines++;
                    try {
                        ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(Text.literal(msg), false);
                    } catch (Throwable e) {
                        throw new AssertionError("채팅 재생 중 오류: " + parts[1] + " " + parts[0] + " " + msg, e);
                    }
                    // 이 줄로 바뀐 상태(할 일 진행·골렘·오션오더·제작 줄·닉네임·핀·접속 시간): 잘못 반응한 줄 찾기
                    Map<String, String> after = snapshot();
                    for (Map.Entry<String, String> e : after.entrySet()) {
                        String before = state.get(e.getKey());
                        if (java.util.Objects.equals(before, e.getValue())) continue;
                        String key = e.getKey();
                        changes.merge(key, 1, Integer::sum);
                        List<String> ex = changeEx.computeIfAbsent(key, k -> new ArrayList<>());
                        if (ex.size() < 4) ex.add(before + " → " + e.getValue() + " · " + parts[1] + " " + parts[0] + " | "
                                + kr.ddingmate.counter.ItemKeys.stripGlyphs(msg));
                    }
                    state = after;
                    List<AlertLog.Entry> es = AlertLog.entries();
                    AlertLog.Entry now = es.isEmpty() ? null : es.get(0);
                    if (now != top) {
                        // 이 줄 하나로 생긴 알림들(맨 앞부터 이전 맨 앞 전까지)
                        for (AlertLog.Entry e : es) {
                            if (e == top) break;
                            String key = e.kind + " · " + e.title;
                            byTitle.merge(key, 1, Integer::sum);
                            List<String> ex = examples.computeIfAbsent(key, k -> new ArrayList<>());
                            if (ex.size() < 4) ex.add(parts[1] + " " + parts[0] + " | " + kr.ddingmate.counter.ItemKeys.stripGlyphs(msg));
                        }
                        top = now;
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        }
        report.append("== 채팅 ").append(lines).append("줄 재생 (").append(System.currentTimeMillis() - t0).append("ms)\n");
        report.append("알림이 생긴 줄(종류 · 제목 = 횟수, 예시):\n");
        byTitle.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            report.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            for (String ex : examples.get(e.getKey())) report.append("      ").append(ex.length() > 220 ? ex.substring(0, 220) : ex).append('\n');
        });
        report.append("상태가 바뀐 줄(항목 = 횟수, 예시):\n");
        changes.forEach((k, v) -> {
            report.append("  ").append(k).append(" = ").append(v).append('\n');
            for (String ex : changeEx.get(k)) report.append("      ").append(ex.length() > 240 ? ex.substring(0, 240) : ex).append('\n');
        });
        report.append("끝난 뒤 상태: ").append(snapshot()).append('\n');
        if (lines == 0) throw new AssertionError("재생할 채팅이 없음: " + data);
    }

    /** 줄마다 비교할 상태 */
    private static Map<String, String> snapshot() {
        Map<String, String> m = new java.util.TreeMap<>();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(kr.ddingmate.schedule.ScheduleEngine.KST);
        for (var t : DdingMate.config().todo.items) m.put("할 일 " + t.name, String.valueOf(kr.ddingmate.todo.TodoList.progress(t, now)));
        long ms = System.currentTimeMillis();
        m.put("광석 골렘 쿨타임", kr.ddingmate.expert.OreGolem.remaining(ms) > 0 ? "켜짐" : "꺼짐");
        m.put("오션오더", kr.ddingmate.expert.OceanOrder.active(ms) + " 오늘 " + kr.ddingmate.expert.OceanOrder.todayCount());
        m.put("제작 줄", String.valueOf(kr.ddingmate.craft.CraftTimers.groups().size()));
        m.put("한글 닉네임", String.valueOf(kr.ddingmate.game.Nickname.get()));
        m.put("핀", DdingMate.config().counter.pins.stream().map(p -> p.recipe).toList().toString());
        m.put("접속 보상 받은 것", String.valueOf(kr.ddingmate.playtime.PlaytimeTracker.unclaimedRewards()));
        m.put("재부팅 예고", String.valueOf(kr.ddingmate.game.ServerHud.rebootAt() > 0));
        return m;
    }

    /** 처음 설치한 상태로: 띵메이트 설정·기록 파일을 지우고 다시 읽는다(백업은 끝나면 되돌림) */
    private static void freshInstall() {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) Files.delete(p);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DdingMate.reloadConfig();
        // 고정 핀 시험: 핀 1 = 강화 횃불(고정 안 함) — 제작 시작 줄에서 빠져야 한다
        var torch = kr.ddingmate.counter.RecipeBook.recipeFor("강화 횃불");
        if (torch != null) DdingMate.config().counter.pins.get(0).recipe = torch.id;
        kr.ddingmate.craft.CraftTimers.debugClear();
        kr.ddingmate.expert.OreGolem.debugReset();
        kr.ddingmate.expert.OceanOrder.debugReset();
        kr.ddingmate.expert.PetFish.debugReset();
        kr.ddingmate.game.Nickname.debugReset();
        AlertLog.clear();
    }

    // ---------------------------------------------------------------- 예전 창 기록(ddingmate-screens.txt)

    private record Window(String title, Map<Integer, ItemStack> stacks) {}

    private static final java.util.regex.Pattern SLOT = java.util.regex.Pattern.compile(
            "^\\s*\\[(\\d+)] (.*?) ×(\\d+) <([a-z0-9_]+)(?: (\\d+))?(?: [^>]*)?>(.*)$");

    private static List<Window> readScreens(Path p) {
        List<Window> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        try {
            String title = null;
            Map<Integer, ItemStack> stacks = null;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.startsWith("== 창: ")) {
                    if (title != null) out.add(new Window(title, stacks));
                    String t = line.substring(5);
                    int at = t.lastIndexOf(" (");
                    title = at > 0 ? t.substring(0, at) : t;
                    stacks = new LinkedHashMap<>();
                    continue;
                }
                java.util.regex.Matcher m = SLOT.matcher(line);
                if (!m.find() || stacks == null) continue;
                int slot = Integer.parseInt(m.group(1));
                if (slot >= 54) continue;
                Item item = Registries.ITEM.get(Identifier.ofVanilla(m.group(4)));
                ItemStack s = new ItemStack(item, Math.max(1, Math.min(99, Integer.parseInt(m.group(3)))));
                if (!m.group(2).isEmpty()) s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(m.group(2)).setStyle(Style.EMPTY.withItalic(false)));
                if (m.group(5) != null) s.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                        new CustomModelDataComponent(List.of(Float.parseFloat(m.group(5))), List.of(), List.of(), List.of()));
                List<Text> lore = new ArrayList<>();
                for (String l : m.group(6).split(" \\| ")) if (!l.isBlank()) lore.add(Text.literal(l.trim()));
                if (!lore.isEmpty()) s.set(DataComponentTypes.LORE, new LoreComponent(lore));
                stacks.put(slot, s);
            }
            if (title != null) out.add(new Window(title, stacks));
        } catch (Exception e) {
            throw new AssertionError("창 기록을 못 읽음", e);
        }
        return out;
    }

    private static void open(MinecraftClient c, Text title, Map<Integer, ItemStack> stacks) {
        SimpleInventory inv = new SimpleInventory(54);
        stacks.forEach((k, v) -> {
            if (k < 54) inv.setStack(k, v);
        });
        GenericContainerScreenHandler h = GenericContainerScreenHandler.createGeneric9x6(1, c.player.getInventory(), inv);
        c.setScreen(new GenericContainerScreen(h, c.player.getInventory(), title));
    }

    /** 이 창에서 띵메이트가 알아본 것 한 줄 */
    private static String describe(MinecraftClient c, String title) {
        StringBuilder sb = new StringBuilder("  ");
        sb.append(kr.ddingmate.counter.ItemKeys.stripGlyphs(title).trim()).append(" → ");
        List<String> got = new ArrayList<>();
        if (!kr.ddingmate.craft.FacilityPanel.entries().isEmpty())
            got.add("시설 판 " + kr.ddingmate.craft.FacilityPanel.entries().size() + "줄(" + kr.ddingmate.craft.FacilityPanel.entries().get(0).recipe().facility + ")");
        if (kr.ddingmate.counter.BagTracker.storageOpen()) got.add("아이템 저장소");
        if (kr.ddingmate.expert.PetFish.current() != null) got.add("반려어 " + kr.ddingmate.expert.PetFish.current().name);
        if (kr.ddingmate.expert.OceanOrder.active(System.currentTimeMillis())) got.add("오션오더 진행");
        int jobs = kr.ddingmate.craft.CraftTimers.groups().size();
        if (jobs > 0) got.add("제작 줄 " + jobs);
        sb.append(got.isEmpty() ? "(알아본 것 없음)" : String.join(", ", got));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 원본 기록(DevRecord)

    /** 원본 기록에서 같은 모양(숫자만 다름) 줄을 몇 개까지 다시 보낼지 */
    private static final int SAME_SHAPE_MAX = 30;

    private static List<JsonObject> readRecords(Path dir) {
        List<JsonObject> out = new ArrayList<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    // 같은 모양(숫자만 다름) 줄은 몇 개만: 잠수 채널 "299초 뒤 잠수 포인트…" 액션바가 하루 수만 줄이라
                    // 줄마다 게임에 다시 보내면 20분 넘게 걸렸다(2026-10-24). 창(screen)은 모두 다시 연다
                    String shape = line.replaceAll("\\d", "#").replaceAll("\"at\":#+", "");
                    if (!line.contains("\"t\":\"screen\"") && seen.merge(shape, 1, Integer::sum) > SAME_SHAPE_MAX) continue;
                    out.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("원본 기록을 못 읽음", e);
        }
        return out;
    }

    private static void replayRecord(MinecraftClient c, JsonObject r, String kind, StringBuilder report) {
        var regs = c.world.getRegistryManager();
        try {
            switch (kind) {
                case "chat", "overlay" -> ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(
                        Text.Serialization.fromJsonTree(r.get("text"), regs), kind.equals("overlay"));
                case "title" -> c.inGameHud.setTitle(Text.Serialization.fromJsonTree(r.get("text"), regs));
                case "subtitle" -> c.inGameHud.setSubtitle(Text.Serialization.fromJsonTree(r.get("text"), regs));
                case "bossbar" -> {
                    java.util.UUID id = java.util.UUID.fromString(r.get("id").getAsString());
                    var bars = ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars();
                    bars.put(id, new net.minecraft.client.gui.hud.ClientBossBar(id, Text.Serialization.fromJsonTree(r.get("text"), regs),
                            r.has("percent") ? r.get("percent").getAsFloat() : 1f, net.minecraft.entity.boss.BossBar.Color.WHITE,
                            net.minecraft.entity.boss.BossBar.Style.PROGRESS, false, false, false));
                }
                case "bossbars" -> {
                    java.util.Set<java.util.UUID> keep = new java.util.HashSet<>();
                    for (var e : r.getAsJsonArray("ids")) keep.add(java.util.UUID.fromString(e.getAsString()));
                    ((kr.ddingmate.mixin.BossBarHudAccessor) c.inGameHud.getBossBarHud()).ddingmate$bossBars().keySet().retainAll(keep);
                }
                case "screen" -> {
                    Map<Integer, ItemStack> stacks = new LinkedHashMap<>();
                    for (var e : r.getAsJsonArray("slots")) {
                        JsonObject s = e.getAsJsonObject();
                        ItemStack.CODEC.parse(regs.getOps(com.mojang.serialization.JsonOps.INSTANCE), s.get("item")).result()
                                .ifPresent(st -> stacks.put(s.get("slot").getAsInt(), st));
                    }
                    open(c, Text.Serialization.fromJsonTree(r.get("title"), regs), stacks);
                }
                default -> { /* sidebar 등: 지금은 보고만 */ }
            }
        } catch (Throwable e) {
            throw new AssertionError("원본 기록 재생 중 오류: " + r, e);
        }
    }

    // ---------------------------------------------------------------- 설정 되돌리기

    private static Map<Path, byte[]> backupConfig() {
        Map<Path, byte[]> out = new LinkedHashMap<>();
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) {
                out.put(p, Files.readAllBytes(p));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return out;
    }

    private static void restoreConfig(Map<Path, byte[]> backup) {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ddingmate") && Files.isRegularFile(p)).toList()) {
                if (!backup.containsKey(p)) Files.delete(p);
            }
            for (Map.Entry<Path, byte[]> e : backup.entrySet()) Files.write(e.getKey(), e.getValue());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        DdingMate.reloadConfig();
    }

    /** build/testdata(시험은 build/run/clientGameTest 에서 돈다) 또는 DDINGMATE_TESTDATA */
    private static Path dataDir() {
        String env = System.getenv("DDINGMATE_TESTDATA");
        Path p = env != null ? Path.of(env) : Path.of("").toAbsolutePath().resolve("../../testdata").normalize();
        return Files.isDirectory(p) ? p : null;
    }
}
