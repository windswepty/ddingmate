package kr.ddingmate;

import kr.ddingmate.command.DdingMateCommands;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.hud.ScheduleHud;
import kr.ddingmate.hud.CounterHud;
import kr.ddingmate.hud.DurabilityHud;
import kr.ddingmate.hud.TodoHud;
import kr.ddingmate.gear.DurabilityTracker;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.playtime.PlaytimeTracker;
import kr.ddingmate.todo.TodoList;
import kr.ddingmate.notify.KeywordWatcher;
import kr.ddingmate.notify.Notifier;
import kr.ddingmate.schedule.EventRule;
import kr.ddingmate.schedule.ScheduleEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import kr.ddingmate.craft.CraftTimers;
import kr.ddingmate.game.ServerHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 띵메이트. 위키에 공개된 서버 일정을 HUD로 보여 주고, 정해진 시점에 소리·토스트·채팅(내 화면에만)으로 알린다.
 * 서버로 아무 패킷·명령도 보내지 않으며, 게임 조작을 대신하지 않는다.
 */
public final class DdingMate implements ClientModInitializer {

    public static final String MOD_ID = "ddingmate";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static DdingMateConfig config;
    private static List<EventRule> rules = List.of();
    private static final Map<String, Long> FIRED = new HashMap<>();
    private static KeyBinding toggleHudKey;
    private static KeyBinding openSettingsKey;
    private static int tickCounter;
    private static boolean joinTipShown;
    /** 지난 틱에 실제 게임(스폰 이후)에 있었는지. 대기 서버에서 게임으로 넘어오는 순간을 안다 */
    private static boolean wasInGame;

