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

    private static final Pattern CHANNEL = Pattern.compile("\\((마을|노크틸라|야생|아일랜드|스폰)\\s*(\\d{1,3})\\)");

    private static Area area;
    private static int channel;
    /** 이번 접속에서 게임 화면(채널 표시)을 본 적이 있는지 */
    private static boolean seen;

    private ServerHud() {}

    /** 10틱마다 */
    public static void tick(MinecraftClient client) {
        if (client.inGameHud == null || client.player == null) return;
        for (ClientBossBar bar : ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).ddingmate$bossBars().values()) {
            Matcher m = CHANNEL.matcher(ItemKeys.stripGlyphs(bar.getName().getString()));
            if (!m.find()) continue;
            Area a = areaOf(m.group(1));
            int ch = Integer.parseInt(m.group(2));
            if (a == null || ch <= 0) continue;
            boolean changed = a != area || ch != channel;
            if (changed) rebootAt = 0; // 채널을 옮기면 이전 채널의 재부팅 예고는 더 이상 내 일이 아니다
            area = a;
            channel = ch;
            seen = true;
            if (changed) {
                DdingMate.LOG.info("채널 인식: {} {}채널", a.label, ch);
                remember(a, ch);
            }
            return;
        }
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
        for (Area a : Area.values()) if (a.label.equals(label)) return a;
        return null;
    }

    /** 지금 있는 곳. 모르면 null */
    public static Area area() {
        return area;
    }

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
        if (seen) return true;
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
        if (message == null || !message.contains("리붓")) return;
        Matcher m = REBOOT.matcher(ItemKeys.stripGlyphs(message));
        if (!m.find()) return;
        long n = Long.parseLong(m.group(1));
        rebootAt = System.currentTimeMillis() + (m.group(2).equals("분") ? n * 60_000 : n * 1000);
    }

    /** 지금 채널이 재부팅될 시각(ms). 예고가 없거나 지났으면 0 */
    public static long rebootAt() {
        return rebootAt > System.currentTimeMillis() ? rebootAt : 0;
    }

    /** 접속이 끊기면 처음부터 다시 */
    public static void reset() {
        rebootAt = 0;
        area = null;
        channel = 0;
        seen = false;
    }

    /** 시험용 */
    public static void debugSet(Area a, int ch) {
        if (a != area || ch != channel) rebootAt = 0;
        area = a;
        channel = ch;
        seen = true;
        remember(a, ch);
    }
}
