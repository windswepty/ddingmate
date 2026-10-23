package kr.ddingmate.gui;

import kr.ddingmate.news.Notices;
import net.minecraft.client.gui.screen.Screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 받은 개발자 공지(최근 30일, 새것 먼저) */
public class NoticeScreen extends ReadingScreen {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public NoticeScreen(Screen parent) {
        super("띵메이트 공지", "개발자가 띵메이트 사용자 모두에게 보낸 공지예요 · 최근 30일", parent);
    }

    @Override
    List<Block> blocks() {
        List<Block> out = new ArrayList<>();
        List<Notices.Notice> list = Notices.received();
        if (list.isEmpty()) {
            out.add(new Block("받은 공지가 없어요. 게임에 들어가 있으면 접속할 때와 30분마다 확인해요.", 0xFFB8C0CC, false, false, 0));
        }
        for (Notices.Notice n : list) {
            out.add(new Block(n.title() + (n.at() > 0 ? "  ·  " + WHEN.format(Instant.ofEpochMilli(n.at())) : ""),
                    0xFFF2C230, true, false, out.isEmpty() ? 0 : 12));
            for (String line : n.text().split("\n")) {
                if (!line.isBlank()) out.add(new Block(line.strip(), 0xFFE2E6EE, false, false, 1));
            }
        }
        return out;
    }
}
