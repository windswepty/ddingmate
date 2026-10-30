package kr.ddingmate.game;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.counter.ItemKeys;
import kr.ddingmate.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버가 화면 오른쪽 위에 보여 주는 "에버그린 마을 (스폰4)" 같은 위치·채널 글자를 읽는다.
 * 띵타이쿤은 이 글자를 보스바 제목으로 보낸다(막대는 리소스팩이 투명하게 바꿔 둠). 읽기만 하고 아무것도 보내지 않는다.
 *
 * 이 글자가 보이면 대기 서버가 아니라 실제 게임(스폰 이후)에 들어온 것으로 본다.
 */
public final class ServerHud {

    /** 지역 이름 → 설정 칸. 서버 표기와 같은 순서 */
    public enum Area {
        VILLAGE("마을"), HUNT("노크틸라"), WILD("야생"), ISLAND("아일랜드"), SPAWN("스폰");

        public final String label;

        Area(String label) {
            this.label = label;
        }
    }

    /** 사냥 지역은 서버 리붓 공지에 "사냥 2, 6, 10 … 채널"로 나온다(같은 공지의 마을·야생·스폰은 채널 표시와 같은 낱말). 둘 다 받는다 */
    private static final Pattern CHANNEL = Pattern.compile("\\((마을|노크틸라|사냥|야생|아일랜드|스폰)\\s*(\\d{1,3})\\)");

    private static Area area;
    private static int channel;
    /** 채널 표시 앞의 장소 이름("세레니티 (아일랜드6)" → "세레니티"). 판 자동 바꾸기(지역)에 쓴다 */
    private static String place = "";
    /** 이번 접속에서 게임 화면(채널 표시)을 본 적이 있는지 */
    private static boolean seen;
    /** 채널 표시(오른쪽 위 보스바)를 마지막으로 본 시각 */
    private static long barSeenAt;
    /** 채널 표시가 이만큼 안 보이면 게임 밖(채널 재시작 대기·로비)으로 본다 */
    static final long BAR_GONE_MS = 3_000;

    private ServerHud() {}

    /** 10틱마다 */
    public static void tick(MinecraftClient client) {
        if (client.inGameHud == null || client.player == null) return;
        boolean ocean = false, channelSeen = false;
        for (ClientBossBar bar : ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ddingmate$bossBars().values()) {
            // 오션오더 HUD: 서버 리소스팩의 오션오더 HUD 글꼴로 그린 보스바(2026-10-21 리소스팩 hud_oceanorder_hud_*)
            // 재화 바·날짜·채널 표시가 한 보스바로 와서, 오션오더 HUD 가 같은 줄에 실려도 나머지(채널 표시)는 계속 읽는다
            // (예전에는 오션오더 줄을 통째로 건너뛰어 채널 표시를 3초 넘게 못 봐 '게임 밖'으로 보고 HUD 를 모두 숨겼다)
            kr.ddingmate.todo.TodoList.scanStamina(bar.getName()); // 아일랜드 프로필 HUD 의 지금 스태미나 → 오늘 소모량
            String oceanHud = kr.ddingmate.expert.OceanOrder.hudText(bar.getName());
            String rest = bar.getName().getString();
            if (oceanHud != null) {
                ocean = true;
                kr.ddingmate.expert.OceanOrder.onHud(oceanHud, System.currentTimeMillis());
                rest = kr.ddingmate.expert.OceanOrder.withoutHud(bar.getName());
            }
            kr.ddingmate.expert.OceanOrder.onMessage(rest); // 오션오더 남은 시간·오늘 횟수가 보스바로 올 수도
            if (channelSeen) continue;
            String plain = ItemKeys.stripGlyphs(rest);
            Matcher m = CHANNEL.matcher(plain);
            if (!m.find()) continue;
            Area a = areaOf(m.group(1));
            int ch = Integer.parseInt(m.group(2));
            if (a == null || ch <= 0) continue;
            String before = plain.substring(0, m.start());
            Matcher pm = PLACE.matcher(before);
            String nowPlace = "";
            while (pm.find()) nowPlace = pm.group().trim();
            if (!nowPlace.equals(place)) DdingMate.LOG.info("장소 인식: {}", nowPlace);
            place = nowPlace;
            boolean changed = a != area || ch != channel;
            if (changed) rebootAt = 0; // 채널을 옮기면 이전 채널의 재부팅 예고는 더 이상 내 일이 아니다
            area = a;
            channel = ch;
            seen = true;
            barSeenAt = System.currentTimeMillis();
            afk = false; // 채널 표시가 다시 보이면 잠수 채널에서 나온 것
            if (changed) {
                DdingMate.LOG.info("채널 인식: {} {}채널", a.label, ch);
                remember(a, ch);
            }
            channelSeen = true;
        }
        kr.ddingmate.expert.OceanOrder.hudPresent(ocean, System.currentTimeMillis());
    }

