package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 띵타이쿤 서버 리소스팩을 켜고 찍어 보는 시험(DDINGMATE_SERVER_PACK 이 있을 때만).
 * 값이 파일 경로면 그 파일, "1"이면 Modrinth 띵타이쿤 테스트 프로필의 downloads 에서 가장 최근에 받은 서버 리소스팩을 쓴다.
 * 리소스팩은 이 시험 실행 폴더(build/run)에만 복사하고 저장소에는 넣지 않는다(서버 소유 자료).
 * 찍는 것: 서버 그림 글자가 섞인 실제 채팅(게임 전체 글꼴 끔/켬), 전문가 탭의 스킬 보석·세이지 도구 아이콘.
 */
public class ServerPackClientGameTest implements FabricClientGameTest {

    private static final String PACK_FILE = "ddingtycoon-server.zip";
    private static final String PACK_ID = "file/" + PACK_FILE;

    /** 실제 로그(2026-10)의 채팅 그대로: 앞의 그림·간격 글자 포함 */
    private static final String[] CHAT = {
            "󏿻ꀂ󏿿ꀁ󏼡𑙢 오늘 스태미나 3,000 소모 보상으로 빙고 티켓 10개를 지급받았습니다!",
            "󏿻ꀂ󏿿ꀁ󏼡𑙡 광석 골렘이 등장했습니다!",
            "󏿻ꀂ󐀁ꀇ󐀂ꀄ [달토끼] 망령 : 네 두리한테 가서 수리하면되요",
            "󏿻ꀂ󏿿ꀁ󏼡ꀓ󐀂[질문]󐀃JJung69 : rpg 할때 무기 바꿀라고 하는데",
    };

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            boolean[] on = new boolean[1];
            ctx.runOnClient(c -> on[0] = TestPack.active(c));
            if (!on[0]) return; // 맨 처음 시험(ServerPackSetup)이 켜 둔 리소스팩이 없으면 건너뜀
            DdingMateConfig.FontStyle[] keepFont = new DdingMateConfig.FontStyle[1];
            boolean[] keep = new boolean[3];
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                keepFont[0] = cfg.font;
                keep[0] = cfg.fontEverywhere;
                keep[1] = cfg.opened;
                keep[2] = cfg.tutorialDone;
                cfg.opened = true;
                cfg.tutorialDone = true;
                c.getWindow().setWindowedSize(1920, 1080);
                c.options.getGuiScale().setValue(3);
                c.onResolutionChanged();
            });
            ctx.waitTicks(20);
            ctx.runOnClient(c -> {
                for (String line : CHAT) c.inGameHud.getChatHud().addMessage(Text.literal(line));
                // 아일랜드 작물 단계 표: 서버 리소스팩의 트립와이어 상태에서 토마토 6단계·양파 4단계·마늘 4단계(위키와 같아야)
                int size = kr.ddingmate.expert.IslandCrops.debugTableSize();
                var max = kr.ddingmate.expert.IslandCrops.debugMaxStages();
                if (size < 14 || !java.util.Map.of("tomato", 6, "onion", 4, "garlic", 4).equals(max))
                    throw new AssertionError("리소스팩의 작물 단계 표가 다름: " + size + " " + max);
                // 전문가 보석(스킬 트리 창 단추): 채광·해양·재배·사냥 × 보통·고른 것·흐린 것 = 12장
                int gems = kr.ddingmate.skill.SkillIcons.debugGemCount();
                if (gems < 12) throw new AssertionError("리소스팩에서 전문가 보석 그림을 다 못 찾음: " + gems);
            });
            // 게임 전체 글꼴: 끔(서버 글꼴 그대로) → 레디오 볼펜체 → 베리려원. 칭호·아이콘 그림이 그대로여야 한다
            for (Object[] f : new Object[][]{{false, DdingMateConfig.FontStyle.GALMURI, "off"},
                    {true, DdingMateConfig.FontStyle.REDIO, "redio"}, {true, DdingMateConfig.FontStyle.BERRY, "berry"}}) {
                ctx.runOnClient(c -> {
                    DdingMate.config().fontEverywhere = (Boolean) f[0];
                    DdingMate.config().font = (DdingMateConfig.FontStyle) f[1];
                    c.setScreen(new net.minecraft.client.gui.screen.ChatScreen(""));
                });
                ctx.waitTicks(10);
                ctx.takeScreenshot("serverpack-chat-" + f[2]);
            }
            // 전문가 탭: 스킬 판매가(보석 아이콘), 판매 시세(세이지 도구)
            ctx.runOnClient(c -> {
                DdingMate.config().fontEverywhere = false;
                DdingMate.config().font = DdingMateConfig.FontStyle.GALMURI;
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("전문가");
                s.jumpTo("스킬 판매가");
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("serverpack-expert-skill-icons");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).jumpTo("판매 시세"));
            ctx.waitTicks(10);
            ctx.takeScreenshot("serverpack-expert-sage-tools");

            // 원래대로: 설정·창 크기 되돌리기(리소스팩은 켜 둔 채 다음 시험으로)
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                cfg.font = keepFont[0];
                cfg.fontEverywhere = keep[0];
                cfg.opened = keep[1];
                cfg.tutorialDone = keep[2];
                cfg.save();
                kr.ddingmate.hud.HudLayout.bounds().clear();
                c.getWindow().setWindowedSize(854, 480);
                c.options.getGuiScale().setValue(0);
                c.onResolutionChanged();
            });
        }
    }
}
