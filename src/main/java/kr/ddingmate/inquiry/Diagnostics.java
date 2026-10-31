package kr.ddingmate.inquiry;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 문의에 붙이는 진단 정보: 개발자가 "어떤 설정에서, 무엇이 돌고 있었고, 무슨 경고가 났는지"를 바로 볼 수 있게.
 * 개인 정보(파일 경로의 사용자 폴더 이름, 문의 비밀 번호표)는 넣지 않는다. 다른 사람 채팅도 넣지 않는다(띵메이트 경고만)
 */
public final class Diagnostics {

    private Diagnostics() {}

    /** 띵메이트 경고: 로그의 WARN·ERROR 줄 중 한글이 들어간 것(띵메이트 문구는 한글, 다른 모드·게임은 대부분 영어) */
    private static final Pattern OUR_WARN = Pattern.compile("/(WARN|ERROR)\\].*\\p{IsHangul}");
    private static final String HEADER = "\n\n— 띵메이트 진단 —\n";

    /** 문의 내용 뒤에 붙일 짧은 요약(1500자 한도 안에 들어가게 남은 만큼만). 자리가 거의 없으면 그대로 */
    public static String attach(String text, MinecraftClient client) {
        String t = text == null ? "" : text.strip();
        int room = Inquiry.MAX_LENGTH - t.length() - HEADER.length();
        if (room < 80) return t;
        String s = summary(client);
        return t + HEADER + (s.length() <= room ? s : s.substring(0, room - 1) + "…");
    }

    /** 짧은 요약: 설정·지금 도는 것·최근 경고 3개 */
    public static String summary(MinecraftClient client) {
        DdingMateConfig cfg = DdingMate.config();
        StringBuilder sb = new StringBuilder();
        sb.append("스킬 채광").append(cfg.craft.mining).append(" 재배").append(cfg.craft.farming)
                .append(" 해양").append(cfg.craft.marine).append(" 사냥").append(cfg.craft.hunting)
                .append(" · HUD 줄 ").append(cfg.hudLines >= DdingMateConfig.HUD_LINES_ALL ? "전부" : cfg.hudLines)
                .append(cfg.hudGroupCategory ? " · 분류 묶기" : "").append('\n');
        List<kr.ddingmate.craft.CraftTimers.Group> groups = kr.ddingmate.craft.CraftTimers.groups();
        long unknown = groups.stream().filter(g -> g.unknown() || g.estimated()).count();
        sb.append("제작 줄 ").append(groups.size()).append("(시간 어림·모름 ").append(unknown).append(")")
                .append(" · 할 일 ").append(cfg.todo.items.size()).append("+직접 ").append(cfg.todo.manual == null ? 0 : cfg.todo.manual.size())
                .append(" · 추적 장비 ").append(cfg.durability.tracked.size())
                .append(" · 지역 ").append(kr.ddingmate.game.ServerHud.area() == null ? "모름" : kr.ddingmate.game.ServerHud.area().label)
                .append(kr.ddingmate.game.ServerHud.afk() ? "(잠수)" : "").append('\n');
        List<String> warns = recentWarnings(client, 3);
        if (!warns.isEmpty()) sb.append("최근 경고:\n").append(String.join("\n", warns)).append('\n');
        sb.append("다른 모드 ").append(otherMods().size()).append("개");
        return sb.toString().strip();
    }

    /** 전체(복사용): 요약 + 다른 모드 목록 + 제작 줄 + 최근 경고 10개 */
    public static String full(MinecraftClient client) {
        StringBuilder sb = new StringBuilder("띵메이트 진단 정보\n");
        if (client.player != null) sb.append(Inquiry.infoLine(Inquiry.info(client))).append('\n');
        sb.append(summary(client)).append("\n\n");
        sb.append("다른 모드: ").append(String.join(", ", otherMods())).append("\n\n");
        long now = kr.ddingmate.util.Clock.now();
        sb.append("제작 줄:\n");
        for (kr.ddingmate.craft.CraftTimers.Group g : kr.ddingmate.craft.CraftTimers.groups()) {
            sb.append("  ").append(kr.ddingmate.craft.CraftTimers.displayLabel(g, now)).append(" · ")
                    .append(kr.ddingmate.craft.CraftTimers.remainingText(g, now)).append('\n');
        }
        List<String> warns = recentWarnings(client, 10);
        sb.append("\n최근 띵메이트 경고:\n").append(warns.isEmpty() ? "  없음" : String.join("\n", warns));
        return sb.toString();
    }

    private static List<String> otherMods() {
        List<String> out = new ArrayList<>();
        FabricLoader.getInstance().getAllMods().forEach(m -> {
            String id = m.getMetadata().getId();
            if (id.equals("minecraft") || id.equals("java") || id.startsWith("fabric") || id.equals(DdingMate.MOD_ID)) return;
            if (m.getContainingMod().isPresent()) return; // 다른 모드 안에 든 것은 빼고
            out.add(id + " " + m.getMetadata().getVersion().getFriendlyString());
        });
        out.sort(String::compareTo);
        return out;
    }

    /** latest.log 끝부분(최대 2MB)에서 띵메이트 경고 n개(새것이 아래). 사용자 폴더 경로는 ~ 로 가린다 */
    static List<String> recentWarnings(MinecraftClient client, int n) {
        Path log = client.runDirectory.toPath().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) return List.of();
        List<String> out = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - 2L * 1024 * 1024);
            byte[] buf = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(buf);
            String home = System.getProperty("user.home", "");
            for (String line : new String(buf, StandardCharsets.UTF_8).split("\r?\n")) {
                if (!OUR_WARN.matcher(line).find()) continue;
                String l = home.isEmpty() ? line : line.replace(home, "~");
                out.add("  " + (l.length() > 160 ? l.substring(0, 159) + "…" : l));
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return out.subList(Math.max(0, out.size() - n), out.size());
    }

    /** 시험용: 요약을 붙인 결과 */
    public static String debugAttach(String text, MinecraftClient client) {
        return attach(text, client);
    }
}
