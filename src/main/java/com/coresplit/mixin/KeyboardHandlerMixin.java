package com.coresplit.mixin;

import com.coresplit.overlay.CoreSplitF3KeyBinding;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在原版 F3 组合键处理流程中拦截 CoreSplit 的 F3+S 快捷键。
 *
 * <p>{@code handleDebugKeys} 仅在 F3 修饰键按下时被调用，因此此处检测天然具备「F3 按住」前提。
 * 命中时切换 CoreSplit F3 页面并消费事件（返回 true），避免 S 键继续触发后腿移动。
 * 未命中时放行到原版逻辑，不影响其它 F3+快捷键。
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
    private void coresplit$handleF3Shortcut(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        KeyMapping key = CoreSplitF3KeyBinding.getKeyMapping();
        // 修复BUG: key 可能为 null（注册失败或客户端初始化未完成时），直接访问 matches 会 NPE
        if (key == null) return;
        if (key.matches(event)) {
            CoreSplitF3KeyBinding.toggle();
            cir.setReturnValue(true);
        }
    }
}