    @Override
    public void onInitializeClient() {
        config = DdingMateConfig.load();
        rebuildRules();
        RecipeBook.load();
        BagTracker.load();
        AlertLog.load();
        PlaytimeTracker.load();
        CraftTimers.load();

        // 기본 키는 비워 둔다. 서버 조작(F·Q·숫자키·Shift 조합)과 겹치지 않게 사용자가 직접 지정한다.
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.open_settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));

        ClientTickEvents.END_CLIENT_TICK.register(DdingMate::onTick);
        // 같은 구석이면 등록한 순서대로 쌓인다: 일정 → 할 일 → 재료 → 내구도
        // 설치만 하고 아직 /띵메이트 를 열어 보지 않았으면 화면에 아무것도 띄우지 않는다
        // 띵타이쿤 대기 서버(접속 직후 스폰으로 옮겨지기 전)에서도 띄우지 않는다
        // Tab 접속자 목록은 늘 HUD 위에 온다(미니맵처럼 HUD 는 목록 뒤에 비쳐 보임)
        HudRenderCallback.EVENT.register(DdingMate::renderHuds);
        // 내가 보낸 단축어(ㄱ1 등)로 몇 번 가방을 여는지 안다. 읽기만 하고 막거나 바꾸지 않는다.
        ClientSendMessageEvents.CHAT.register(BagTracker::onOutgoing);
        ClientSendMessageEvents.COMMAND.register(BagTracker::onOutgoing);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerHud.reset();
            wasInGame = false;
            BagTracker.save();
            PlaytimeTracker.save();
            kr.ddingmate.counter.IconCache.save();
            CraftTimers.onDisconnect();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            BagTracker.onOverlay(message.getString()); // "[아이템 저장소] 조약돌 1개 수납! (2/5,000)" → 저장소 재고
            if (!overlay) {
                KeywordWatcher.onMessage(message, null);
                TodoList.onChat(message.getString()); // 할 일 "자동으로 세기 문구"
                kr.ddingmate.game.ServerHud.onChat(message.getString()); // "[서버 리붓] 3분 뒤 현재 채널이 재부팅됩니다."
                PlaytimeTracker.onChat(message.getString()); // 서버가 알려 주는 일일 접속 시간·받은 보상
                CraftTimers.onChat(message.getString()); // 제작 시작·회수 메시지
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
                KeywordWatcher.onMessage(message, sender == null ? null : sender.getName()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> DdingMateCommands.register(dispatcher));

        LOG.info("띵메이트 로드됨 (일정 {}개)", rules.size());
    }

    /**
     * 스폰으로 들어온 순간(대기 서버 다음). 처음 쓰는 사람에게는 게임을 켠 뒤 한 번 사용법을 알려 준다(설정에서 끌 수 있음).
     */
    /** 같은 구석이면 이 순서대로 쌓인다: 일정 → 할 일 → 재료 → 내구도 */
    private static void renderHuds(net.minecraft.client.gui.DrawContext ctx, net.minecraft.client.render.RenderTickCounter tick) {
        if (!config.opened || !ServerHud.inGame()) return;
        ScheduleHud.render(ctx, tick);
        TodoHud.render(ctx, tick);
        CounterHud.render(ctx, tick);
        DurabilityHud.render(ctx, tick);
    }

    private static void onEnteredGame() {
        if (joinTipShown) return;
        if (!config.opened) {
            joinTipShown = true;
            Notifier.chat("띵메이트가 설치됐어요! 채팅에 /띵메이트 를 입력하면 사용법 안내가 시작되고, 그다음부터 화면에 일정 HUD 가 나와요.");
        } else if (config.joinTip) {
            joinTipShown = true;
            Notifier.chat("채팅에 /띵메이트 를 입력하면 일정·재료·장비·할 일을 한 창에서 보고 바꿀 수 있어요.");
        }
    }

    private static void onTick(MinecraftClient client) {
        while (toggleHudKey.wasPressed()) {
            config.hudEnabled = !config.hudEnabled;
            config.save();
            Notifier.chat(config.hudEnabled ? "HUD 를 켰어요." : "HUD 를 껐어요.");
        }
        while (openSettingsKey.wasPressed()) {
            if (ServerHud.inGame()) client.setScreen(new DdingMateScreen(client.currentScreen));
        }
        BagTracker.tick(client);
        PlaytimeTracker.tick(client);
        tickCounter++;
        if (tickCounter % 10 == 0) {
            ServerHud.tick(client); // 오른쪽 위 위치·채널 표시 읽기
            boolean inGame = ServerHud.inGame();
            if (inGame && !wasInGame) onEnteredGame();
            wasInGame = inGame;
            MaterialCounter.tick(client);
            DurabilityTracker.tick(client);
            PlaytimeTracker.scanScreen(client); // 내가 연 /접속시간 창이면 서버 기록에 맞춘다
            CraftTimers.scanScreen(client); // 내가 연 시설 창의 남은 제작 시간
            kr.ddingmate.craft.ExpertSkills.scan(client); // 전문가 스킬 창의 "제작 시간이 N% 감소" → 스킬 레벨 자동 설정
            TodoList.QuestContext.scan(client); // 지금 연 의뢰 창이 일일·주간 중 어느 쪽인지
            kr.ddingmate.debug.ScreenNotes.tick(client); // 서버 창 모양 기록(내 컴퓨터에만)
            if (client.world != null) kr.ddingmate.counter.IconCache.tick(client); // 열어 본 창의 아이템 모양을 기억
        }
        // 알림 판정은 1초에 한 번. 월드(서버)에 들어가 있을 때만 울린다.
        if (tickCounter % 20 != 0 || client.world == null || client.player == null) return;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (ScheduleEngine.Alert a : ScheduleEngine.dueAlerts(rules, config::isEnabled, config::leadsOf, now, FIRED)) {
            Notifier.schedule(a);
        }
        TodoList.tick(now);
        CraftTimers.tick();
    }

    public static DdingMateConfig config() {
        return config;
    }

    public static List<EventRule> rules() {
        return rules;
    }

    /** 채널 설정이 바뀌면 다시 만든다. */
    public static void rebuildRules() {
        rules = ScheduleEngine.buildRules(config.channels());
    }

    public static void reloadConfig() {
        config = DdingMateConfig.load();
        rebuildRules();
    }
}
