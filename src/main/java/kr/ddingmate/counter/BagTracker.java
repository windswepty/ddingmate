package kr.ddingmate.counter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kr.ddingmate.DdingMate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모험 가방 창이 열려 있는 동안 내용을 읽어 기억해 두고, 닫은 뒤에도 합산할 수 있게 한다.
 * 가방은 서버에 있어서 내 게임은 창을 열었을 때만 내용을 안다. 대신 열어 주거나 몰래 여는 일은 하지 않는다.
 *
 * <p>몇 번 가방인지는 화면에 나오지 않으므로 이렇게 알아낸다.
 * <ol>
 *   <li>내가 보낸 "ㄱ3", "/가방 3" 같은 메시지 바로 뒤에 열린 창 → 3번 가방. 이때 창 제목을 가방 제목으로 기억한다.</li>
 *   <li>번호 없이 가방 제목의 창이 열리면 → 기억해 둔 가방들과 칸 배치를 비교해 가장 비슷한 가방.</li>
 * </ol>
 * 기록은 계정·서버별로 config/ddingmate-bags.json 에 저장한다.
 */
public final class BagTracker {

    /** 단축어를 보낸 뒤 이 시간 안에 열린 창만 그 번호의 가방으로 본다. */
    private static final long PENDING_MS = 5000;
    /** 창이 열린 뒤 내용이 도착할 때까지 기다리는 틱 수 */
    private static final int SETTLE_TICKS = 6;
    /** 번호 없이 열렸을 때 같은 가방으로 볼 최소 칸 일치율 */
    private static final double MATCH_THRESHOLD = 0.6;

    public enum OpenState { NONE, BAG, UNKNOWN_BAG }

    static final class Bag {
        long seenAt;
        List<String> slots = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
    }

    static final class Account {
        List<String> titles = new ArrayList<>();
        Map<String, Bag> bags = new TreeMap<>();
        /** 아이템 저장소. 슬롯마다 보관 개수를 설명 문구에서 읽는다 */
        Bag storage;
    }

    private static final class Store {
        Map<String, Account> accounts = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Store store = new Store();
    private static boolean dirty;

    private static int pendingBag;
    private static long pendingAt;

    private static GenericContainerScreen openScreen;
    private static int openTicks;
    private static boolean openIsBag;
    private static int openBag;
    private static boolean openIsStorage;
    private static int ticks;

    private BagTracker() {}

    // ---------------------------------------------------------------- 이벤트

    /** 내가 보낸 채팅·명령. 읽기만 하고 막거나 바꾸지 않는다. command 는 앞의 / 가 빠진 채로 온다. */
    public static void onOutgoing(String text) {
        int n = parseBagNumber(text, DdingMate.config().counter.bagAliases);
        if (n > 0) {
            pendingBag = n;
            pendingAt = System.currentTimeMillis();
        }
    }

    /** "ㄱ3", "/ㄱ3", "가방 3", "/가방 3" → 3. 해당 없으면 0. */
    public static int parseBagNumber(String text, List<String> aliases) {
        if (text == null || aliases == null || aliases.isEmpty()) return 0;
        StringBuilder alt = new StringBuilder();
        for (String a : aliases) {
            if (a == null || a.isBlank()) continue;
            if (!alt.isEmpty()) alt.append('|');
            alt.append(Pattern.quote(a.trim()));
        }
        if (alt.isEmpty()) return 0;
        Matcher m = Pattern.compile("^/?(?:" + alt + ")\\s*([1-9])$").matcher(text.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null) {
            closeOpen();
            return;
        }
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            closeOpen();
            return;
        }
        if (screen != openScreen) {
            closeOpen();
            openScreen = screen;
            openTicks = 0;
            openIsBag = false;
            openBag = 0;
            openIsStorage = false;
        }
        openTicks++;
        if (openTicks == SETTLE_TICKS) decide(screen);
        if (openTicks >= SETTLE_TICKS && openBag > 0 && ticks % 5 == 0) record(screen, openBag);
        if (openTicks >= SETTLE_TICKS && openIsStorage && ticks % 5 == 0) recordStorage(screen);
    }

    // ---------------------------------------------------------------- 창 제목 글리프

    /**
     * 띵타이쿤 리소스팩은 가방 창 그림을 창 제목 속 글리프로 그린다.
     * 가방 내용 창: 글꼴 bag/ui/main 의 U+A001(가방 그림). 가방 목록 창은 U+A000.
     * 위쪽 가방 번호 단추: 글꼴 bag/ui/button 의 U+A00N(보통)·U+B00N(눌린 = 지금 열린 가방).
     * (2026-06~10 의 리소스팩 20개판에서 같은 이름공간·글자임을 확인)
     */
    static final String DDING_NS = "e94a3c2f-10aa-4d92-9ed0-c73f74fef20e";
    static final Identifier BAG_MAIN_FONT = Identifier.of(DDING_NS, "bag/ui/main");
    static final Identifier BAG_BUTTON_FONT = Identifier.of(DDING_NS, "bag/ui/button");

