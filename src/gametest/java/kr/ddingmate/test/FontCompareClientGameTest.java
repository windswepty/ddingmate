package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.ui.Theme;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 글꼴 스위치(0.3.2): 글꼴마다(갈무리·온글잎 레디오 볼펜체·베리려원) 글꼴 이름이 바뀌고 글자 폭이 달라지는지, 그리고
 * 1920×1080 GUI 3 에서 띵메이트 창·HUD 를 찍는다(DDINGMATE_FONT_COMPARE=1 일 때만 찍음).
 */
public class FontCompareClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            DdingMateConfig.FontStyle[] keep = new DdingMateConfig.FontStyle[1];
            boolean[] keepOpened = new boolean[2];
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                keep[0] = cfg.font;
                java.util.Set<String> ids = new java.util.HashSet<>();
                java.util.Map<DdingMateConfig.FontStyle, Integer> widths = new java.util.LinkedHashMap<>();
                String fontSample = "조약돌 뭉치 ×7 [3] 50분 20초 가나다라마바사 ABCxyz";
                // 글꼴 파일을 못 읽으면 그 글꼴 묶음의 다음 줄(minecraft:default)로 대신 그려 폭이 기본 글꼴과 같아진다
                int fallback = c.textRenderer.getWidth(fontSample);
                for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                    cfg.font = f;
                    ids.add(Theme.bodyFont().toString());
                    widths.put(f, Theme.width(c.textRenderer, fontSample));
                }
                DdingMate.LOG.info("글꼴별 글자 폭(기본 글꼴 {}): {}", fallback, widths);
                if (ids.size() != DdingMateConfig.FontStyle.values().length) throw new AssertionError("글꼴마다 다른 글꼴 이름이 아님: " + ids);
                for (var e : widths.entrySet())
                    if (e.getValue() == fallback && e.getKey() != DdingMateConfig.FontStyle.GALMURI)
                        throw new AssertionError("글꼴 파일을 못 읽은 듯(기본 글꼴과 폭이 같음): " + e.getKey() + " " + widths);
                if (new java.util.HashSet<>(widths.values()).size() < 3) throw new AssertionError("글꼴을 바꿔도 글자 폭이 거의 같음: " + widths);
                // 게임 전체에 적용: 기본 글꼴의 글자(한글·영문)도 고른 글꼴로, 끄면 원래대로
                boolean keepEverywhere = cfg.fontEverywhere;
                cfg.font = DdingMateConfig.FontStyle.BERRY;
                String sample = "조약돌 뭉치 x7 [3] 50분 20초";
                cfg.fontEverywhere = false;
                int vanillaW = c.textRenderer.getWidth(sample);
                cfg.fontEverywhere = true;
                int everywhereW = c.textRenderer.getWidth(sample);
                if (everywhereW == vanillaW || everywhereW != Theme.width(c.textRenderer, sample))
                    throw new AssertionError("게임 전체 글꼴이 안 바뀜: 기본 " + vanillaW + " / 적용 " + everywhereW + " / 베리려원 " + Theme.width(c.textRenderer, sample));
                // 기본 글꼴이 아닌 글꼴(띵메이트 본문 글꼴 자체, uniform 등)은 그대로
                net.minecraft.text.Text uni = net.minecraft.text.Text.literal(sample).setStyle(net.minecraft.text.Style.EMPTY
                        .withFont(net.minecraft.util.Identifier.ofVanilla("uniform")));
                int uniOn = c.textRenderer.getWidth(uni);
                cfg.fontEverywhere = false;
                if (c.textRenderer.getWidth(uni) != uniOn) throw new AssertionError("기본 글꼴이 아닌 글꼴까지 바꿈");
                if (c.textRenderer.getWidth(sample) != vanillaW) throw new AssertionError("게임 전체 글꼴을 꺼도 원래대로 안 돌아옴");
                cfg.fontEverywhere = keepEverywhere;
                cfg.font = keep[0];
            });
            if (System.getenv("DDINGMATE_FONTCAL") != null) calibrate(ctx);
            if (System.getenv("DDINGMATE_FONT_COMPARE") == null) return;
            ctx.runOnClient(c -> {
                c.getWindow().setWindowedSize(1920, 1080);
                keepOpened[0] = DdingMate.config().opened;
                keepOpened[1] = DdingMate.config().tutorialDone;
                DdingMate.config().opened = true;
            });
            ctx.waitTicks(10);
            for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                ctx.runOnClient(c -> {
                    DdingMate.config().font = f;
                    c.options.getGuiScale().setValue(3);
                    c.onResolutionChanged();
                    c.setScreen(null);
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-hud-" + f.name().toLowerCase());
                ctx.runOnClient(c -> {
                    DdingMateScreen s = new DdingMateScreen(null);
                    c.setScreen(s);
                    s.openTabNamed("재료");
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-screen-" + f.name().toLowerCase());
            }
            // 게임 전체에 적용: 채팅·인벤토리(아이템 이름)까지 고른 글꼴로
            for (DdingMateConfig.FontStyle f : new DdingMateConfig.FontStyle[]{DdingMateConfig.FontStyle.REDIO, DdingMateConfig.FontStyle.BERRY}) {
                ctx.runOnClient(c -> {
                    DdingMate.config().font = f;
                    DdingMate.config().fontEverywhere = true;
                    c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("[초보모험가] 멜트 : 게임 전체 글꼴 시험이에요 1234 ABC"));
                    c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("오늘 스태미나 3,000 소모 보상으로 빙고 티켓 10개를 지급받았습니다!"));
                    c.player.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_PICKAXE));
                    c.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(c.player));
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("font-everywhere-" + f.name().toLowerCase());
            }
            restore(ctx, keep[0], keepOpened);
        }
    }

    /** 글꼴 측정(DDINGMATE_FONTCAL=1): 글꼴 7종 × 본문·굵게·제목을 흰 바탕에 1920×1080 GUI 3 으로 찍고, 줄마다 그린 y·폭을 남긴다 */
    static final String CAL_SAMPLE = "가나다 한글 일정 보상 ABCgjpq 0123 [3] ×7";
    static final int CAL_ROW = 16, CAL_X = 12;

    private static void calibrate(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> {
            c.getWindow().setWindowedSize(1920, 1080);
            c.options.getGuiScale().setValue(3);
            c.onResolutionChanged();
            c.setScreen(new net.minecraft.client.gui.screen.Screen(net.minecraft.text.Text.literal("글꼴 측정")) {
                @Override
                public void render(net.minecraft.client.gui.DrawContext g, int mx, int my, float d) {
                    g.fill(0, 0, width, height, 0xFFFFFFFF);
                    DdingMateConfig cfg = DdingMate.config();
                    DdingMateConfig.FontStyle keepF = cfg.font;
                    int row = 0;
                    for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                        cfg.font = f;
                        for (int kind = 0; kind < 3; kind++) {
                            int y = 4 + row * CAL_ROW;
                            g.fill(CAL_X - 4, y, CAL_X - 2, y + 9, 0xFFFF0000); // 줄 칸(그린 y ~ y+9)
                            net.minecraft.text.Text t = kind == 0 ? Theme.body(CAL_SAMPLE) : kind == 1 ? Theme.bold(CAL_SAMPLE) : Theme.title(CAL_SAMPLE);
                            g.drawText(textRenderer, t, CAL_X, y, 0xFF000000, false);
                            row++;
                        }
                    }
                    cfg.font = keepF;
                }
            });
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("fontcal");
        ctx.runOnClient(c -> {
            DdingMateConfig cfg = DdingMate.config();
            DdingMateConfig.FontStyle keepF = cfg.font;
            StringBuilder sb = new StringBuilder();
            for (DdingMateConfig.FontStyle f : DdingMateConfig.FontStyle.values()) {
                cfg.font = f;
                sb.append(f.name()).append(' ').append(Theme.width(c.textRenderer, CAL_SAMPLE)).append(' ')
                        .append(Theme.boldWidth(c.textRenderer, CAL_SAMPLE)).append(' ').append(c.textRenderer.getWidth(Theme.title(CAL_SAMPLE))).append("; ");
            }
            cfg.font = keepF;
            DdingMate.LOG.info("FONTCAL scale={} rowH={} x={} widths(body bold title)={}", c.getWindow().getScaleFactor(), CAL_ROW, CAL_X, sb);
            c.setScreen(null);
            c.getWindow().setWindowedSize(854, 480);
            c.options.getGuiScale().setValue(0);
            c.onResolutionChanged();
        });
    }

    private static void restore(ClientGameTestContext ctx, DdingMateConfig.FontStyle font, boolean[] keepOpened) {
        ctx.runOnClient(c -> {
            c.setScreen(null);
            DdingMate.config().fontEverywhere = false;
            DdingMate.config().font = font;
            // 다음 시험은 '처음 설치' 상태로 시작해야 한다
            DdingMate.config().opened = keepOpened[0];
            DdingMate.config().tutorialDone = keepOpened[1];
            DdingMate.config().save();
            kr.ddingmate.hud.HudLayout.bounds().clear(); // 여기서 그린 HUD 자리가 다음 시험의 '아직 HUD 없음' 확인에 남지 않게
            c.getWindow().setWindowedSize(854, 480);
            c.options.getGuiScale().setValue(0);
            c.onResolutionChanged();
        });
    }
}