    /** 자동 인식이 켜져 있으면 그 지역의 "내 채널"을 지금 채널로 바꿔 재시작 알림이 따라오게 한다 */
    private static void remember(Area a, int ch) {
        DdingMateConfig cfg = DdingMate.config();
        if (!cfg.autoChannel) return;
        int before = get(cfg, a);
        if (before == ch) return;
        switch (a) {
            case VILLAGE -> cfg.villageChannel = ch;
            case HUNT -> cfg.huntChannel = ch;
            case WILD -> cfg.wildChannel = ch;
            case ISLAND -> cfg.islandChannel = ch;
            case SPAWN -> cfg.spawnChannel = ch;
        }
        cfg.sanitize();
        cfg.save();
        DdingMate.rebuildRules();
    }

    public static int get(DdingMateConfig cfg, Area a) {
        return switch (a) {
            case VILLAGE -> cfg.villageChannel;
            case HUNT -> cfg.huntChannel;
            case WILD -> cfg.wildChannel;
            case ISLAND -> cfg.islandChannel;
            case SPAWN -> cfg.spawnChannel;
        };
    }

    private static Area areaOf(String label) {
        if ("사냥".equals(label)) return Area.HUNT;
        for (Area a : Area.values()) if (a.label.equals(label)) return a;
        return null;
    }

    /** 지금 있는 곳. 모르면 null */
    public static Area area() {
        return area;
    }

    /** 채널 표시 앞의 장소 이름(모르면 "") */
    public static String place() {
        return place;
    }

    /** 시험용 */
    public static void debugPlace(Area a, String p) {
        area = a;
        place = p == null ? "" : p;
    }

    private static final Pattern PLACE = Pattern.compile("[가-힣A-Za-z][가-힣A-Za-z0-9 ]*[가-힣A-Za-z0-9]|[가-힣]");

    public static int channel() {
        return channel;
    }

