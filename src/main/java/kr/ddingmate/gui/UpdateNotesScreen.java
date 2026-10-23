package kr.ddingmate.gui;

import kr.ddingmate.news.UpdateNotes;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 업데이트 소식 창: "0.3.1 → 0.3.2" 와 버전마다 새로 생긴 기능 · 바뀐 점 · 고친 문제.
 * releases 가 null 이면(알림 탭 [업데이트 소식 보기]) 지금 버전까지의 소식을 모두 보여 준다.
 */
public class UpdateNotesScreen extends ReadingScreen {

    private final List<UpdateNotes.Release> releases;

    public UpdateNotesScreen(Screen parent, List<UpdateNotes.Release> releases) {
        super("띵메이트 업데이트 소식", releases == null
                ? "지금 버전 " + UpdateNotes.currentVersion() + " · 휠로 넘겨 보세요"
                : "새 버전으로 바뀌었어요 · " + UpdateNotes.fromTo(kr.ddingmate.DdingMate.config().notesSeenVersion), parent);
        this.releases = releases != null ? releases : UpdateNotes.since("0", UpdateNotes.currentVersion());
    }

    @Override
    List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        if (releases.isEmpty()) out.add(new Block("보여 줄 소식이 없어요.", 0xFFB8C0CC, false, false, 0));
        for (UpdateNotes.Release r : releases) {
            out.add(new Block(r.version() + (r.date() == null || r.date().isEmpty() ? "" : "  ·  " + r.date()), 0xFFFFFFFF, true, false, out.isEmpty() ? 0 : 12));
            section(out, "새로 생긴 기능", 0xFF7DD379, r.added());
            section(out, "바뀐 점", 0xFF8AB4F8, r.changed());
            section(out, "고친 문제", 0xFFF2B06A, r.fixed());
        }
        return out;
    }

    private static void section(List<Block> out, String title, int color, List<String> items) {
        if (items.isEmpty()) return;
        out.add(new Block(title, color, false, false, 5));
        for (String s : items) out.add(new Block(s, 0xFFE2E6EE, false, true, 1));
    }
}
