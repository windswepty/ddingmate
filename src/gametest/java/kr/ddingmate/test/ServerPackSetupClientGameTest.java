package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 맨 처음 시험: 띵타이쿤 서버 리소스팩을 켜 둔다. 뒤의 모든 시험이 실제 게임과 같은 그림·글꼴·모델 위에서 돈다
 * (2026-10-22 사용자: "모든 테스트는 띵타이쿤 텍스쳐팩을 가져와서"). 리소스팩이 없는 컴퓨터에서는 건너뛰고,
 * DDINGMATE_SERVER_PACK 으로 직접 시켰는데 못 찾으면 실패.
 */
public class ServerPackSetupClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!TestPack.wanted()) return;
        boolean[] on = new boolean[1];
        ctx.runOnClient(c -> on[0] = TestPack.enable(c));
        if (!on[0]) {
            if (TestPack.required()) throw new AssertionError("서버 리소스팩을 못 찾음: " + System.getenv("DDINGMATE_SERVER_PACK"));
            DdingMate.LOG.warn("띵타이쿤 서버 리소스팩이 없어 바닐라 그림으로 시험해요");
            return;
        }
        ctx.waitFor(c -> c.getOverlay() == null, 20 * 180);
        ctx.waitTicks(10);
        ctx.runOnClient(c -> {
            if (!TestPack.active(c)) throw new AssertionError("서버 리소스팩이 켜지지 않음");
            DdingMate.LOG.info("띵타이쿤 서버 리소스팩을 켜고 시험해요");
        });
    }
}
