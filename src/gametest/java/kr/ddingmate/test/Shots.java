package kr.ddingmate.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * 빠른 시험 / 전체 시험 나누기. 평소(./gradlew runClientGameTest)에는 스크린샷·실제 기록 재생·전시용 화면을 건너뛰고,
 * 출시·커밋 전(./gradlew runClientGameTest -Pfull)에만 모두 한다
 */
final class Shots {

    private Shots() {}

    /** 전체 시험인가(-Pfull → -Dddingmate.full=true) */
    static boolean full() {
        return Boolean.getBoolean("ddingmate.full");
    }

    /** 전체 시험일 때만 스크린샷 */
    static void take(ClientGameTestContext ctx, String name) {
        if (full()) ctx.takeScreenshot(name);
    }
}