    /**
     * 실제 게임에 들어와 있는지. 띵타이쿤은 접속하면 먼저 대기 서버에 들어갔다가 스폰으로 옮겨지는데,
     * 대기 서버에서는 띵메이트를 쓰지 않는다. 띵타이쿤이 아닌 곳(싱글플레이 등)은 늘 true.
     */
    public static boolean inGame() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null) return false;
        if (!onDdingtycoon(c)) return true;
        // 채널 표시가 지금 보일 때만 게임 중이다. 채널이 재시작되면 대기 서버(로비)로 옮겨져 채널 표시가 사라지는데,
        // 예전에는 한 번 본 뒤로 계속 게임 중으로 봐서 로비에서도 HUD 가 떴다
        if (seen) return System.currentTimeMillis() - barSeenAt <= BAR_GONE_MS;
        // 채널 표시를 아직 못 읽었어도, 업적이 잔뜩 온 서버(대기 서버는 2개)는 게임 서버로 본다
        var net = c.getNetworkHandler();
        return net != null && net.getAdvancementHandler().getManager().getAdvancements().size() >= 20;
    }

    public static boolean onDdingtycoon(MinecraftClient c) {
        if (c.isIntegratedServerRunning()) return false;
        ServerInfo info = c.getCurrentServerEntry();
        return info != null && info.address != null && info.address.toLowerCase(Locale.ROOT).contains("ddingtycoon");
    }

    /**
     * 서버 공지 "[서버 리붓] 3분 뒤 현재 채널이 재부팅됩니다." (2026-05~10 실제 로그: 30·20·10·5·3·1분 전).
     * 야생·아일랜드처럼 정해진 시각이 없는 채널도 이 공지로 남은 시간을 보여 준다.
     * 앞에 칭호·닉네임이 붙은 유저 채팅은 받지 않는다.
     */
    private static final Pattern REBOOT = Pattern.compile("^\\s*\\[\\s*서버\\s*리붓\\s*]\\s*(\\d+)\\s*(분|초)\\s*뒤\\s*현재\\s*채널");
    private static long rebootAt;

    public static void onChat(String message) {
        if (message != null && message.contains("[청소]")) onCleanChat(ItemKeys.stripGlyphs(message).trim(), System.currentTimeMillis());
        if (message != null && message.contains("[초기화]")) onPetResetChat(ItemKeys.stripGlyphs(message).trim(), System.currentTimeMillis());
        if (message == null || !message.contains("리붓")) return;
        Matcher m = REBOOT.matcher(ItemKeys.stripGlyphs(message));
        if (!m.find()) return;
        long n = Long.parseLong(m.group(1));
        rebootAt = System.currentTimeMillis() + (m.group(2).equals("분") ? n * 60_000 : n * 1000);
    }

    /**
     * 잠수 채널: 서버가 재화 바·채널 표시를 지우고 화면 아래에 "292초 뒤 잠수 포인트를 획득합니다. | 현재 잠수 포인트 : 4239"
     * (2026-10-10 실제)를 띄운다. 이 문구를 보면 잠수 중으로 보고, 채널 표시가 다시 보이면 끝낸다
     */
    private static final Pattern AFK = Pattern.compile("잠수\\s*포인트를\\s*획득|현재\\s*잠수\\s*포인트");
    private static boolean afk;

    /** 액션바(화면 아래 글) */
    public static void onOverlay(String message) {
        if (message == null || !message.contains("잠수")) return;
        if (AFK.matcher(ItemKeys.stripGlyphs(message)).find()) afk = true;
    }

    /** 잠수 채널에 있는지(이때는 띵메이트 HUD 를 그리지 않는다) */
    public static boolean afk() {
        return afk;
    }

    /** 지금 그 마을 채널 안에 있는지(그 채널 사람에게는 서버가 "N분 뒤 현재 채널이 재부팅됩니다"를 직접 알린다) */
    public static boolean inVillageChannel(int ch) {
        return ch > 0 && area == Area.VILLAGE && channel == ch;
    }

    /** 지금 채널이 재부팅될 시각(ms). 예고가 없거나 지났으면 0 */
    // ---------------------------------------------------------------- 바닥 아이템 청소

    /**
     * 실제 로그(2026-04~10, 약 1,900번): 10분마다 "[청소] 5분 후 떨어진 아이템이 전부 청소됩니다." → 3분·1분·30초·10초·5~1초 →
     * "[청소] 떨어진 아이템 170개가 청소되었습니다." 채널마다 시각이 달라서 이 문구로 맞춘다(/개인설정 청소알림이 꺼져 있으면 모름)
     */
    private static final Pattern CLEAN_SOON = Pattern.compile("^\\[청소\\]\\s*(\\d+)\\s*(분|초)\\s*후\\s*떨어진\\s*아이템");
    private static final Pattern CLEAN_DONE = Pattern.compile("^\\[청소\\]\\s*떨어진\\s*아이템.*청소되었습니다");
    public static final long CLEAN_EVERY_MS = 10 * 60_000L;
    private static long cleanAt;

    static void onCleanChat(String t, long now) {
        Matcher m = CLEAN_SOON.matcher(t);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            cleanAt = now + (m.group(2).equals("분") ? n * 60_000 : n * 1000);
        } else if (CLEAN_DONE.matcher(t).find()) {
            cleanAt = now + CLEAN_EVERY_MS;
        }
    }

    /** 다음 바닥 아이템 청소 시각(모르면 0). 지난 시각이면 10분씩 넘겨 본다 */
    public static long cleanAt() {
        if (cleanAt == 0) return 0;
        long now = System.currentTimeMillis();
        long at = cleanAt;
        while (at <= now) at += CLEAN_EVERY_MS;
        return at;
    }

    /** 지금 있는 곳에서 바닥 청소가 일어나나: 아일랜드·스폰에는 청소가 없다(10-09~29 로그: 마을 375번·야생 23번, 아일랜드 70칸·스폰 149칸 모두 0번) */
    public static boolean cleansHere() {
        return area != Area.ISLAND && area != Area.SPAWN;
    }

    public static void debugClean(String t, long now) {
        onCleanChat(t, now);
    }

    // ---------------------------------------------------------------- 펫 전부 소환 해제

    /**
     * 실제 로그(2026-10-28 22:25~22:30, 23:25~23:30): "[초기화] 5분 후 펫이 전부 소환 해제됩니다." → 1분마다 4·3·2·1분 →
     * "[초기화] 펫 이 소환 해제되었습니다." 언제 오는지 정해진 주기를 아직 몰라서 안내가 온 때만 센다
     */
    private static final Pattern PET_RESET_SOON = Pattern.compile("^\\[초기화\\]\\s*(\\d+)\\s*(분|초)\\s*후\\s*펫이\\s*전부\\s*소환\\s*해제");
    private static final Pattern PET_RESET_DONE = Pattern.compile("^\\[초기화\\]\\s*펫\\s*이?\\s*소환\\s*해제되었습니다");
    private static long petResetAt;

    static void onPetResetChat(String t, long now) {
        Matcher m = PET_RESET_SOON.matcher(t);
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            petResetAt = now + (m.group(2).equals("분") ? n * 60_000 : n * 1000);
        } else if (PET_RESET_DONE.matcher(t).find()) {
            petResetAt = 0;
        }
    }

    /** 펫 전부 소환 해제 시각(안내가 없었거나 지났으면 0) */
    public static long petResetAt() {
        return petResetAt > System.currentTimeMillis() ? petResetAt : 0;
    }

    /** 시험용 */
    public static void debugPetReset(String t, long now) {
        onPetResetChat(t, now);
    }

    public static long rebootAt() {
        return rebootAt > System.currentTimeMillis() ? rebootAt : 0;
    }

    /** 접속이 끊기면 처음부터 다시 */
    public static void reset() {
        rebootAt = 0;
        afk = false;
        area = null;
        channel = 0;
        seen = false;
        barSeenAt = 0;
    }

    /** 시험용 */
    public static void debugSet(Area a, int ch) {
        if (a != area || ch != channel) {
            rebootAt = 0;
            cleanAt = 0; // 채널마다 청소 시각이 다르다
        }
        afk = false;
        area = a;
        channel = ch;
        seen = true;
        barSeenAt = Long.MAX_VALUE / 2; // 시험: 계속 보이는 것으로
        remember(a, ch);
    }
}
