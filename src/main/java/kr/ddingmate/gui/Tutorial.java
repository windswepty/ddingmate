package kr.ddingmate.gui;

import kr.ddingmate.ui.Theme;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 처음 /띵메이트 를 열었을 때 기능을 하나씩 소개하는 튜토리얼.
 * 단계마다 해당 탭으로 옮기고 그 탭 단추를 금색 테두리로 강조한 뒤, 아래쪽 설명 상자에 쓰는 법을 보여 준다.
 * [건너뛰기]·[이전]·[다음] 단추와 키보드(Enter·→ 다음, ← 이전, Esc 건너뛰기)로 움직인다.
 */
final class Tutorial {

    /**
     * 한 단계: 보여 줄 탭, 탭 단추 강조 여부, 강조할 설정 이름(그 줄로 스크롤해 금색 테두리, null 이면 없음), 제목, 설명
     */
    record Step(DdingMateScreen.Tab tab, boolean highlightTab, String target, String title, String body) {}

    static final List<Step> STEPS = List.of(
            new Step(DdingMateScreen.Tab.HOME, false, null, "띵메이트에 오신 걸 환영해요!",
                    "띵타이쿤을 더 편하게 해 주는 도우미예요. 기능을 하나씩 자세히 소개할게요(약 2분). 바로 쓰고 싶으면 [건너뛰기]를 누르세요. 키보드 Enter·→ 로 넘기고 ← 로 돌아가요."),
            new Step(DdingMateScreen.Tab.HOME, true, null, "홈",
                    "다음 일정, 오늘 접속 시간·받을 보상, 할 일 진행, 재료·장비 현황을 한눈에 봐요. 줄을 누르면 그 기능의 탭으로 바로 가요."),
            new Step(DdingMateScreen.Tab.NOTIFY, true, null, "알림 방식 탭",
                    "알림을 어떻게 받을지, HUD(게임 화면 구석에 늘 떠 있는 작은 정보 판)를 어떻게 보일지 정하는 곳이에요."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "일정 HUD", "일정 HUD",
                    "켜면 화면 구석에 다음 서버 일정과 남은 시간이 늘 보여요. 남은 시간 색: 빨강 = 10분 안, 노랑 = 1시간 안."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "팝업 알림", "소리 · 팝업 · 채팅 알림",
                    "알림은 세 가지로 받아요. 소리는 \"띵\", 팝업은 오른쪽 위 알림 상자, 채팅은 [띵메이트] 줄(나만 보여요). 원하는 것만 켜세요."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "소리 크기", "소리 크기",
                    "알림 소리 크기를 막대를 끌어 정해요. 0% 면 소리가 나지 않아요."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "HUD 스타일", "HUD 스타일 · 진하기",
                    "어두운 판(서버 HUD 느낌), 밝은 창, 반투명 중에서 골라요. 진하기로 배경이 얼마나 비칠지 정해요."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "HUD 전체 크기", "HUD 크기 · 서버 HUD 피하기",
                    "HUD 를 한꺼번에 줄이거나 키워요. \"서버 HUD 피하기\"를 켜 두면 서버의 재화 바·날짜 줄과 채팅창을 가리지 않게 자리를 잡아요."),
            new Step(DdingMateScreen.Tab.NOTIFY, false, "HUD 위치·크기 설정", "HUD 위치·크기 설정",
                    "게임 화면에서 HUD 를 마우스로 끌어 옮기고, 휠이나 오른쪽 아래 모서리로 HUD 마다 크기를 바꿔요. [저장]을 눌러야 남고, Esc 는 취소예요."),
            new Step(DdingMateScreen.Tab.EVENTS, true, null, "일정 탭",
                    "일일·주간 초기화, 정기 점검, 낚시 대회 같은 서버 일정 14개를 미리 알려 줘요. 줄 오른쪽에 다음까지 남은 시간이 보여요."),
            new Step(DdingMateScreen.Tab.EVENTS, false, "일일 초기화 (매일 03:00)", "일정 켜고 끄기 · 알림 시점",
                    "스위치로 일정마다 켜고 꺼요. 끄면 HUD·알림에서 빠져요. 오른쪽 칸에 몇 분 전에 알릴지 적어요(예: 10,3 = 10분 전과 3분 전, 0 = 시작할 때)."),
            new Step(DdingMateScreen.Tab.CHANNELS, false, "지금 있는 채널 자동 인식", "내 채널 자동 인식",
                    "화면 오른쪽 위 채널 표시(예: 마을30)를 읽어 지역별 내 채널을 알아서 맞추고, 그 채널이 재시작되기 전에 알려 줘요. 끄면 아래에서 직접 고를 수 있어요."),
            new Step(DdingMateScreen.Tab.KEYWORDS, false, "키워드 알림", "키워드 알림",
                    "채팅에 정해 둔 단어가 들어간 메시지가 뜨면 소리와 팝업으로 알려 줘요. 팝업 아래에 그 메시지가 그대로 보여요."),
            new Step(DdingMateScreen.Tab.KEYWORDS, false, "키워드", "키워드 적기",
                    "알고 싶은 단어를 쉼표로 구분해서 적어요(예: 오션오더, 풍선, 경매). 같은 단어는 10초에 한 번만 울려요."),
            new Step(DdingMateScreen.Tab.KEYWORDS, false, "내 닉네임 언급", "내 닉네임 언급",
                    "다른 사람이 채팅에서 내 닉네임을 부르면 알려 줘요. 내가 쓴 채팅이나 내 이름이 들어간 시스템 공지에는 울리지 않아요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "핀 1", "재료 · 레시피 핀",
                    "누르면 레시피 고르기 창이 열려요. 16개 시설의 레시피 373개를 이름·재료로 검색하거나 시설별로 거를 수 있어요. 핀은 3개까지."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "만들 개수|핀 1", "만들 개수",
                    "핀을 고르면 그 아래에 '만들 개수' 줄이 생겨요. 몇 개 만들지 정하면 필요한 재료가 그만큼 곱해져요. [−][+][+10][+64](1세트) 로 바꾸고, 한 번에 여러 개 나오는 레시피는 \"개 얻기\"로 결과물 개수를 적을 수 있어요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "가방도 합산", "가방 · 저장소 합산",
                    "인벤토리는 늘 세고, 모험 가방과 아이템 저장소는 한 번 열어 두면 기억했다가 함께 세요. 대신 열어 주지는 않아요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "원재료까지 펼치기", "원재료까지 펼치기",
                    "모자란 재료가 다른 레시피로 만드는 것이면(예: 조약돌 뭉치) 필요한 원재료(조약돌)까지 아래에 보여 줘요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "다 모이면 알림", "다 모이면 알림",
                    "핀한 레시피의 재료가 전부 모이면 \"재료 준비 완료\" 알림을 한 번 보내요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "가방 단축어", "가방 단축어",
                    "ㄱ1 처럼 가방을 여는 단축어를 쓰면 몇 번 가방인지 더 정확히 알아요. 쓰는 단축어를 쉼표로 적어 두세요."),
            new Step(DdingMateScreen.Tab.COUNTER, false, "제작 완료 타이머", "제작 완료 타이머",
                    "제작·가공 시설에서 제작을 시작하면 아이템마다 완료까지 남은 시간을 일정 HUD 에 보여 주고, 다 되면 알려 줘요. 시설 창을 한 번 열면 정확한 시간으로 맞춰요."),
            new Step(DdingMateScreen.Tab.GEAR, true, null, "장비 탭",
                    "지금 인벤토리에 있는 도구·갑옷이 목록에 나와요. 지켜볼 장비의 스위치를 켜면 남은 내구도를 HUD 에 보여 줘요."),
            new Step(DdingMateScreen.Tab.GEAR, false, "부족 알림", "내구도 부족 알림",
                    "남은 내구도가 정한 %(기본 10%) 아래로 떨어지면 한 번 알려 줘요. 수리하면 다시 알릴 준비를 해요."),
            new Step(DdingMateScreen.Tab.TODO, false, "일일 의뢰|넣을 수 있는 할 일", "오늘 할 일 · 알아서 세기",
                    "일일 의뢰(5번)·주간 의뢰(3번)·접속 보상·마인리스트 추천·무역은 서버가 보내는 메시지를 읽어 알아서 세요. 직접 체크하지 않고, 다 하면 HUD 에 체크 표시가 돼요."),
            new Step(DdingMateScreen.Tab.TODO, false, "넣을 수 있는 할 일", "할 일 넣기 · 빼기",
                    "필요 없는 할 일은 [빼기]로 빼고, 여기서 다시 넣어요. 알아서 셀 수 있는 것만 넣을 수 있어요. 초기화 시각(매일 03시·목 03시·자정)이 지나면 0 으로 돌아가요."),
            new Step(DdingMateScreen.Tab.TODO, false, "보상 구간 알림", "접속 시간 보상",
                    "접속 1·2·3·4·5·8시간 보상 구간을 넘길 때마다 채팅으로 알려 줘요. 접속할 때 서버가 알려 주는 일일 접속 시간에 알아서 맞추고, /접속시간 창을 열면 받을 보상 개수까지 보여요."),
            new Step(DdingMateScreen.Tab.LOG, true, null, "기록 탭",
                    "소리·팝업을 놓쳐도 괜찮아요. 지난 알림을 최근 100개까지 다시 볼 수 있어요."),
            new Step(DdingMateScreen.Tab.HOME, false, "튜토리얼 다시 보기", "준비 끝!",
                    "언제든 채팅에 /띵메이트 를 입력하면 이 창이 열려요. 이 안내는 홈 맨 아래 \"튜토리얼 다시 보기\"로 다시 볼 수 있어요."));

