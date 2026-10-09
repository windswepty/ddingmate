package kr.ddingmate.craft;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.notify.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전문가 스킬 창(/전문가 → 채광·재배·해양·사냥 스킬보기)에서 제작 시간을 줄이는 스킬의 현재 레벨을 읽어
 * 설정에 그대로 넣는다. 실제 칸: "[세레니티] 초고속 용광로 (+1)" 설명
 * ". 채광물 가공과 강화 제작 시설의 / . 제작 시간이 10% 감소합니다. / ──── / . [강화 시 스킬 정보] / …30% 감소…"
 * — 구분선 위가 지금 효과, 아래는 다음 레벨 미리보기다.
 */
public final class ExpertSkills {

    private ExpertSkills() {}

    /** 제작 시간을 줄이는 스킬과 설정 칸 */
    private record Skill(String name, String label, boolean marine,
                         ToIntFunction<DdingMateConfig.Craft> get, java.util.function.ObjIntConsumer<DdingMateConfig.Craft> set) {}

    private static final List<Skill> SKILLS = List.of(
            new Skill("초고속 용광로", "채광", false, c -> c.mining, (c, v) -> c.mining = v),
            new Skill("불 더 올려", "재배", false, c -> c.farming, (c, v) -> c.farming = v),
            new Skill("물 흐르듯 술술", "해양", true, c -> c.marine, (c, v) -> c.marine = v),
            new Skill("고속 영혼 가공기", "사냥", false, c -> c.hunting, (c, v) -> c.hunting = v));

    private static final Pattern REDUCE = Pattern.compile("제작\\s*시간이\\s*(\\d+)\\s*%\\s*감소");
    /** 구분선 또는 "[강화 시 스킬 정보]" 부터는 다음 레벨 미리보기 */
    private static final Pattern PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시");
    private static final Pattern NOT_LEARNED = Pattern.compile("\\(\\s*\\+?\\s*0\\s*\\)|미습득|배우지\\s*않|습득하지\\s*않");

    /** 10틱마다: 열린 창에 위 스킬 칸이 있으면 레벨을 맞춘다 */
    public static void scan(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) return;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            read(slot.getStack());
        }
    }

    /** 칸 하나를 읽어 해당 스킬이면 설정에 반영. 반영했으면 true */
    public static boolean read(ItemStack stack) {
        String name = ItemKeys.stripGlyphs(stack.getName().getString());
        Skill skill = null;
        for (Skill s : SKILLS) if (name.contains(s.name)) skill = s;
        if (skill == null) return false;
        List<String> lines = new ArrayList<>();
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) lines.add(ItemKeys.stripGlyphs(t.getString()));
        int level = levelOf(name, lines, skill.marine);
        if (level < 0) return false;
        DdingMateConfig.Craft c = DdingMate.config().craft;
        int pct = (skill.marine ? MARINE : NORMAL)[level];
        if (skill.get.applyAsInt(c) == level) {
            // 이미 같은 값이어도 이번 접속에서 처음 읽었으면 확인했다고 한 번 알려 준다
            if (CONFIRMED.add(skill.name)) {
                Notifier.chat(level == 0
                        ? "전문가 스킬 '" + skill.name + "'을 아직 안 배운 것으로 확인했어요."
                        : "전문가 스킬 '" + skill.name + "' " + pct + "% 감소를 확인했어요(이미 반영됨).");
            }
            return false;
        }
        CONFIRMED.add(skill.name);
        skill.set.accept(c, level);
        DdingMate.config().save();
        CraftTimers.recalculate();
        DdingMate.LOG.info("전문가 스킬 {} → {}레벨({}%)", skill.name, level, pct);
        Notifier.chat(level == 0
                ? "전문가 스킬 '" + skill.name + "'을 아직 안 배운 것으로 확인했어요."
                : "전문가 스킬 '" + skill.name + "' " + pct + "% 감소를 제작 시간 계산에 반영했어요.");
        return true;
    }

    /** 이번 접속에서 이미 읽고 알려 준 스킬 */
    private static final java.util.Set<String> CONFIRMED = new java.util.HashSet<>();

    private static final int[] NORMAL = {0, 10, 30, 40, 60, 80};
    private static final int[] MARINE = {0, 10, 20, 30, 50, 70};

    /** 지금 레벨(0~5). 알 수 없으면 -1 */
    static int levelOf(String name, List<String> lore, boolean marine) {
        if (NOT_LEARNED.matcher(name).find()) return 0;
        // 구분선 위(지금 효과)만 한 줄로 이어 읽는다. 스킬마다 줄 바꿈 위치가 달라
        // "영혼 가공 시설의 제작 시간이 / 10% 감소합니다."처럼 숫자가 다음 줄로 넘어가기도 한다
        StringBuilder now = new StringBuilder();
        for (String l : lore) {
            if (PREVIEW.matcher(l).find()) break;
            now.append(' ').append(l.replaceFirst("^[.\\s]+", ""));
        }
        if (NOT_LEARNED.matcher(now).find()) return 0;
        Matcher m = REDUCE.matcher(now);
        return m.find() ? levelForPercent(Integer.parseInt(m.group(1)), marine) : -1;
    }

    /** 감소율 → 레벨. 표에 없는 값이면 그 값을 넘지 않는 가장 높은 레벨 */
    static int levelForPercent(int pct, boolean marine) {
        int[] t = marine ? MARINE : NORMAL;
        int best = 0;
        for (int i = 0; i < t.length; i++) if (t[i] <= pct) best = i;
        return best;
    }

    /** 시험용 */
    public static int debugLevel(String name, List<String> lore) {
        boolean marine = name.contains("물 흐르듯");
        return levelOf(ItemKeys.stripGlyphs(name), lore, marine);
    }
}
