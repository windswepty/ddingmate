package kr.ddingmate.news;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 업데이트 소식(2026-10-24 사용자 요청): 띵메이트를 새 버전으로 바꾸면 처음 켤 때 "몇 버전 → 몇 버전, 무엇이 추가·변경·수정됐는지"를
 * 창으로 보여 준다. 내용은 모드 안 assets/ddingmate/changelog.json(새 버전이 위). 인터넷으로 묻지 않는다.
 * <p>
 * 버전은 '0.3.2-pre.27' 의 앞부분(0.3.2)끼리 견준다: 같은 버전의 미리보기를 여러 번 바꿔 끼워도 한 번만 뜬다.
 * 처음 설치한 사람(띵메이트 창을 한 번도 안 연 사람)에게는 띄우지 않고, 예전 버전을 쓰던 사람에게는 이번 버전 소식을 보여 준다.
 */
public final class UpdateNotes {

    /** 버전 하나의 소식 */
    public record Release(String version, String date, List<String> added, List<String> changed, List<String> fixed, List<String> removed) {
        public List<String> added() {
            return added == null ? List.of() : added;
        }

        public List<String> changed() {
            return changed == null ? List.of() : changed;
        }

        public List<String> fixed() {
            return fixed == null ? List.of() : fixed;
        }

        public List<String> removed() {
            return removed == null ? List.of() : removed;
        }
    }

    private static List<Release> all;

    private UpdateNotes() {}

    /** 모드에 든 소식 전부(새 버전이 앞) */
    public static synchronized List<Release> all() {
        if (all == null) {
            List<Release> read = null;
            try (InputStream in = UpdateNotes.class.getResourceAsStream("/assets/ddingmate/changelog.json")) {
                if (in != null) read = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Release>>() {}.getType());
            } catch (Exception e) {
                DdingMate.LOG.warn("업데이트 소식을 읽지 못했어요", e);
            }
            all = read == null ? List.of() : List.copyOf(read);
        }
        return all;
    }

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(DdingMate.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** '0.3.2-pre.27' → '0.3.2' */
    public static String base(String v) {
        if (v == null) return "";
        int i = v.indexOf('-');
        return i < 0 ? v : v.substring(0, i);
    }

    /** 점으로 나뉜 숫자 버전 비교(숫자가 아닌 칸은 0) */
    public static int compare(String a, String b) {
        String[] x = base(a).split("\\."), y = base(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * seen 다음 버전부터 지금 버전까지의 소식(새것 먼저). seen 이 비어 있으면(예전 버전에서 올라옴) 지금 버전 것만.
     * current 는 시험에서 바꿔 넣는다
     */
    public static List<Release> since(String seen, String current) {
        List<Release> out = new ArrayList<>();
        boolean known = seen != null && !seen.isEmpty();
        for (Release r : all()) {
            if (compare(r.version(), current) > 0) continue; // 아직 안 나온 버전(소식을 미리 적어 둔 것)
            if (known && compare(r.version(), seen) <= 0) break;
            out.add(r);
            if (!known) break;
        }
        return out;
    }

    /** 지금 보여 줄 소식. 없으면 빈 목록(처음 설치면 지금 버전을 본 것으로 적어 둔다) */
    public static List<Release> pending(DdingMateConfig cfg) {
        String cur = currentVersion();
        if (cfg.notesSeenVersion.isEmpty() && !cfg.opened) {
            markSeen(cfg);
            return List.of();
        }
        if (!cfg.notesSeenVersion.isEmpty() && base(cfg.notesSeenVersion).equals(base(cur))) return List.of();
        if (!cfg.updateNotes) {
            markSeen(cfg);
            return List.of();
        }
        return since(cfg.notesSeenVersion, cur);
    }

    /** 접속할 때 채팅으로만 알린 소식: 다음 /띵메이트 에서 창으로 한 번 보여 준다 */
    private static List<Release> unread = List.of();

    public static void holdUnread(List<Release> notes) {
        unread = List.copyOf(notes);
    }

    /** 아직 창으로 못 본 소식(가져가면 비운다) */
    public static List<Release> takeUnread() {
        List<Release> out = unread;
        unread = List.of();
        return out;
    }

    /** 이번 버전 소식을 보여 줬다 */
    public static void markSeen(DdingMateConfig cfg) {
        cfg.notesSeenVersion = currentVersion();
        cfg.save();
    }

    /** "0.3.1 → 0.3.2-pre.27" (예전 버전을 모르면 "이전 버전 → …") */
    public static String fromTo(String seen) {
        String from = seen == null || seen.isEmpty() ? "이전 버전" : seen;
        return from + " → " + currentVersion();
    }
}
