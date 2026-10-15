package kr.ddingmate.expert;

import kr.ddingmate.DdingMate;
import kr.ddingmate.counter.ItemKeys;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 판매가를 올려 주는 전문가 스킬(위키 채광·재배·해양·사냥 전문가 문서, 2026-10-15 전수 조사: 설명이 "…의 판매가가 N% 증가"인 스킬 16개).
 * /전문가 스킬 창에서 지금 배운 비율(설명의 "판매가가 N% 증가")을 읽어 두고, 상점 창에서 아직 못 본 판매품의 예상 판매가에 쓴다.
 * 상점에서 "나의 판매가"를 직접 보면 그 값이 먼저다(서버가 계산한 값).
 */
public final class PriceSkills {

    private PriceSkills() {}

    /**
     * @param group  ShopPrices.group 묶음 이름(상점 판매품에 바로 쓰는 것) 또는 null(띵메이트가 세지 않는 물건: 물고기·수렵물·요리)
     * @param levels 레벨별 증가율(%), 위키 표
     * @param note   조건·적용처
     */
    public record Skill(String name, String expert, String goods, String group, double[] levels, String note) {}

    public static final List<Skill> SKILLS = List.of(
            new Skill("주괴 좀 사주괴", "채광", "주괴", "주괴", new double[]{5, 7, 10, 20, 30, 50}, "상점·무역"),
            new Skill("반짝반짝 눈이 부셔", "채광", "정동석", "정동석", new double[]{5, 7, 10, 20, 30, 50}, "상점·무역"),
            new Skill("귀하신 몸값", "채광", "귀중품", "귀중품", new double[]{5, 7, 10, 15, 20, 30}, "상점(귀중품 가격에 이미 반영)"),
            new Skill("내가그린형광그림", "채광", "형광 제련품", "제련품", new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30}, "상점"),
            new Skill("돈 좀 벌어볼까?", "재배", "요리", null, new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30, 50}, "상점·무역"),
            new Skill("한 솥 가득", "재배", "요리", null, new double[]{1, 2, 3, 4, 7}, "한 번에 3세트 이상 팔 때만 보너스"),
            new Skill("아름다움의 극치", "재배", "향장품", "향장품", new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30, 50}, "상점"),
            new Skill("커피값이 밥값", "재배", "커피", null, new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30, 50}, "손님(인스턴트 커피 제외)"),
            new Skill("조개 좀 사조개", "해양", "공예품", "공예품", new double[]{5, 7, 10, 15, 20, 30, 40, 50}, "상점"),
            new Skill("프리미엄 한정가", "해양", "연금품", "연금품", new double[]{5, 7, 9, 12, 15, 20, 25, 30}, "상점·무역"),
            new Skill("치솟는 생선값", "해양", "물고기", null, new double[]{2, 5, 7, 10, 20}, "판매 시 자동"),
            new Skill("귀하게 자란 티", "해양", "반려어 방생 대금", null, new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30, 50}, "반려어 방생"),
            new Skill("값어치 증명", "사냥", "영혼", "영혼", new double[]{5, 7, 10, 20, 30, 50}, "상점·무역"),
            new Skill("원혼 사냥꾼", "사냥", "원혼", "원혼", new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30, 50}, "상점"),
            new Skill("장인의 품격", "사냥", "영혼 명품", "가죽 명품", new double[]{1, 2, 3, 4, 5, 6, 10, 15, 30}, "상점"),
            new Skill("상태 좋네!", "사냥", "수렵물", null, new double[]{5, 7, 10, 20, 30, 50}, "수렵꾼(즉시 판매는 제외)"));

    /** 기준가 예시(위키): 물고기(샤키 원가)·수렵물(수렵꾼 기본가) */
    public static final Map<String, List<Object[]>> EXAMPLES = Map.of(
            "물고기", List.of(new Object[]{"커먼 물고기", 520}, new Object[]{"노멀 물고기", 1_300}, new Object[]{"에픽 물고기", 2_600},
                    new Object[]{"전설 물고기", 13_000}, new Object[]{"신화 물고기", 65_000}),
            "수렵물", List.of(new Object[]{"쇠약한 수렵물", 23_808}, new Object[]{"평범한 수렵물", 47_617}, new Object[]{"건강한 수렵물", 102_036}));

    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern PREVIEW = Pattern.compile("─{3,}|강화\\s*시|습득\\s*시|배울\\s*시");

    /** 10틱마다: /전문가 스킬 창이면 판매가 스킬의 지금 비율을 읽는다 */
    public static void scan(MinecraftClient client) {
        if (!(client.currentScreen instanceof GenericContainerScreen screen)) return;
        Map<String, Double> saved = DdingMate.config().expert.priceSkills;
        boolean changed = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory || !slot.hasStack()) continue;
            String name = ItemKeys.stripGlyphs(slot.getStack().getName().getString());
            for (Skill k : SKILLS) {
                if (!name.contains(k.name())) continue;
                double pct = 0;
                var lore = slot.getStack().get(DataComponentTypes.LORE);
                if (lore != null) {
                    for (Text t : lore.lines()) {
                        String l = ItemKeys.stripGlyphs(t.getString());
                        if (PREVIEW.matcher(l).find()) break;
                        Matcher m = PCT.matcher(l);
                        if (m.find()) {
                            pct = Double.parseDouble(m.group(1));
                            break;
                        }
                    }
                }
                // 안 배운 스킬: "(+0)" 이나 잠김
                if (name.matches(".*\\(\\s*\\+?\\s*0\\s*\\).*") || name.contains("잠김")) pct = 0;
                Double old = saved.get(k.name());
                boolean differs = old == null || Math.abs(old - pct) > 1e-6;
                if (differs) {
                    saved.put(k.name(), pct);
                    changed = true;
                    DdingMate.LOG.info("판매가 스킬 읽음: {} {}%", k.name(), pct);
                }
                // 이번 접속에서 처음 읽었거나 값이 바뀌었으면 스킬 확인 채팅(한 번에 모아서)에 넣는다
                if (CONFIRMED.add(k.name()) || differs) {
                    kr.ddingmate.skill.SkillReport.add("판매가", k.expert(), k.name(),
                            k.name() + (pct > 0 ? "(" + k.goods() + " +" + (pct == Math.floor(pct) ? String.valueOf((int) pct) : String.valueOf(pct)) + "%)" : " 안 배움"),
                            differs && old != null);
                }
            }
        }
        if (changed) DdingMate.config().save();
    }

    /** 이번 접속에서 이미 채팅으로 알려 준 판매가 스킬 */
    private static final java.util.Set<String> CONFIRMED = new java.util.HashSet<>();

    /** 이 스킬의 지금 비율(%). 스킬 창을 아직 안 열었으면 -1 */
    public static double pct(String skill) {
        Double v = DdingMate.config().expert.priceSkills.get(skill);
        return v == null ? -1 : v;
    }

    /** 판매품 묶음에 걸리는 스킬(없으면 null) */
    public static Skill forGroup(String group) {
        for (Skill k : SKILLS) if (group.equals(k.group())) return k;
        return null;
    }
}
