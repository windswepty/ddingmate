package kr.ddingmate;

import kr.ddingmate.command.AlarmCommands;
import kr.ddingmate.config.AlarmConfig;
import kr.ddingmate.counter.BagTracker;
import kr.ddingmate.counter.MaterialCounter;
import kr.ddingmate.counter.RecipeBook;
import kr.ddingmate.gui.AlarmConfigScreen;
import kr.ddingmate.hud.AlarmHud;
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

    private static AlarmConfig config;
    private static List<EventRule> rules = List.of();
    private static final Map<String, Long> FIRED = new HashMap<>();
    private static KeyBinding toggleHudKey;
    private static KeyBinding openSettingsKey;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        config = AlarmConfig.load();
        rebuildRules();
        RecipeBook.load();
        BagTracker.load();
        AlertLog.load();
        PlaytimeTracker.load();

        // 기본 키는 비워 둔다. 서버 조작(F·Q·숫자키·Shift 조합)과 겹치지 않게 사용자가 직접 지정한다.
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.open_settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));

        ClientTickEvents.END_CLIENT_TICK.register(DdingMate::onTick);
        // 같은 구석이면 등록한 순서대로 쌓인다: 일정 → 할 일 → 재료 → 내구도
        HudRenderCallback.EVENT.register(AlarmHud::render);
        HudRenderCallback.EVENT.register(TodoHud::render);
        HudRenderCallback.EVENT.register(CounterHud::render);
        HudRenderCallback.EVENT.register(DurabilityHud::render);
        // 내가 보낸 단축어(ㄱ1 등)로 몇 번 가방을 여는지 안다. 읽기만 하고 막거나 바꾸지 않는다.
        ClientSendMessageEvents.CHAT.register(BagTracker::onOutgoing);
        ClientSendMessageEvents.COMMAND.register(BagTracker::onOutgoing);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BagTracker.save();
            PlaytimeTracker.save();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) KeywordWatcher.onMessage(message, null);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
                KeywordWatcher.onMessage(message, sender == null ? null : sender.getName()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> AlarmCommands.register(dispatcher));

        LOG.info("띵메이트 로드됨 (일정 {}개)", rules.size());
    }

    private static void onTick(MinecraftClient client) {
        while (toggleHudKey.wasPressed()) {
            config.hudEnabled = !config.hudEnabled;
            config.save();
            Notifier.chat(config.hudEnabled ? "HUD 를 켰어요." : "HUD 를 껐어요.");
        }
        while (openSettingsKey.wasPressed()) {
            client.setScreen(new AlarmConfigScreen(client.currentScreen));
        }
        BagTracker.tick(client);
        PlaytimeTracker.tick(client);
        tickCounter++;
        if (tickCounter % 10 == 0) {
            MaterialCounter.tick(client);
            DurabilityTracker.tick(client);
        }
        // 알림 판정은 1초에 한 번. 월드(서버)에 들어가 있을 때만 울린다.
        if (tickCounter % 20 != 0 || client.world == null || client.player == null) return;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (ScheduleEngine.Alert a : ScheduleEngine.dueAlerts(rules, config::isEnabled, config::leadsOf, now, FIRED)) {
            Notifier.schedule(a);
        }
        TodoList.tick(now);
    }

    public static AlarmConfig config() {
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
        config = AlarmConfig.load();
        rebuildRules();
    }
}
