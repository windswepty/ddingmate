package kr.ddingmate.skill;

import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /전문가 스킬 창에서 읽은 스킬을 모아 두었다가, 창을 모두 닫으면 채팅 한 번에 정리해 알려 준다.
 * 서버는 전문가마다 창을 새로 열어서(2026-10-15 실제), 창이 바뀌어도 계속 모으고 상자 창이 없는 상태가
 * 잠깐 이어질 때 내보낸다. 제작 시간·쉬프트 스킬·판매가 스킬을 종류별로 묶는다.
 */
public final class SkillReport {

    private SkillReport() {}

    /** 종류(제작 시간·쉬프트 스킬·판매가) 순서 */
    private static final List<String> ORDER = List.of("제작 시간", "쉬프트 스킬", "판매가");

    /** 전문가마다 글자색(채팅에서 한눈에 구분). 이 순서로 묶어 보여 준다 */
    private static final Map<String, Formatting> EXPERT_COLORS = new LinkedHashMap<>();
    static {
        EXPERT_COLORS.put("채광", Formatting.AQUA);
        EXPERT_COLORS.put("재배", Formatting.GREEN);
        EXPERT_COLORS.put("해양", Formatting.BLUE);
        EXPERT_COLORS.put("사냥", Formatting.RED);
    }

    private record Entry(String category, String expert, String text, boolean changed) {}

    private static final Map<String, Entry> PENDING = new LinkedHashMap<>();
    /** 상자 창이 없는 상태가 이만큼(틱) 이어지면 알린다. 창이 바뀔 때 서버가 잠깐 닫았다 여는 틈은 넘긴다 */
    public static final int IDLE_TICKS = 30;
    private static int idle;

    /**
     * @param expert  채광·재배·해양·사냥(모르면 빈 글자: 흰색)
     * @param name    스킬 이름(같은 스킬은 마지막 값으로 덮어씀)
     * @param text    "초고속 용광로 30%" 처럼 보여 줄 글
     * @param changed 이번에 새로 반영했거나 값이 바뀌었으면 true
     */
    public static void add(String category, String expert, String name, String text, boolean changed) {
        Entry old = PENDING.get(name);
        PENDING.put(name, new Entry(category, expert == null ? "" : expert, text, changed || (old != null && old.changed())));
        idle = 0;
    }

    /** 매 틱: 상자 창(스킬 창)을 모두 닫고 잠깐 지나면 한 번에 알린다 */
    public static void tick(MinecraftClient client) {
        if (PENDING.isEmpty()) return;
        if (client.currentScreen instanceof HandledScreen<?>) {
            idle = 0;
            return;
        }
        if (++idle >= IDLE_TICKS) flush();
    }

    public static void flush() {
        if (PENDING.isEmpty()) return;
        Notifier.chat(message());
        PENDING.clear();
        idle = 0;
    }

    /** 시험용: 지금 모아 둔 것을 채팅 글로 */
    public static String preview() {
        return PENDING.isEmpty() ? "" : message().getString();
    }

    /** 시험용: 모아 둔 스킬의 채팅 글자색 */
    public static Formatting debugColor(String name) {
        Entry e = PENDING.get(name);
        return e == null ? null : EXPERT_COLORS.getOrDefault(e.expert(), Formatting.WHITE);
    }

    /** 시험용 */
    public static int pending() {
        return PENDING.size();
    }

    private static Text message() {
        long changed = PENDING.values().stream().filter(Entry::changed).count();
        MutableText t = Text.literal("전문가 스킬 " + PENDING.size() + "개 확인" + (changed > 0 ? " · 새로 반영 " + changed + "개" : ""))
                .formatted(Formatting.YELLOW);
        // 머리줄에 색 범례: 이번에 나온 전문가만
        for (Map.Entry<String, Formatting> x : EXPERT_COLORS.entrySet()) {
            if (PENDING.values().stream().noneMatch(e -> !unlearned(e) && e.expert().equals(x.getKey()))) continue;
            t.append(Text.literal(" ■" + x.getKey()).formatted(x.getValue()));
        }
        List<String> cats = new ArrayList<>(ORDER);
        for (Entry e : PENDING.values()) if (!cats.contains(e.category())) cats.add(e.category());
        // 안 배운 스킬은 종류별 줄에서 빼서 맨 아래 회색 한 줄로(24개를 한 번에 보면 배운 것만 눈에 들어오게)
        List<String> unlearned = PENDING.values().stream().filter(SkillReport::unlearned)
                .map(e -> e.text().substring(0, e.text().length() - UNLEARNED.length())).toList();
        for (String c : cats) {
            List<Entry> list = PENDING.values().stream().filter(e -> e.category().equals(c) && !unlearned(e))
                    .sorted(java.util.Comparator.comparingInt(SkillReport::expertOrder)).toList();
            if (list.isEmpty()) continue;
            t.append(Text.literal("\n  " + c + " · ").formatted(Formatting.GOLD));
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (i > 0) t.append(Text.literal(", ").formatted(Formatting.GRAY));
                t.append(Text.literal(e.text()).formatted(EXPERT_COLORS.getOrDefault(e.expert(), Formatting.WHITE)));
                if (e.changed()) t.append(Text.literal("(새로)").formatted(Formatting.LIGHT_PURPLE));
            }
        }
        if (!unlearned.isEmpty()) {
            t.append(Text.literal("\n  안 배움 · ").formatted(Formatting.DARK_GRAY));
            t.append(Text.literal(String.join(", ", unlearned)).formatted(Formatting.GRAY));
        }
        return t;
    }

    private static final String UNLEARNED = " 안 배움";

    private static int expertOrder(Entry e) {
        int i = new ArrayList<>(EXPERT_COLORS.keySet()).indexOf(e.expert());
        return i < 0 ? EXPERT_COLORS.size() : i;
    }

    private static boolean unlearned(Entry e) {
        return e.text().endsWith(UNLEARNED);
    }
}
