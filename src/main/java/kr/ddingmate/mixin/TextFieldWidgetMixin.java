package kr.ddingmate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.ddingmate.ui.Widgets;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 띵메이트 입력칸(밝은 바탕)에서만 글자 그림자를 끈다. 그림자가 글자 획과 어긋나 두 겹으로 번져 보이기 때문.
 * 한글 조합 중인 글자(caramelChat 등 입력 모드가 덧그리는 부분)까지 같은 그리기 단계에서 처리돼서 함께 깔끔해진다.
 * 다른 입력칸(채팅창 등)은 원래대로 그린다.
 */
@Mixin(TextFieldWidget.class)
abstract class TextFieldWidgetMixin {

    @WrapOperation(method = "renderWidget", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"))
    private int ddingmate$ordered(DrawContext ctx, TextRenderer tr, OrderedText text, int x, int y, int color, Operation<Integer> op) {
        if ((Object) this instanceof Widgets.Field) return ctx.drawText(tr, text, x, y, color, false);
        return op.call(ctx, tr, text, x, y, color);
    }

    @WrapOperation(method = "renderWidget", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"))
    private int ddingmate$text(DrawContext ctx, TextRenderer tr, Text text, int x, int y, int color, Operation<Integer> op) {
        if ((Object) this instanceof Widgets.Field) return ctx.drawText(tr, text, x, y, color, false);
        return op.call(ctx, tr, text, x, y, color);
    }

    @WrapOperation(method = "renderWidget", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I"))
    private int ddingmate$string(DrawContext ctx, TextRenderer tr, String text, int x, int y, int color, Operation<Integer> op) {
        if ((Object) this instanceof Widgets.Field) return ctx.drawText(tr, text, x, y, color, false);
        return op.call(ctx, tr, text, x, y, color);
    }
}