    /** bagUi: 가방 관련 창인가, bag: 가방 내용 창인가, number: 눌린 번호 단추(모르면 0) */
    record TitleSignal(boolean bagUi, boolean bag, int number) {}

    static TitleSignal readTitle(Text title) {
        boolean[] ui = {false};
        boolean[] bag = {false};
        int[] number = {0};
        int[] pressed = {0};
        title.visit((style, s) -> {
            Identifier font = style.getFont();
            if (BAG_MAIN_FONT.equals(font)) {
                ui[0] = true;
                if (s.indexOf('ꀁ') >= 0) bag[0] = true;
            } else if (BAG_BUTTON_FONT.equals(font)) {
                ui[0] = true;
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c >= '뀁' && c <= '뀉') {
                        number[0] = c - '뀀';
                        pressed[0]++;
                    }
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return new TitleSignal(ui[0], bag[0], pressed[0] == 1 ? number[0] : 0);
    }

    /** 창이 열리고 내용이 도착한 뒤 가방인지, 몇 번인지 정한다. */
    private static void decide(GenericContainerScreen screen) {
        Account acc = account();
        if (acc == null) return;
        boolean pending = pendingBag > 0 && System.currentTimeMillis() - pendingAt < PENDING_MS;
        // 1) 서버 창 제목의 가방 글리프로 판단 (가장 확실)
        TitleSignal sig = readTitle(screen.getTitle());
        if (sig.bagUi()) {
            if (sig.bag()) {
                openIsBag = true;
                openBag = sig.number() > 0 ? sig.number() : pending ? pendingBag : bestMatch(acc, slotsOf(screen));
            }
            pendingBag = 0;
            return;
        }
        // 2) 글리프가 없으면(리소스팩을 끈 경우 등) 단축어·기억한 제목·칸 비교로 판단
        String title = screen.getTitle().getString();
        if (title.contains("저장소")) {
            openIsStorage = true;
            pendingBag = 0;
            return;
        }
        if (pending) {
            // 이미 가방 제목을 알고 있는데 다른 제목의 창이 열렸다면 가방이 아닌 창(구매 안내 등)으로 본다.
            if (acc.titles.isEmpty() || acc.titles.contains(title)) {
                if (!title.isBlank() && !acc.titles.contains(title)) {
                    acc.titles.add(title);
                    dirty = true;
                }
                openIsBag = true;
                openBag = pendingBag;
            }
            pendingBag = 0;
            return;
        }
        if (acc.titles.contains(title) || title.contains("가방")) {
            openIsBag = true;
            openBag = bestMatch(acc, slotsOf(screen));
        }
    }

    private static void record(GenericContainerScreen screen, int number) {
        Account acc = account();
        if (acc == null) return;
        Bag bag = new Bag();
        bag.seenAt = System.currentTimeMillis();
        bag.slots = slotsOf(screen);
        Inventory inv = screen.getScreenHandler().getInventory();
        int size = containerSize(screen);
        for (int i = 0; i < size; i++) ItemKeys.add(bag.counts, inv.getStack(i));
        acc.bags.put(String.valueOf(number), bag);
        dirty = true;
    }

    /**
     * 아이템 저장소: 한 슬롯에 수천 개가 들어가서 아이템 묶음 개수가 아니라 설명 문구의 보관 개수를 읽는다.
     * 문구에서 못 찾으면 묶음 개수를 쓴다. (실제 문구 형식은 서버에서 확인 필요)
     */
    private static void recordStorage(GenericContainerScreen screen) {
        Account acc = account();
        if (acc == null) return;
        Bag bag = new Bag();
        bag.seenAt = System.currentTimeMillis();
        bag.slots = slotsOf(screen);
        Inventory inv = screen.getScreenHandler().getInventory();
        int size = containerSize(screen);
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            int stored = ItemKeys.storedAmount(s);
            bag.counts.merge(ItemKeys.keyOf(s), stored >= 0 ? stored : s.getCount(), Integer::sum);
            IconCache.remember(s);
        }
        acc.storage = bag;
        dirty = true;
    }

    private static void closeOpen() {
        if (openScreen != null) {
            openScreen = null;
            openIsBag = false;
            openBag = 0;
            openIsStorage = false;
            save();
        }
    }

    // ---------------------------------------------------------------- 조회

    /** 기록된 모든 가방의 아이템 합계 (현재 계정·서버) */
    public static Map<String, Integer> totals() {
        Map<String, Integer> sum = new HashMap<>();
        Account acc = account();
        if (acc == null) return sum;
        for (Bag b : acc.bags.values()) b.counts.forEach((k, v) -> sum.merge(k, v, Integer::sum));
        return sum;
    }

    /** 기록된 아이템 저장소의 아이템 합계 */
    public static Map<String, Integer> storageTotals() {
        Account acc = account();
        return acc == null || acc.storage == null ? Map.of() : Map.copyOf(acc.storage.counts);
    }

    /** 아이템 저장소를 마지막으로 본 시각(ms). 기록이 없으면 0 */
    public static long storageSeenAt() {
        Account acc = account();
        return acc == null || acc.storage == null ? 0 : acc.storage.seenAt;
    }

    public static boolean storageOpen() {
        return openScreen != null && openIsStorage;
    }

    public static int recordedCount() {
        Account acc = account();
        return acc == null ? 0 : acc.bags.size();
    }

    /** 가장 오래된 기록 시각(ms). 기록이 없으면 0 */
    public static long oldestSeenAt() {
        Account acc = account();
        if (acc == null) return 0;
        return acc.bags.values().stream().mapToLong(b -> b.seenAt).min().orElse(0);
    }

    /** 번호 → 기록 시각. 명령어 목록 출력용 */
    public static Map<Integer, Long> recordedBags() {
        Map<Integer, Long> out = new TreeMap<>();
        Account acc = account();
        if (acc != null) acc.bags.forEach((k, b) -> out.put(Integer.parseInt(k), b.seenAt));
        return out;
    }

    public static List<String> learnedTitles() {
        Account acc = account();
        return acc == null ? List.of() : List.copyOf(acc.titles);
    }

    public static OpenState openState() {
        if (openScreen == null || !openIsBag) return OpenState.NONE;
        return openBag > 0 ? OpenState.BAG : OpenState.UNKNOWN_BAG;
    }

    public static int openBagNumber() {
        return openBag;
    }

    /** 현재 계정·서버의 가방 기록과 기억한 가방 제목을 지운다. */
    public static void clearCurrent() {
        String key = accountKey();
        if (key != null && store.accounts.remove(key) != null) {
            dirty = true;
            save();
        }
    }

    // ---------------------------------------------------------------- 비교

    private static int bestMatch(Account acc, List<String> slots) {
        int best = 0;
        double bestScore = -1;
        long bestSeen = -1;
        for (Map.Entry<String, Bag> e : acc.bags.entrySet()) {
            double score = similarity(slots, e.getValue().slots);
            if (score < MATCH_THRESHOLD) continue;
            if (score > bestScore || (score == bestScore && e.getValue().seenAt > bestSeen)) {
                best = Integer.parseInt(e.getKey());
                bestScore = score;
                bestSeen = e.getValue().seenAt;
            }
        }
        return best;
    }

    /** 두 칸 배치에서 아이템이 있는 칸 중 같은 칸의 비율 */
    static double similarity(List<String> a, List<String> b) {
        int n = Math.max(a.size(), b.size());
        int used = 0;
        int same = 0;
        for (int i = 0; i < n; i++) {
            String x = i < a.size() ? a.get(i) : "";
            String y = i < b.size() ? b.get(i) : "";
            if (x.isEmpty() && y.isEmpty()) continue;
            used++;
            if (x.equals(y)) same++;
        }
        return used == 0 ? 0 : (double) same / used;
    }

    private static List<String> slotsOf(GenericContainerScreen screen) {
        Inventory inv = screen.getScreenHandler().getInventory();
        int size = containerSize(screen);
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getStack(i);
            out.add(s.isEmpty() ? "" : ItemKeys.keyOf(s) + "*" + s.getCount());
        }
        return out;
    }

