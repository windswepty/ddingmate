package kr.ddingmate.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import kr.ddingmate.gui.DdingMateScreen;

/** Mod Menu 의 "설정" 버튼으로 띵메이트 설정 화면을 연다. Mod Menu 가 없으면 이 클래스는 불리지 않는다. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return DdingMateScreen::new;
    }
}
