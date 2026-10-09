package kr.ddingmate.command;

import com.mojang.brigadier.CommandDispatcher;
import kr.ddingmate.game.ServerHud;
import kr.ddingmate.gui.DdingMateScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /띵메이트 하나만 둔다. 입력하면 띵메이트 창이 열리고, 모든 조작은 창 안에서 한다.
 * 내 클라이언트에서만 처리되며 서버로 전송되지 않는다.
 */
public final class DdingMateCommands {

    private DdingMateCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        // 띵타이쿤 대기 서버(스폰으로 옮겨지기 전)에서는 명령어가 없는 것처럼 둔다
        d.register(literal("띵메이트").requires(s -> ServerHud.inGame()).executes(c -> {
            // 채팅창이 닫힌 다음 프레임에 연다.
            MinecraftClient client = MinecraftClient.getInstance();
            client.send(() -> client.setScreen(new DdingMateScreen(null)));
            return 1;
        }));
    }
}