    private static int containerSize(GenericContainerScreen screen) {
        GenericContainerScreenHandler h = screen.getScreenHandler();
        return Math.min(h.getRows() * 9, h.getInventory().size());
    }

    // ---------------------------------------------------------------- 저장

    private static String accountKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        UUID id = client.player != null ? client.player.getUuid() : null;
        if (id == null) return null;
        ServerInfo server = client.getCurrentServerEntry();
        String where = server == null ? "singleplayer" : server.address.toLowerCase(Locale.ROOT);
        return id + "@" + where;
    }

    private static Account account() {
        String key = accountKey();
        return key == null ? null : store.accounts.computeIfAbsent(key, k -> new Account());
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ddingmate-bags.json");
    }

    public static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Store s = GSON.fromJson(r, Store.class);
            if (s != null && s.accounts != null) {
                for (Account a : s.accounts.values()) {
                    if (a.titles == null) a.titles = new ArrayList<>();
                    if (a.bags == null) a.bags = new TreeMap<>();
                    a.bags.values().removeIf(b -> b == null || b.counts == null || b.slots == null);
                    if (a.storage != null && (a.storage.counts == null || a.storage.slots == null)) a.storage = null;
                }
                store = s;
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("가방 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    public static void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(store, w);
            }
            dirty = false;
        } catch (IOException e) {
            DdingMate.LOG.error("가방 기록 저장 실패", e);
        }
    }
}
