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
    /** 창이 열린 뒤 내용이 도착할 때까지 기다리는 최소 틱 수 */
    private static final int SETTLE_TICKS = 6;
    /**
     * 내용(칸)이 하나도 안 왔으면 이만큼(2초)까지 판단을 미룬다. 실제 서버에서 가방 창은 열린 뒤 0.5초쯤 지나 내용이 왔고
     * (2026-10-22 개발 기록), 0.3초에 빈 창으로 판단해 번호를 못 정하면 그 창을 닫을 때까지 기록하지 않아 가끔 기록이 씹혔다
     */
    private static final int WAIT_CONTENT_TICKS = 40;
    /** 번호 없이 열렸을 때 같은 가방으로 볼 최소 칸 일치율 */
    private static final double MATCH_THRESHOLD = 0.6;

    public enum OpenState { NONE, BAG, UNKNOWN_BAG }

    static final class Bag {
        long seenAt;
        /** 번호를 모른 채(명령어·눌린 번호 단추 없이) 내용으로 짐작해 붙인 번호인가. 진짜 번호를 알면 자리를 옮길 수 있다 */
        boolean guessed;
        List<String> slots = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
    }

    static final class Account {
        List<String> titles = new ArrayList<>();
        Map<String, Bag> bags = new TreeMap<>();
        /** 가방 창 제목의 번호 단추 수 = 가진 가방 수(0 이면 아직 모름). 이보다 큰 번호의 기록은 있을 수 없다 */
        int bagCount;
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
    /** 지금 창이 가방인지·몇 번인지 판단을 마쳤는가 */
    private static boolean decided;
    private static boolean openIsBag;
    private static int openBag;
    /** 지금 연 가방의 번호가 짐작한 번호인가(기록할 때 함께 남긴다) */
    private static boolean openGuessed;
    /** resolveBag 이 마지막으로 정한 번호가 짐작한 번호인가 */
    private static boolean resolvedGuessed;
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

    /** 띵타이쿤 가방 명령어(설정 없이 늘 알아본다): /ㄱ1, /가방 2, /.ㄱ2 (사용자 명령 기록에서 확인) */
    private static final Pattern BUILT_IN = Pattern.compile("^/?\\.?(?:ㄱ|가방)\\s*([1-9])$");

    /**
     * "ㄱ3", "/ㄱ3", "가방 3", "/가방 3", "/.ㄱ3" → 3. 해당 없으면 0.
     * 설정의 단축어는 더해서 알아본다: "ㄱ" 처럼 앞부분만 적었으면 뒤에 번호, "ㄱ1" 처럼 번호까지 적었으면 그 단축어 = 그 번호
     */
    public static int parseBagNumber(String text, List<String> aliases) {
        if (text == null) return 0;
        String t = text.trim();
        Matcher b = BUILT_IN.matcher(t);
        if (b.matches()) return Integer.parseInt(b.group(1));
        if (aliases == null) return 0;
        String bare = t.startsWith("/") ? t.substring(1).trim() : t;
        for (String a : aliases) {
            if (a == null || a.isBlank()) continue;
            String alias = a.trim();
            if (alias.startsWith("/")) alias = alias.substring(1).trim();
            char last = alias.charAt(alias.length() - 1);
            if (last >= '1' && last <= '9' && alias.length() > 1) {
                if (bare.equals(alias)) return last - '0';
                continue;
            }
            Matcher m = Pattern.compile("^" + Pattern.quote(alias) + "\\s*([1-9])$").matcher(bare);
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null) {
            closeOpen();
            INV_HISTORY.clear();
            pendingWithdraw = 0;
            return;
        }
        trackWithdraw(client);
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            closeOpen();
            return;
        }
        if (screen != openScreen) {
            closeOpen();
            openScreen = screen;
            openTicks = 0;
            decided = false;
            openIsBag = false;
            openBag = 0;
            openIsStorage = false;
        }
        openTicks++;
        // 내용이 도착한 뒤에 판단한다(빈 가방이면 2초 뒤)
        if (!decided && openTicks >= SETTLE_TICKS && (hasItems(screen) || openTicks >= WAIT_CONTENT_TICKS)) {
            decided = true;
            decide(screen);
        }
        if (decided && openBag > 0 && ticks % 5 == 0) record(screen, openBag);
        if (decided && openIsStorage && ticks % 5 == 0) recordStorage(screen);
    }

    private static boolean hasItems(GenericContainerScreen screen) {
        Inventory inv = screen.getScreenHandler().getInventory();
        int size = containerSize(screen);
        for (int i = 0; i < size; i++) if (!inv.getStack(i).isEmpty()) return true;
        return false;
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
    /** buttons: 제목의 번호 단추 중 가장 큰 번호(= 가진 가방 수, 단추가 없으면 0) */
    public record TitleSignal(boolean bagUi, boolean bag, int number, int buttons) {
        public TitleSignal(boolean bagUi, boolean bag, int number) {
            this(bagUi, bag, number, 0);
        }
    }

    public static TitleSignal readTitle(Text title) {
        boolean[] ui = {false};
        boolean[] bag = {false};
        int[] number = {0};
        int[] pressed = {0};
        int[] buttons = {0};
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
                        buttons[0] = Math.max(buttons[0], c - '뀀');
                    } else if (c >= 'ꀁ' && c <= 'ꀉ') {
                        buttons[0] = Math.max(buttons[0], c - 'ꀀ');
                    }
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return new TitleSignal(ui[0], bag[0], pressed[0] == 1 ? number[0] : 0, buttons[0]);
    }

    /** 창이 열리고 내용이 도착한 뒤 가방인지, 몇 번인지 정한다. */
    private static void decide(GenericContainerScreen screen) {
        Account acc = account();
        if (acc == null) return;
        boolean pending = pendingBag > 0 && System.currentTimeMillis() - pendingAt < PENDING_MS;
        // 1) 서버 창 제목의 가방 글리프로 판단 (가장 확실)
        TitleSignal sig = readTitle(screen.getTitle());
        List<String> slots = slotsOf(screen);
        // 아이템 저장소는 이름 빈 칸 39개와 "[ 저장소 슬롯 N번 열기 ]" 단추 13개가 있어 장식 창 판정보다 먼저 본다
        // (2026-10-22~25 원본 기록 80건 모두 장식 창으로 걸려 저장소 수량을 한 번도 못 읽었다)
        if (screen.getTitle().getString().contains("저장소")) {
            openIsStorage = true;
            pendingBag = 0;
            return;
        }
        if (decorated(slots)) {
            // 이름 없는 장식 칸이 많은 창(메뉴·구매 안내 등)은 가방이 아니다(2026-10-10 실제로 이런 창이 3번 가방으로 잡혔음)
            pendingBag = 0;
            return;
        }
        if (sig.bagUi()) {
            if (sig.bag()) {
                // 가방 창 제목의 가장 큰 번호 단추 = 가진 가방 수: 그보다 큰 번호의 기록(예전 판이 같은 가방을 다른 번호로 남긴 것)은 지운다.
                // 가방 목록 창 등 다른 창의 단추는 믿지 않는다
                if (sig.buttons() > 0) learnBagCount(acc, Math.max(sig.buttons(), sig.number()));
                openIsBag = true;
                int known = sig.number() > 0 ? sig.number() : pending ? pendingBag : sig.buttons() == 1 ? 1 : 0;
                openBag = resolveBag(acc, slots, known, true);
                openGuessed = resolvedGuessed;
            }
            pendingBag = 0;
            return;
        }
        // 2) 글리프가 없으면(리소스팩을 끈 경우 등) 단축어·기억한 제목·칸 비교로 판단
        String title = screen.getTitle().getString();
        if (pending) {
            // 이미 가방 제목을 알고 있는데 다른 제목의 창이 열렸다면 가방이 아닌 창(구매 안내 등)으로 본다.
            if (acc.titles.isEmpty() || acc.titles.contains(title)) {
                if (!title.isBlank() && !acc.titles.contains(title)) {
                    acc.titles.add(title);
                    dirty = true;
                }
                openIsBag = true;
                openBag = resolveBag(acc, slots, pendingBag, true);
                openGuessed = resolvedGuessed;
            }
            pendingBag = 0;
            return;
        }
        if (acc.titles.contains(title) || title.contains("가방")) {
            // 제목만 보고 가방이라 짐작한 창: 이미 기록한 가방과 맞을 때만 쓰고, 새 가방 기록은 만들지 않는다
            openIsBag = true;
            openBag = resolveBag(acc, slots, 0, false);
            openGuessed = resolvedGuessed;
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
        bag.guessed = openGuessed;
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
            String key = ItemKeys.keyOf(s);
            if (key.equals("n:")) continue; // 이름 없는 꾸미기 칸
            int stored = ItemKeys.storedAmount(s);
            bag.counts.merge(key, stored >= 0 ? stored : s.getCount(), Integer::sum);
            IconCache.remember(s);
        }
        // 실제 저장소 창은 한 쪽에 모든 칸이 보인다(2026-10 원본 기록 80건): 연 창 내용으로 통째로 바꾼다.
        // 예전처럼 더하면 등록을 해제한 아이템이 저장소 수량에 계속 남았다
        acc.storage = bag;
        dirty = true;
    }

    private static void closeOpen() {
        if (openScreen != null) {
            // 닫기 직전의 모습까지 남긴다: 꺼내자마자(0.25초 안에) 닫으면 마지막 변화가 기록에서 빠졌다.
            // 아주 빨리 닫아 아직 판단 전이면 내용이 와 있을 때만 지금 판단한다(내용 없이 판단하면 빈 가방으로 덮어쓸 수 있어서)
            try {
                if (!decided && hasItems(openScreen)) {
                    decided = true;
                    decide(openScreen);
                }
                if (decided && openBag > 0) record(openScreen, openBag);
                if (decided && openIsStorage) recordStorage(openScreen);
            } catch (RuntimeException e) {
                DdingMate.LOG.warn("가방 창을 닫으며 기록하지 못했어요", e);
            }
            decided = false;
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

    /**
     * 자동 수납 알림: 아이템을 주우면 서버가 화면 아래에 "[아이템 저장소] 조약돌 1개 수납! (2/5,000)" 을 띄운다
     * (2026-10 실제 화면). 괄호 앞 수가 지금 재고라서, 저장소 창을 다시 열지 않아도 그 값으로 맞춘다.
     */
    private static final Pattern STORED_MSG = Pattern.compile(
            "\\[\\s*아이템\\s*저장소\\s*]\\s*(.+?)\\s*[\\d,]+\\s*개\\s*수납.*?\\(\\s*([\\d,]+)\\s*/\\s*[\\d,]+\\s*\\)");

    /** 꺼낼 때 서버 채팅: "[아이템 저장소] 6개를 인출했습니다." (아이템 이름은 없다, 2026-10 실제 채팅) */
    private static final Pattern WITHDRAW_MSG = Pattern.compile("\\[\\s*아이템\\s*저장소\\s*]\\s*([\\d,]+)\\s*개를\\s*인출");
    /** 최근 1초(20틱) 동안의 인벤토리 아이템 수(오래된 것부터) */
    private static final java.util.ArrayDeque<Map<String, Integer>> INV_HISTORY = new java.util.ArrayDeque<>();
    private static int pendingWithdraw;
    private static int pendingTicks;

    /**
     * 저장소에서 꺼낸 만큼 저장소 기록에서 뺀다(안 빼면 인벤토리 + 저장소로 두 번 센다).
     * 꺼냄 메시지에는 아이템 이름이 없어서, 그 무렵 인벤토리에서 N개 이상 늘어난 아이템(저장소 기록에 있는 것)을 꺼낸 것으로 본다.
     */
    private static void trackWithdraw(MinecraftClient client) {
        Map<String, Integer> now = new HashMap<>();
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) now.merge(ItemKeys.keyOf(s), s.getCount(), Integer::sum);
        }
        if (pendingWithdraw > 0 && !INV_HISTORY.isEmpty()) {
            Account acc = account();
            Map<String, Integer> before = INV_HISTORY.peekFirst();
            String best = null;
            int bestGain = 0;
            if (acc != null && acc.storage != null) {
                for (Map.Entry<String, Integer> e : now.entrySet()) {
                    int gain = e.getValue() - before.getOrDefault(e.getKey(), 0);
                    if (gain >= pendingWithdraw && acc.storage.counts.containsKey(e.getKey()) && gain > bestGain) {
                        best = e.getKey();
                        bestGain = gain;
                    }
                }
            }
            if (best != null) {
                acc.storage.counts.put(best, Math.max(0, acc.storage.counts.get(best) - pendingWithdraw));
                dirty = true;
                pendingWithdraw = 0;
            } else if (++pendingTicks > 40) {
                pendingWithdraw = 0; // 2초 안에 못 찾으면 포기(저장소 창을 다시 열면 맞춰진다)
            }
        }
        INV_HISTORY.addLast(now);
        while (INV_HISTORY.size() > 20) INV_HISTORY.removeFirst();
    }

    public static void onOverlay(String message) {
        if (message == null || !message.contains("저장소")) return;
        if (kr.ddingmate.game.ServerText.fromPlayer(ItemKeys.stripGlyphs(message))) return; // 남이 흉내 낸 채팅·귓속말
        Matcher w = WITHDRAW_MSG.matcher(ItemKeys.stripGlyphs(message));
        if (w.find()) {
            try {
                pendingWithdraw = Integer.parseInt(w.group(1).replace(",", ""));
                pendingTicks = 0;
            } catch (NumberFormatException ignored) {
                // 무시
            }
            return;
        }
        Matcher m = STORED_MSG.matcher(ItemKeys.stripGlyphs(message));
        if (!m.find()) return;
        Account acc = account();
        if (acc == null) return;
        String name = m.group(1).trim();
        int total;
        try {
            total = Integer.parseInt(m.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return;
        }
        if (acc.storage == null) {
            acc.storage = new Bag();
            acc.storage.slots = new ArrayList<>();
        }
        // 저장소 창에서 본 키(바닐라 아이템이면 v:id)가 있으면 그 키로, 없으면 레시피 이름표로 정한다
        String key = null;
        for (String k : RecipeBook.keysFor(name)) {
            if (acc.storage.counts.containsKey(k)) {
                key = k;
                break;
            }
        }
        if (key == null) {
            for (String k : RecipeBook.keysFor(name)) if (k.startsWith("v:")) key = k;
            if (key == null) key = ItemKeys.nameKey(name);
        }
        acc.storage.counts.put(key, total);
        acc.storage.seenAt = System.currentTimeMillis();
        dirty = true;
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

    /**
     * 지금 연 가방 창이 어느 기록인지 정한다. 가방 안은 그 창을 열었을 때만 바뀌므로, 열었을 때 내용이 지난 기록과
     * 똑같으면 같은 가방이다(번호를 몰라도 된다 — 서버 가방 창에는 번호가 안 보일 때가 있다, 2026-10-10 실제 창).
     * known(명령어 /ㄱ2 등으로 안 번호)이 있으면 그 번호로 자리를 바로잡는다. 처음 보는 가방이면 빈 번호로 새로 기록한다.
     */
    static int resolveBag(Account acc, List<String> slots, int known, boolean allowNew) {
        resolvedGuessed = false;
        String exact = null;
        long exactSeen = -1;
        for (Map.Entry<String, Bag> e : acc.bags.entrySet()) {
            if (slots.equals(e.getValue().slots) && e.getValue().seenAt > exactSeen) {
                exact = e.getKey();
                exactSeen = e.getValue().seenAt;
            }
        }
        if (known > 0) {
            String k = String.valueOf(known);
            Bag atKnown = acc.bags.get(k);
            if (exact != null && !exact.equals(k)) {
                // 번호를 모를 때 다른 번호로 기록해 둔 가방이었다 → 진짜 번호로 옮기고, 그 번호에 있던 다른 가방 기록은 비운 자리로
                Bag moved = acc.bags.remove(exact);
                if (atKnown != null) acc.bags.put(exact, atKnown);
                acc.bags.put(k, moved);
                dirty = true;
            } else if (exact == null && atKnown != null && atKnown.guessed && similarity(slots, atKnown.slots) < MATCH_THRESHOLD
                    && !allEmpty(atKnown.slots) && fits(acc, freeNumber(acc, known))) {
                // 그 번호 자리의 기록이 번호를 몰라 짐작해 둔 다른 가방 것이면 지우지 말고 빈 번호로 옮겨 둔다.
                // 번호를 알고 남긴 기록이면 같은 가방이 내용만 바뀐 것이라 그대로 덮어쓴다
                // (예전 판: 이것도 옮겨서 2번 가방의 지난 내용이 "4번 가방"으로 남아 같은 물건을 두 번 셌다, 2026-10-16 실제)
                acc.bags.remove(k);
                acc.bags.put(String.valueOf(freeNumber(acc, known)), atKnown);
                dirty = true;
            }
            return known;
        }
        if (exact != null) {
            resolvedGuessed = acc.bags.get(exact).guessed;
            return Integer.parseInt(exact);
        }
        int best = bestMatch(acc, slots);
        if (best > 0) {
            resolvedGuessed = acc.bags.get(String.valueOf(best)).guessed;
            return best;
        }
        // 처음 보는 내용: 빈 창(내용이 아직 안 온 창)이거나 가진 가방 수를 넘는 번호면 새로 기록하지 않는다
        // (예전 판: 빈 창이 "3번 가방"으로 남았다)
        if (!allowNew || allEmpty(slots)) return 0;
        int n = freeNumber(acc, 0);
        if (!fits(acc, n)) return 0;
        resolvedGuessed = true;
        return n;
    }

    private static boolean allEmpty(List<String> slots) {
        for (String s : slots) if (!s.isEmpty()) return false;
        return true;
    }

    /** 가진 가방 수를 알면 그 안의 번호인가 */
    private static boolean fits(Account acc, int number) {
        return acc.bagCount <= 0 || number <= acc.bagCount;
    }

    /** 가방 창 제목에서 가진 가방 수를 알았다: 그보다 큰 번호의 기록을 지운다 */
    static void learnBagCount(Account acc, int count) {
        if (count <= 0 || count > 9) return;
        // 가방은 늘기만 한다: 더 작게 읽혀도 줄이지 않는다(제목 일부만 온 창 등으로 기록을 잃지 않게)
        if (count > acc.bagCount) {
            acc.bagCount = count;
            dirty = true;
        }
        int limit = acc.bagCount;
        if (acc.bags.keySet().removeIf(k -> Integer.parseInt(k) > limit)) dirty = true;
    }

    /** 시험용 */
    public static boolean isDecorated(List<String> slots) {
        return decorated(slots);
    }

    /** 이름 없는 장식 칸("n:*1")이 9칸 이상인 창 — 가방 안에는 이런 칸이 없다 */
    static boolean decorated(List<String> slots) {
        int n = 0;
        for (String s : slots) if (s.startsWith("n:*")) n++;
        return n >= 9;
    }

    /** 시험용: 기록(번호 → 칸 배치)에서 지금 칸·아는 번호로 정한 번호. bags 는 바뀐 기록으로 고쳐 쓴다 */
    public static int debugResolve(Map<String, List<String>> bags, List<String> slots, int known) {
        Account acc = new Account();
        long t = 1;
        for (Map.Entry<String, List<String>> e : bags.entrySet()) {
            Bag b = new Bag();
            b.slots = new ArrayList<>(e.getValue());
            b.seenAt = t++;
            b.guessed = true;
            acc.bags.put(e.getKey(), b);
        }
        int n = resolveBag(acc, slots, known, true);
        bags.clear();
        acc.bags.forEach((k, v) -> bags.put(k, v.slots));
        return n;
    }

    /**
     * 시험용: 번호를 알고 남긴 기록(guessed=false)들과 가진 가방 수(bagCount, 0 = 모름)로 정한 번호.
     * bags 는 바뀐 기록으로 고쳐 쓴다
     */
    public static int debugResolveKnown(Map<String, List<String>> bags, List<String> slots, int known, int bagCount) {
        Account acc = new Account();
        long t = 1;
        for (Map.Entry<String, List<String>> e : bags.entrySet()) {
            Bag b = new Bag();
            b.slots = new ArrayList<>(e.getValue());
            b.seenAt = t++;
            acc.bags.put(e.getKey(), b);
        }
        if (bagCount > 0) learnBagCount(acc, bagCount);
        int n = resolveBag(acc, slots, known, true);
        bags.clear();
        acc.bags.forEach((k, v) -> bags.put(k, v.slots));
        return n;
    }

    /** 기록에 없는 가장 작은 가방 번호(skip 은 빼고) */
    private static int freeNumber(Account acc, int skip) {
        for (int i = 1; ; i++) if (i != skip && !acc.bags.containsKey(String.valueOf(i))) return i;
    }

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

    /**
     * 두 칸 배치(a = 지금 창, b = 기록)가 같은 가방일 비율. 개수는 보지 않고 아이템 종류만 비교하고,
     * 기록에는 있던 칸이 지금 비었으면 꺼내 쓴 것으로 본다. 예전에는 "아이템*개수"를 그대로 비교해서
     * 재료를 꺼내 개수가 줄면 다른 가방으로 착각해 새 기록을 만들고, 옛 기록(꺼내기 전 개수)이 계속 합산됐다
     */
    static double similarity(List<String> a, List<String> b) {
        int n = Math.max(a.size(), b.size());
        int used = 0, same = 0, removed = 0, nowUsed = 0, nowSame = 0;
        for (int i = 0; i < n; i++) {
            String x = kind(i < a.size() ? a.get(i) : "");
            String y = kind(i < b.size() ? b.get(i) : "");
            if (x.isEmpty() && y.isEmpty()) continue;
            used++;
            if (!x.isEmpty()) nowUsed++;
            if (x.equals(y)) {
                same++;
                nowSame++;
            } else if (x.isEmpty()) {
                removed++;
            }
        }
        // 지금 든 아이템이 하나도 없으면(빈 가방) 같은 가방이라 볼 근거가 없다
        if (nowUsed == 0 || used == 0) return 0;
        // 지금 든 아이템의 절반 이상이 기록과 같은 자리·같은 종류일 때만, 비어 버린 칸을 꺼내 쓴 것으로 본다
        if (nowSame * 2 >= nowUsed) return (double) (same + removed) / used;
        return (double) same / used;
    }

    /** "n:조약돌*64" → "n:조약돌"(개수 떼기) */
    private static String kind(String slot) {
        int star = slot.lastIndexOf('*');
        return star > 0 ? slot.substring(0, star) : slot;
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
                    // 0.3.1 초기판: 가방이 아닌 장식 창을 가방으로 기록한 것(이름 없는 칸만 있고 센 아이템 없음)을 지운다
                    a.bags.values().removeIf(b -> b.counts.isEmpty() && decorated(b.slots));
                    if (a.storage != null && (a.storage.counts == null || a.storage.slots == null)) a.storage = null;
                    // 이름 규칙이 바뀌어도 예전 기록을 그대로 쓸 수 있게 키를 다시 만든다
                    for (Bag b : a.bags.values()) rekey(b);
                    if (a.storage != null) rekey(a.storage);
                }
                store = s;
            }
        } catch (IOException | RuntimeException e) {
            DdingMate.LOG.warn("가방 기록을 읽지 못해 새로 시작합니다: {}", p, e);
        }
    }

    private static void rekey(Bag b) {
        Map<String, Integer> counts = new HashMap<>();
        b.counts.forEach((k, v) -> counts.merge(ItemKeys.rekey(k), v, Integer::sum));
        b.counts = counts;
        List<String> slots = new ArrayList<>();
        for (String slot : b.slots) {
            int star = slot.lastIndexOf('*');
            slots.add(star < 0 ? slot : ItemKeys.rekey(slot.substring(0, star)) + slot.substring(star));
        }
        b.slots = slots;
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
