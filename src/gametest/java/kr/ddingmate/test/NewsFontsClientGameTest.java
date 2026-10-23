package kr.ddingmate.test;

import kr.ddingmate.DdingMate;
import kr.ddingmate.config.DdingMateConfig;
import kr.ddingmate.gui.DdingMateScreen;
import kr.ddingmate.hud.HudLayout;
import kr.ddingmate.news.Notices;
import kr.ddingmate.news.UpdateNotes;
import kr.ddingmate.notify.AlertLog;
import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.UserFonts;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.font.FontStorage;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 2026-10-24 요청 묶음:
 * 탭 정리(알림 · HUD 탭), 업데이트 소식 창, 개발자 공지, 내 글꼴 · 띵타이쿤 글꼴, 채팅창 자리를 피하는 HUD.
 */
public class NewsFontsClientGameTest implements FabricClientGameTest {

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    /** HUD 자리가 채팅창 자리와 겹치지 않는다 */
    private static void checkClear(net.minecraft.client.MinecraftClient c, HudLayout.Rect r, String name) {
        HudLayout.Rect chat = HudLayout.chatRect(c.getWindow().getScaledHeight());
        check(chat != null, "채팅창 자리를 모름");
        boolean hit = r.x() < chat.x() + chat.w() && chat.x() < r.x() + r.w() && r.y() < chat.y() + chat.h() && chat.y() < r.y() + r.h();
        check(!hit, name + " 가 채팅창 자리와 겹침: " + r + " / 채팅 " + chat);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                cfg.tutorialDone = true;
                cfg.opened = true;
            });

            // 1) 탭: "알림 방식" → "알림"(키워드 포함), 새 HUD 탭에 HUD 설정이 모두
            ctx.runOnClient(c -> {
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("알림");
                check("알림".equals(s.tabTitle()), "알림 탭이 없음: " + s.tabTitle());
                check(s.sectionLabels().containsAll(List.of("알림 방법", "키워드", "공지·소식")), "알림 탭 목차가 다름: " + s.sectionLabels());
                s.openTabNamed("HUD·꾸미기");
                check("HUD·꾸미기".equals(s.tabTitle()), "HUD·꾸미기 탭이 없음");
                check(s.sectionLabels().containsAll(List.of("켜고 끄기", "일정 HUD", "꾸미기", "크기·자리", "글꼴")), "HUD 탭 목차가 다름: " + s.sectionLabels());
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-1-hud-tab");
            ctx.runOnClient(c -> ((DdingMateScreen) c.currentScreen).openTabNamed("알림"));
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-2-notify-tab");

            // 2) 업데이트 소식: 버전 비교와 창
            ctx.runOnClient(c -> {
                List<UpdateNotes.Release> all = UpdateNotes.all();
                check(!all.isEmpty() && "0.3.2".equals(all.get(0).version()), "소식 첫 버전이 0.3.2 가 아님");
                check(UpdateNotes.since("0.3.1", "0.3.2-pre.27").size() == 1, "0.3.1 → 0.3.2 소식이 하나가 아님");
                check(UpdateNotes.since("", "0.3.2-pre.27").size() == 1, "예전 버전에서 올라온 사람에게 이번 소식만이 아님");
                check(UpdateNotes.since("0.3.2-pre.5", "0.3.2-pre.27").isEmpty(), "같은 버전 미리보기끼리 소식이 뜸");
                check(UpdateNotes.since("0.3.0", "0.3.2").size() == 2, "0.3.0 → 0.3.2 소식이 둘이 아님");
                check(UpdateNotes.since("0.3.1", "0.3.1").isEmpty(), "지금 버전보다 새 소식이 뜸");
                DdingMateConfig cfg = DdingMate.config();
                String keep = cfg.notesSeenVersion;
                cfg.notesSeenVersion = "0.3.1";
                cfg.updateNotes = true;
                check(UpdateNotes.pending(cfg).size() == 1, "0.3.1 에서 올라왔는데 소식이 없음");
                c.setScreen(new kr.ddingmate.gui.UpdateNotesScreen(null, UpdateNotes.pending(cfg)));
                UpdateNotes.markSeen(cfg);
                check(UpdateNotes.pending(cfg).isEmpty(), "본 뒤에도 소식이 남음");
                cfg.notesSeenVersion = keep;
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-3-update-notes");

            // 3) 개발자 공지: 처음 보는 공지만 알리고, 기록 탭에 남고, 다시 받아도 또 울리지 않는다
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                cfg.notices.enabled = true;
                cfg.notices.seen.clear();
                AlertLog.clear(); // 기록은 100개까지라 비우고 센다
                int before = AlertLog.entries().size();
                String json = "{\"notices\":[{\"id\":\"bbbb2222\",\"title\":\"새 기능 안내\",\"text\":\"알림 탭에서 공지를 다시 볼 수 있어요\",\"at\":1761300000000},"
                        + "{\"id\":\"aaaa1111\",\"title\":\"점검 안내\",\"text\":\"오늘 밤 띵메이트 업데이트가 있어요\\n미리 받아 두세요\",\"at\":1761200000000}],\"pollMin\":45}";
                Notices.testSource = () -> json;
                Notices.debugFetch(c);
                check(Notices.received().size() == 2, "공지를 두 개 받지 않음");
                check(cfg.notices.seen.containsAll(List.of("aaaa1111", "bbbb2222")), "본 공지로 적지 않음");
                check(AlertLog.entries().size() == before + 2, "공지가 기록 탭에 남지 않음");
                check(Notices.debugPollMs() == 45 * 60_000L, "중계가 준 간격을 쓰지 않음");
                Notices.debugFetch(c);
                check(AlertLog.entries().size() == before + 2, "같은 공지가 다시 울림");
                cfg.notices.enabled = false;
                Notices.testSource = () -> "{\"notices\":[{\"id\":\"cccc3333\",\"title\":\"끈 사람\",\"text\":\"안 와야 함\",\"at\":1}]}";
                Notices.debugFetch(c);
                check(!cfg.notices.seen.contains("cccc3333"), "공지 받기를 껐는데 받아 옴");
                cfg.notices.enabled = true;
                Notices.testSource = () -> json;
                Notices.debugFetch(c);
                Notices.testSource = null;
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("news-4-notice-chat");
            ctx.runOnClient(c -> c.setScreen(new kr.ddingmate.gui.NoticeScreen(null)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-5-notice-screen");

            // 3-2) 업데이트 공지: 지금보다 새 버전이면 "새 버전" 알림·창, 같거나 예전 버전이면 조용히
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                AlertLog.clear();
                Notices.testSource = () -> "{\"notices\":[{\"id\":\"dddd4444\",\"title\":\"띵메이트 0.1.0 출시\",\"text\":\"옛날\",\"at\":1,\"kind\":\"update\",\"version\":\"0.1.0\",\"url\":\"https://example.com/old\",\"added\":[],\"changed\":[],\"removed\":[]}]}";
                Notices.debugFetch(c);
                check(Notices.newer() == null && AlertLog.entries().isEmpty(), "예전 버전 업데이트 공지가 뜸");
                check(cfg.notices.seen.contains("dddd4444"), "예전 버전 업데이트 공지를 본 것으로 적지 않음");
                Notices.testSource = () -> "{\"notices\":[{\"id\":\"eeee5555\",\"title\":\"띵메이트 9.9.9 출시\",\"text\":\"추가\n- 업데이트 공지\",\"at\":1761400000000,"
                        + "\"kind\":\"update\",\"version\":\"9.9.9\",\"url\":\"https://example.com/ddingmate\",\"added\":[\"업데이트 공지\",\"새 HUD 꾸미기\"],"
                        + "\"changed\":[\"글꼴 크기 맞춤\"],\"removed\":[\"예전 키워드 탭\"]},"
                        + "{\"id\":\"ffff6666\",\"title\":\"띵메이트 9.0.0 출시\",\"text\":\"x\",\"at\":1,\"kind\":\"update\",\"version\":\"9.0.0\",\"url\":\"javascript:alert(1)\"}]}";
                Notices.debugFetch(c);
                var u = Notices.newer();
                check(u != null && u.version().equals("9.9.9"), "가장 새 버전이 아님: " + u);
                check(u.url().equals("https://example.com/ddingmate"), "다운로드 주소가 다름");
                check(u.release().added().size() == 2 && u.release().removed().equals(List.of("예전 키워드 탭")), "추가·삭제 목록이 다름");
                check(AlertLog.entries().size() == 1, "새 버전을 한 번 알리지 않음: " + AlertLog.entries().size());
                Notices.debugFetch(c);
                check(AlertLog.entries().size() == 1, "같은 접속에서 새 버전을 또 알림");
                check(Notices.takeOpenNewVersion(), "[바뀐 점 보기]로 새 버전 창을 열 준비가 안 됨");
                Notices.testSource = null;
                c.setScreen(kr.ddingmate.gui.UpdateNotesScreen.newVersion(null, u));
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-5b-new-version");
            ctx.runOnClient(c -> {
                c.setScreen(null);
                Notices.debugReset(); // 뒤 시험(보여 주기 촬영)의 홈 화면에 '새 버전'이 남지 않게
            });

            // 4) 띵타이쿤 글꼴 = 게임 기본 글꼴(minecraft:default)
            ctx.runOnClient(c -> {
                c.setScreen(null);
                DdingMateConfig cfg = DdingMate.config();
                UserFonts.choose(cfg, UserFonts.GAME);
                check(Identifier.ofVanilla("default").equals(Theme.bodyFont()), "띵타이쿤 글꼴이 게임 기본 글꼴이 아님: " + Theme.bodyFont());
                UserFonts.choose(cfg, DdingMateConfig.FontStyle.GALMURI.name());
                check(cfg.customFont.isEmpty() && cfg.font == DdingMateConfig.FontStyle.GALMURI, "띵메이트 글꼴로 돌아오지 않음");
            });

            // 5) 내 글꼴: 폴더에 .ttf 를 넣고 다시 읽으면 목록에 생기고, 고르면 그 글꼴로(없는 글꼴 자리가 아님)
            Path[] added = new Path[1];
            ctx.runOnClient(c -> {
                try (InputStream in = DdingMate.class.getResourceAsStream("/assets/ddingmate/font/griun-mongtori.ttf")) {
                    added[0] = UserFonts.folder().resolve("시험 손글씨.ttf");
                    Files.copy(in, added[0], java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(UserFonts.folder().resolve("글꼴 아님.txt"), "x");
                } catch (Exception e) {
                    throw new AssertionError("시험 글꼴을 넣지 못함", e);
                }
                check(UserFonts.check(UserFonts.folder().resolve("글꼴 아님.txt")) != null, "글꼴이 아닌 파일을 받아들임");
                c.reloadResources();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 1200);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                List<UserFonts.Entry> list = UserFonts.entries();
                check(list.size() == 1 && list.get(0).name().equals("시험 손글씨"), "내 글꼴 목록이 다름: " + list);
                DdingMateConfig cfg = DdingMate.config();
                UserFonts.choose(cfg, list.get(0).key());
                Identifier id = Theme.bodyFont();
                check(id.getPath().startsWith("user/") && id.getPath().endsWith("_body"), "내 글꼴을 골랐는데 글꼴 이름이 다름: " + id);
                var tr = (kr.ddingmate.mixin.TextRendererAccessor) c.textRenderer;
                FontStorage mine = tr.ddingmate$storage(id);
                FontStorage missing = tr.ddingmate$storage(Identifier.of(DdingMate.MOD_ID, "no_such_font"));
                check(mine != missing, "내 글꼴이 게임에 읽히지 않음(없는 글꼴 자리)");
                check(UserFonts.label(list.get(0).key()).equals("내 글꼴 · 시험 손글씨"), "내 글꼴 이름표가 다름");
                c.setScreen(new kr.ddingmate.gui.FontLookScreen(null));
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-6-user-font-look");
            ctx.runOnClient(c -> {
                DdingMateScreen s = new DdingMateScreen(null);
                c.setScreen(s);
                s.openTabNamed("HUD·꾸미기");
            });
            ctx.waitTicks(5);
            ctx.takeScreenshot("news-7-user-font-hud-tab");
            // 지우고 다시 읽으면 목록에서 빠지고, 골라 둔 글꼴은 띵메이트 글꼴로 그려진다
            ctx.runOnClient(c -> {
                c.setScreen(null);
                try {
                    Files.deleteIfExists(added[0]);
                    Files.deleteIfExists(UserFonts.folder().resolve("글꼴 아님.txt"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                c.reloadResources();
            });
            ctx.waitFor(c -> c.getOverlay() == null, 1200);
            ctx.waitTicks(5);
            ctx.runOnClient(c -> {
                check(UserFonts.entries().isEmpty(), "지운 글꼴이 남음");
                DdingMateConfig cfg = DdingMate.config();
                check(!Theme.bodyFont().getPath().startsWith("user/"), "지운 내 글꼴로 그리려 함");
                cfg.customFont = "";
                cfg.save();
            });

            // 6) 채팅창 자리: 사용자 설정 그대로(재료 카운터를 왼쪽에 옮기고 높이 210) → 채팅창 위에서 끝나야
            ctx.runOnClient(c -> {
                DdingMateConfig cfg = DdingMate.config();
                cfg.hudPos.clear();
                cfg.hudSizes.clear();
                cfg.hudHeights.clear();
                cfg.hudWidths.clear();
                cfg.avoidServerHud = true;
                cfg.hudEnabled = true;
                cfg.counter.enabled = true;
                cfg.counter.pins.get(0).recipe = "강화 제작 시설/하급 라이프스톤";
                cfg.counter.pins.get(0).crafts = 60;
                cfg.counter.pins.get(1).recipe = "";
                cfg.counter.pins.get(2).recipe = "";
                cfg.counter.expandSubMaterials = true;
                DdingMateConfig.HudPos p = new DdingMateConfig.HudPos();
                p.x = 0.0168f;
                p.y = 0.274f;
                cfg.hudPos.put(HudLayout.COUNTER, p);
                cfg.hudSizes.put(HudLayout.COUNTER, 50);
                cfg.hudHeights.put(HudLayout.COUNTER, 210);
                cfg.hudEnabled = false; // 시험 화면이 작아(240 높이) 하나씩 본다
                for (int i = 0; i < 12; i++) c.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("채팅 " + i + " · 올라오는 채팅에 HUD 가 잘리면 안 돼요"));
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("news-8-chat-avoid");
            ctx.runOnClient(c -> {
                HudLayout.Rect counter = HudLayout.bounds().get(HudLayout.COUNTER);
                check(counter != null, "재료 카운터를 그리지 않음: " + HudLayout.bounds());
                checkClear(c, counter, "재료 카운터");
                // 일정 HUD 를 왼쪽 맨 아래(채팅창 자리)로 옮겨도 채팅창 위로 비킨다
                DdingMateConfig cfg = DdingMate.config();
                cfg.counter.enabled = false;
                cfg.hudEnabled = true;
                DdingMateConfig.HudPos s = new DdingMateConfig.HudPos();
                s.x = 0f;
                s.y = 1f;
                cfg.hudPos.put(HudLayout.SCHEDULE, s);
            });
            ctx.waitTicks(10);
            ctx.takeScreenshot("news-9-chat-avoid-schedule");
            ctx.runOnClient(c -> {
                HudLayout.Rect sched = HudLayout.bounds().get(HudLayout.SCHEDULE);
                check(sched != null, "일정 HUD 를 그리지 않음: " + HudLayout.bounds());
                checkClear(c, sched, "일정 HUD");
                DdingMateConfig cfg = DdingMate.config();
                cfg.hudPos.clear();
                cfg.hudSizes.clear();
                cfg.hudHeights.clear();
                cfg.counter.pins.get(0).recipe = "";
                cfg.save();
            });
        }
    }
}
