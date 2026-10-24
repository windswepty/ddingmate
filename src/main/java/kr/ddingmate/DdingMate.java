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
    /** 업데이트 소식을 이번 실행에서 이미 확인했는지(타이틀 화면이나 접속 때 한 번) */
    private static boolean notesChecked;

    @Override
    public void onInitializeClient() {
        config = DdingMateConfig.load();
        rebuildRules();
        RecipeBook.load();
        BagTracker.load();
        AlertLog.load();
        PlaytimeTracker.load();
        CraftTimers.load();
        kr.ddingmate.skill.SkillTimers.load();

        // 기본 키는 비워 둔다. 서버 조작(F·Q·숫자키·Shift 조합)과 겹치지 않게 사용자가 직접 지정한다.
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ddingmate.open_settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.ddingmate"));

        ClientTickEvents.END_CLIENT_TICK.register(DdingMate::onTick);
        // 서버 창 옆 도우미 판: 바리스타 손님 창 → 카페 판, 밀키 요리 상점 → 부엌 판, 제작 시설 → 가진 재료로 만들기 판
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            // 새 버전으로 처음 켰을 때: 타이틀 화면 위에 업데이트 소식 창(한 번만)
            // 자동 시험(fabric.client.gametest)에서는 띄우지 않는다(시험이 창을 직접 연다)
            if (screen instanceof net.minecraft.client.gui.screen.TitleScreen && !notesChecked && System.getProperty("fabric.client.gametest") == null) {
                notesChecked = true;
                java.util.List<kr.ddingmate.news.UpdateNotes.Release> notes = kr.ddingmate.news.UpdateNotes.pending(config);
                if (!notes.isEmpty()) client.send(() -> {
                    kr.ddingmate.gui.UpdateNotesScreen s = new kr.ddingmate.gui.UpdateNotesScreen(screen, notes);
                    kr.ddingmate.news.UpdateNotes.markSeen(config);
                    client.setScreen(s);
                });
            }
            if (screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register(DdingMate::renderSidePanel);
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register(
                        (s, mx, my, button) -> kr.ddingmate.expert.Barista.kind() != kr.ddingmate.expert.Barista.Kind.NONE
                                || kr.ddingmate.expert.Cooking.shopOpen() || kr.ddingmate.expert.ShopPrices.openShop() != null
                                || !kr.ddingmate.expert.ShopPrices.jewels().isEmpty()
                                || !kr.ddingmate.craft.FacilityPanel.click(mx, my, button));
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseScroll(screen).register(
                        (s, mx, my, h2, v) -> !(kr.ddingmate.ui.SidePanel.resizeScroll(mx, my, v)
                                || kr.ddingmate.craft.FacilityPanel.scrolled(mx, my, v)));
            }
        });
        // 서버 리소스팩을 받거나 바꾸면 섬 그림 글꼴을 다시 읽는다
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public net.minecraft.util.Identifier getFabricId() {
                        return net.minecraft.util.Identifier.of(MOD_ID, "island_icons");
                    }

                    @Override
                    public void reload(net.minecraft.resource.ResourceManager manager) {
                        kr.ddingmate.craft.IslandIcons.clearFonts();
                        kr.ddingmate.expert.IslandCrops.onResourceReload(); // 작물 단계 표(트립와이어 상태)를 새 리소스팩에서
                        Notifier.onResourceReload();
                    }
                });
        // 같은 구석이면 등록한 순서대로 쌓인다: 일정 → 할 일 → 재료 → 내구도
        // 설치만 하고 아직 /띵메이트 를 열어 보지 않았으면 화면에 아무것도 띄우지 않는다
        // 띵타이쿤 대기 서버(접속 직후 스폰으로 옮겨지기 전)에서도 띄우지 않는다
        // Tab 접속자 목록은 늘 HUD 위에 온다(미니맵처럼 HUD 는 목록 뒤에 비쳐 보임)
        // 채팅 바로 아래 층에 그린다: 새 채팅이 올라오면 채팅(반투명 배경)이 HUD 위에 겹치고 HUD 는 뒤로 비쳐 보인다.
        // (예전엔 HUD 를 맨 마지막에 그려서, 채팅 자리에서는 HUD 가 채팅 뒤가 아니라 깊이 검사에 걸려 잘려 보였다, 2026-10-24)
        net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback.EVENT.register(layers -> layers.attachLayerBefore(
                net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer.CHAT, net.minecraft.util.Identifier.of(MOD_ID, "huds"), DdingMate::renderHuds));
        // 내가 보낸 단축어(ㄱ1 등)로 몇 번 가방을 여는지 안다. 읽기만 하고 막거나 바꾸지 않는다.
        ClientSendMessageEvents.CHAT.register(BagTracker::onOutgoing);
        ClientSendMessageEvents.CHAT.register(kr.ddingmate.game.Nickname::onSent); // 돌아온 내 채팅에서 한글 닉네임 배우기
        ClientSendMessageEvents.COMMAND.register(BagTracker::onOutgoing);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Notifier.onJoin();
            kr.ddingmate.inquiry.Replies.onJoin(); // 보낸 문의에 답변이 왔는지(접속 직후·10분마다)
            kr.ddingmate.news.Notices.onJoin(); // 개발자 공지(접속 직후·30분마다)
            // 타이틀 화면을 거치지 않고 바로 접속했으면 업데이트 소식은 채팅에 [바뀐 점 보기]로
            notesChecked = true;
            java.util.List<kr.ddingmate.news.UpdateNotes.Release> notes = System.getProperty("fabric.client.gametest") != null
                    ? java.util.List.of() : kr.ddingmate.news.UpdateNotes.pending(config);
            if (!notes.isEmpty()) {
                // 누르면 /띵메이트 → 소식 창이 먼저 열린다(명령어는 /띵메이트 하나)
                String fromTo = kr.ddingmate.news.UpdateNotes.fromTo(config.notesSeenVersion);
                kr.ddingmate.news.UpdateNotes.holdUnread(notes);
                kr.ddingmate.news.UpdateNotes.markSeen(config);
                client.execute(() -> Notifier.chat(net.minecraft.text.Text.literal("띵메이트가 새 버전이에요 (" + fromTo + ") ")
                        .formatted(net.minecraft.util.Formatting.WHITE)
                        .append(net.minecraft.text.Text.literal("[바뀐 점 보기]").styled(st -> st.withColor(net.minecraft.util.Formatting.AQUA)
                                .withUnderline(true)
                                .withClickEvent(new net.minecraft.text.ClickEvent(net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/띵메이트"))))));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerHud.reset();
            kr.ddingmate.inquiry.Replies.onDisconnect();
            kr.ddingmate.news.Notices.onDisconnect();
            kr.ddingmate.todo.TodoList.resetStaminaBaseline();
            wasInGame = false;
            BagTracker.save();
            PlaytimeTracker.save();
            kr.ddingmate.counter.IconCache.save();
            CraftTimers.onDisconnect();
            kr.ddingmate.skill.SkillTimers.save();
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            kr.ddingmate.debug.DevRecord.message(overlay ? "overlay" : "chat", message); // 개발용 원본 기록(꺼져 있으면 아무것도 안 함)
            BagTracker.onOverlay(message.getString()); // "[아이템 저장소] 조약돌 1개 수납! (2/5,000)" → 저장소 재고
            if (overlay) ServerHud.onOverlay(message.getString()); // 채팅으로 온 액션바: 잠수 채널 알아보기(일반 채팅은 안 씀)
            kr.ddingmate.expert.OceanOrder.onMessage(message.getString()); // 오션오더 도착·성공·실패(채팅·액션바)
            if (!overlay) {
                KeywordWatcher.onMessage(message, null);
                TodoList.onChat(message.getString()); // 할 일 "자동으로 세기 문구"
                kr.ddingmate.game.ServerHud.onChat(message.getString()); // "[서버 리붓] 3분 뒤 현재 채널이 재부팅됩니다."
                PlaytimeTracker.onChat(message.getString()); // 서버가 알려 주는 일일 접속 시간·받은 보상
                CraftTimers.onChat(message.getString()); // 제작 시작·회수 메시지
                kr.ddingmate.counter.MaterialCounter.onChat(message.getString()); // 고정 안 한 핀은 한 번 만들면 뺀다
                kr.ddingmate.expert.OreGolem.onChat(message.getString()); // "광석 골렘이 등장했습니다!" → 출현 쿨타임
                kr.ddingmate.game.Nickname.onChat(message.getString()); // 귓속말·내 채팅에서 한글 닉네임(문의에 함께 보냄)
                kr.ddingmate.skill.SkillTimers.onChat(message.getString()); // "곡괭이 나가신다! 스킬을 활성화했습니다." 등
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
        // Tab(접속자 목록)이 떠 있는 동안은 그리지 않는다: 서버 Tab 판 밑으로 잘려 보이거나 겹쳐 보이면 이질적이다
        if (playerListShown()) return;
        // 잠수 채널은 서버도 화면을 비워 두는 곳이라 HUD 를 띄우지 않는다
        if (ServerHud.afk()) return;
        drawAll(ctx, tick);
    }

    private static void drawAll(net.minecraft.client.gui.DrawContext ctx, net.minecraft.client.render.RenderTickCounter tick) {
        kr.ddingmate.hud.HudMascot.begin();
        ScheduleHud.render(ctx, tick);
        TodoHud.render(ctx, tick);
        CounterHud.render(ctx, tick);
        DurabilityHud.render(ctx, tick);
        kr.ddingmate.hud.SkillHud.render(ctx, tick);
        kr.ddingmate.hud.HudMascot.end(ctx); // HUD 친구는 판을 다 그린 뒤 자리 나는 판 위에
    }

    /** 지금 Tab(접속자 목록)이 화면에 떠 있는지 — 바닐라와 같은 조건 */
    public static boolean playerListShown() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.options == null || !c.options.playerListKey.isPressed()) return false;
        return !c.isInSingleplayer() || c.player.networkHandler.getListedPlayerListEntries().size() > 1
                || c.world.getScoreboard().getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.LIST) != null;
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

    /** 한 창에는 판 하나만: 바리스타 → 요리 상점 → 제작 시설 순 */
    private static void renderSidePanel(net.minecraft.client.gui.screen.Screen screen, net.minecraft.client.gui.DrawContext ctx,
                                        int mouseX, int mouseY, float delta) {
        kr.ddingmate.ui.SidePanel.beginFrame();
        if (kr.ddingmate.expert.Barista.kind() == kr.ddingmate.expert.Barista.Kind.NONE
                && !kr.ddingmate.expert.Cooking.shopOpen() && kr.ddingmate.expert.ShopPrices.openShop() == null
                && kr.ddingmate.expert.ShopPrices.jewels().isEmpty()) {
            kr.ddingmate.craft.FacilityPanel.render(ctx, screen, mouseX, mouseY); // 판 배율은 안에서(서버 칸 강조는 실제 자리)
            return;
        }
        // 나머지 판은 통째로 판 배율 안에서 그린다(마우스도 판 좌표로)
        int mx = kr.ddingmate.ui.SidePanel.mouse(mouseX), my = kr.ddingmate.ui.SidePanel.mouse(mouseY);
        kr.ddingmate.ui.SidePanel.push(ctx);
        try {
            if (kr.ddingmate.expert.Barista.kind() != kr.ddingmate.expert.Barista.Kind.NONE) {
                kr.ddingmate.expert.BaristaPanel.render(ctx, screen);
            } else if (kr.ddingmate.expert.Cooking.shopOpen()) {
                kr.ddingmate.expert.CookingPanel.render(ctx, screen, mx, my);
            } else if (kr.ddingmate.expert.ShopPrices.openShop() != null) {
                kr.ddingmate.expert.ShopPanel.render(ctx, screen, mx, my);
            } else {
                kr.ddingmate.expert.JewelPanel.render(ctx, screen, mx, my);
            }
        } finally {
            kr.ddingmate.ui.SidePanel.pop(ctx);
        }
    }

    private static void onTick(MinecraftClient client) {
        kr.ddingmate.ui.HudAuto.tick(client);
        Notifier.tick(client);
        kr.ddingmate.inquiry.Replies.tick(client);
        kr.ddingmate.news.Notices.tick(client);
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
        kr.ddingmate.skill.SkillSignals.tick(client); // 스킬 효과가 남은 틱으로 지속 시간 맞추기
        kr.ddingmate.expert.CropTimer.tick(client); // 아일랜드 작물 15분 타이머 단축키(눌리는 순간만)
        tickCounter++;
        // 시설 창은 0.1초마다 읽는다: "제작이 완료되었습니다"로 바뀌는 순간을 잡아 완료 시각을 0.1초 안으로 맞춘다
        if (tickCounter % 2 == 0 && client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) {
            CraftTimers.scanScreen(client);
        }
        // 스킬 창에서 읽은 전문가 스킬은 창을 닫을 때 채팅 한 번으로 모아서 알린다
        kr.ddingmate.skill.SkillReport.tick(client);
        // 제작 완료 알림은 매 틱 확인(예전: 1초에 한 번이라 평균 0.5초 늦게 울림)
        if (client.world != null && client.player != null) CraftTimers.tick();
        if (tickCounter % 10 == 0) {
            ServerHud.tick(client); // 오른쪽 위 위치·채널 표시 읽기
            boolean inGame = ServerHud.inGame();
            if (inGame && !wasInGame) onEnteredGame();
            wasInGame = inGame;
            MaterialCounter.tick(client);
            DurabilityTracker.tick(client);
            PlaytimeTracker.scanScreen(client); // 내가 연 /접속시간 창이면 서버 기록에 맞춘다
            kr.ddingmate.craft.ExpertSkills.scan(client); // 전문가 스킬 창의 "제작 시간이 N% 감소" → 스킬 레벨 자동 설정
            kr.ddingmate.expert.Barista.scan(client); // 손님 주문 창의 커피
            kr.ddingmate.expert.Cooking.scan(client); // 밀키 상점 창의 요리 가격
            kr.ddingmate.expert.ShopPrices.scan(client); // 로니·샤키·포스·픽스·카이 상점 창의 판매품 가격
            kr.ddingmate.expert.PriceSkills.scan(client); // /전문가 스킬 창의 판매가 스킬 증가율
            kr.ddingmate.craft.FacilityPanel.scan(client); // 제작 시설 창: 가진 재료로 만들 수 있는 레시피
            kr.ddingmate.expert.PetFish.scan(client); // 어항 창의 반려어 허기·기분·활력·성장도
            kr.ddingmate.expert.OceanOrder.scanScreen(client); // /오션오더 창·칸의 남은 시간
            TodoList.QuestContext.scan(client); // 지금 연 의뢰 창이 일일·주간 중 어느 쪽인지
            TodoList.scanSidebar(client); // 의뢰 트래커의 "스태미나 소모하기 (N/…)"
            kr.ddingmate.debug.ScreenNotes.tick(client); // 서버 창 모양 기록(내 컴퓨터에만)
            kr.ddingmate.debug.DevRecord.tick(client); // 개발용 원본 기록: 보스바·사이드바·창(내 컴퓨터에만, 기본 꺼짐)
            if (client.world != null) kr.ddingmate.counter.IconCache.tick(client); // 열어 본 창의 아이템 모양을 기억
        }
        // 알림 판정은 1초에 한 번. 월드(서버)에 들어가 있을 때만 울린다.
        if (tickCounter % 20 != 0 || client.world == null || client.player == null) return;
        ZonedDateTime now = ZonedDateTime.now(ScheduleEngine.KST);
        for (ScheduleEngine.Alert a : ScheduleEngine.dueAlerts(rules, config::isEnabled, config::leadsOf, now, FIRED)) {
            // 그 마을 채널 안에 있으면 서버가 재부팅을 직접 예고하므로 같은 알림을 또 내지 않는다(HUD 에는 남은 시간이 보인다)
            if (a.rule().id().equals("village_reboot") && ServerHud.inVillageChannel(config.villageChannel)) continue;
            Notifier.schedule(a);
        }
        TodoList.tick(now);
        long ms = System.currentTimeMillis();
        kr.ddingmate.expert.PetFish.tick(ms);
        kr.ddingmate.expert.IslandCrops.tick(client); // 마을 경작지 아일랜드 작물 단계(주변 블록만 읽음)
        kr.ddingmate.expert.OceanOrder.tick(ms);
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