    private static final int PANEL_MAX_W = 330;
    private static final int PAD = 8;

    private final Consumer<DdingMateScreen.Tab> goTab;
    private final Function<DdingMateScreen.Tab, int[]> tabRect;
    private final Function<String, int[]> targetRect;
    private final Runnable onEnd;
    private final Widgets.Button skip;
    private final Widgets.Button prev;
    private final Widgets.Button next;
    private int index;

    Tutorial(Consumer<DdingMateScreen.Tab> goTab, Function<DdingMateScreen.Tab, int[]> tabRect,
             Function<String, int[]> targetRect, Runnable onEnd) {
        this.goTab = goTab;
        this.tabRect = tabRect;
        this.targetRect = targetRect;
        this.onEnd = onEnd;
        skip = new Widgets.Button(64, 20, Text.literal("건너뛰기"), false, b -> end());
        prev = new Widgets.Button(52, 20, Text.literal("이전"), false, b -> go(index - 1));
        next = new Widgets.Button(64, 20, Text.literal("다음"), true, b -> go(index + 1));
    }

    void start() {
        go(0);
    }

    int index() {
        return index;
    }

    /** 지금 단계에서 강조할 자리 {x, y, w, h}. 설정 줄이면 그 줄로 스크롤한다 */
    /** 마지막으로 그린 설명 상자 자리(시험용) */
    int[] panel() {
        return panel;
    }

