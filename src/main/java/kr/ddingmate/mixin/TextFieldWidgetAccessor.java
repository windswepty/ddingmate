package kr.ddingmate.mixin;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 여러 줄 입력칸(kr.ddingmate.ui.TextArea)이 고른 글자 범위를 칠하려고 읽기만 한다 */
@Mixin(TextFieldWidget.class)
public interface TextFieldWidgetAccessor {
    @Accessor("selectionEnd")
    int ddingmate$selectionEnd();
}
