package kr.ddingmate.skill;

import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /전문가 스킬 창에서 읽은 스킬을 모아 두었다가, 창을 닫으면(또는 다른 창으로 넘어가면) 채팅 한 번에 정리해 알려 준다.
 * 예전에는 스킬마다 한 줄씩 채팅이 나왔다. 제작 시간·쉬프트 스킬·판매가 스킬을 종류별로 묶는다.
 */
public final class SkillReport {

    private SkillReport() {}

    /** 종류(제작 시간·쉬프트 스킬·판매가) 순서 */
    private static final List<String> ORDER = List.of("제작 시간", "쉬프트 스킬", "판매가");

    private record Entry(String category, String text, boolean changed) {}

    private static final Map<String, Entry> PENDING = new LinkedHashMap<>();
    /** 모으기 시작한 창(이 창이 닫히면 알린다) */
    private static Screen window;
    private static long firstAt;

    /**
     * @param name    스킬 이름(같은 스킬은 마지막 값으로 덮어씀)
     * @param text    "초고속 용광로 30%" 처럼 보여 줄 글
     * @param changed 이번에 새로 반영했거나 값이 바뀌었으면 true
     */
    public static void add(String category, String name, String text, boolean changed) {
        Entry old = PENDING.get(name);
        PENDING.put(name, new Entry(category, text, changed || (old != null && old.changed())));
        if (window == null) {
            window = MinecraftClient.getInstance().currentScreen;
            firstAt = System.currentTimeMillis();
        }
    }

    /** 매 틱: 모은 창이 닫혔거나 바뀌었으면(혹은 30초가 지나면) 한 번에 알린다 */
    public static void tick(MinecraftClient client) {
        if (PENDING.isEmpty()) return;
        if (window != null && client.currentScreen == window && System.currentTimeMillis() - firstAt < 30_000) return;
        flush();
    }

    public static void flush() {
        if (PENDING.isEmpty()) return;
        Notifier.chat(message());
        PENDING.clear();
        window = null;
    }

    /** 시험용: 지금 모아 둔 것을 채팅 글로 */
    public static String preview() {
        return PENDING.isEmpty() ? "" : message().getString();
    }

    /** 시험용 */
    public static int pending() {
        return PENDING.size();
    }

    private static Text message() {
        long changed = PENDING.values().stream().filter(Entry::changed).count();
        MutableText t = Text.literal("전문가 스킬 " + PENDING.size() + "개 확인" + (changed > 0 ? " · 새로 반영 " + changed + "개" : ""))
                .formatted(Formatting.YELLOW);
        List<String> cats = new ArrayList<>(ORDER);
        for (Entry e : PENDING.values()) if (!cats.contains(e.category())) cats.add(e.category());
        for (String c : cats) {
            List<Entry> list = PENDING.values().stream().filter(e -> e.category().equals(c)).toList();
            if (list.isEmpty()) continue;
            t.append(Text.literal("\n  " + c + " · ").formatted(Formatting.GOLD));
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (i > 0) t.append(Text.literal(", ").formatted(Formatting.GRAY));
                t.append(Text.literal(e.text()).formatted(e.changed() ? Formatting.GREEN : Formatting.WHITE));
                if (e.changed()) t.append(Text.literal("(새로)").formatted(Formatting.GREEN));
            }
        }
        return t;
    }
}