    int[] highlight() {
        Step step = STEPS.get(index);
        if (step.target() != null) {
            // "가|나": 앞의 설정이 아직 없으면(예: 핀이 비어 있음) 뒤의 것을 강조
            for (String key : step.target().split("\\|")) {
                int[] r = targetRect.apply(key);
                if (r != null) return r;
            }
            return null;
        }
        return step.highlightTab() ? tabRect.apply(step.tab()) : null;
    }

    private void go(int i) {
        if (i < 0) return;
        if (i >= STEPS.size()) {
            end();
            return;
        }
        index = i;
        goTab.accept(STEPS.get(i).tab());
    }

    private void end() {
        onEnd.run();
    }

    /** 화면 위에 덧그린다. (sw, sh) 화면 크기, window 는 설정 창 {x, y, w, h} */
    private int[] panel;

    void render(DrawContext ctx, TextRenderer tr, int sw, int sh, int[] window, int mouseX, int mouseY, float delta) {
        Step step = STEPS.get(index);
        int[] hl = highlight();

        // 강조할 탭만 빼고 화면을 살짝 어둡게
        int dim = 0x88000000;
        if (hl == null) {
            ctx.fill(0, 0, sw, sh, dim);
        } else {
            int x0 = hl[0] - 2, y0 = hl[1] - 2, x1 = hl[0] + hl[2] + 2, y1 = hl[1] + hl[3] + 2;
            ctx.fill(0, 0, sw, y0, dim);
            ctx.fill(0, y1, sw, sh, dim);
            ctx.fill(0, y0, x0, y1, dim);
            ctx.fill(x1, y0, sw, y1, dim);
            // 깜빡이는 금색 테두리
            boolean on = (Util.getMeasuringTimeMs() / 450) % 2 == 0;
            int c = on ? 0xFFFFD25A : 0xFFE0A100;
            ctx.fill(x0 - 1, y0 - 1, x1 + 1, y0 + 1, c);
            ctx.fill(x0 - 1, y1 - 1, x1 + 1, y1 + 1, c);
            ctx.fill(x0 - 1, y0, x0 + 1, y1, c);
            ctx.fill(x1 - 1, y0, x1 + 1, y1, c);
        }

        // 설명 상자: 설정 창 아래쪽, 닫기 단추 위
        int w = Math.min(PANEL_MAX_W, window[2] - 24);
        List<OrderedText> lines = tr.wrapLines(Theme.body(step.body()), w - PAD * 2);
        int h = PAD + 12 + lines.size() * 10 + 6 + 20 + PAD;
        int x = window[0] + (window[2] - w) / 2;
        int y = window[1] + window[3] - 34 - h;
        if (hl == null) y = window[1] + (window[3] - h) / 2;
        else if (hl[1] + hl[3] + 4 > y && hl[1] - 4 < y + h) {
            // 강조한 줄을 가리지 않게 그 바로 아래, 자리가 없으면 바로 위로
            int below = hl[1] + hl[3] + 6;
            int above = hl[1] - 6 - h;
            y = below + h <= sh - 2 ? below : above >= 2 ? above : 2;
        }
        panel = new int[]{x, y, w, h};
        Theme.window(ctx, x, y, w, h);
        ctx.fill(x + 2, y + 2, x + w - 2, y + 4, Theme.BLUE);
        Theme.boldText(ctx, tr, step.title(), x + PAD, y + PAD + 1, Theme.INK, false);
        String count = (index + 1) + " / " + STEPS.size();
        Theme.text(ctx, tr, count, x + w - PAD - Theme.width(tr, count), y + PAD + 1, Theme.TEXT_SOFT);
        int ty = y + PAD + 14;
        for (OrderedText line : lines) {
            ctx.drawText(tr, line, x + PAD, ty, Theme.INK, false);
            ty += 10;
        }
        // 단추: 왼쪽 건너뛰기, 오른쪽 이전·다음
        int by = y + h - PAD - 20;
        boolean last = index == STEPS.size() - 1;
        next.setMessage(Text.literal(index == 0 ? "시작하기" : last ? "완료" : "다음"));
        skip.visible = !last;
        prev.visible = index > 0;
        skip.setX(x + PAD);
        skip.setY(by);
        next.setX(x + w - PAD - next.getWidth());
        next.setY(by);
        prev.setX(next.getX() - 4 - prev.getWidth());
        prev.setY(by);
        skip.render(ctx, mouseX, mouseY, delta);
        prev.render(ctx, mouseX, mouseY, delta);
        next.render(ctx, mouseX, mouseY, delta);
    }

    /** 설명 상자 단추만 받는다. 튜토리얼 중에는 아래 창이 눌리지 않는다 */
    void mouseClicked(double mx, double my, int button) {
        if (button != 0) return;
        for (Widgets.Button b : List.of(skip, prev, next)) {
            if (b.visible && b.isMouseOver(mx, my)) {
                b.playDownSound(net.minecraft.client.MinecraftClient.getInstance().getSoundManager());
                b.onPress();
                return;
            }
        }
    }

    /** Enter·Space·→ 다음, ←·Backspace 이전, Esc 건너뛰기 */
    void keyPressed(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_RIGHT -> go(index + 1);
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_BACKSPACE -> go(index - 1);
            case GLFW.GLFW_KEY_ESCAPE -> end();
            default -> {
            }
        }
    }
}
