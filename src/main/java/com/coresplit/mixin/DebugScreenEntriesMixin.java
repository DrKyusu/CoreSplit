package com.coresplit.mixin;

import com.coresplit.overlay.CoreSplitDebugEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让原版 {@link DebugScreenEntries#getEntry(Identifier)} 能识别 CoreSplit 自定义条目。
 *
 * <p>CoreSplit 条目不在原版 {@code ENTRIES_BY_ID} 映射中，此处通过拦截 {@code getEntry} 的查询，
 * 当请求 CoreSplit 条目 ID 时直接返回单例。这种方式比注入 {@code <clinit>} 修改静态映射更稳健：
 * 无类初始化时序顾虑，且对原版 {@code allEntries()}（用于调试选项界面）零侵入。
 *
 * <p>默认情况下 CoreSplit 条目不显示（状态为 NEVER）。用户按 F3+S（见
 * {@link com.coresplit.overlay.CoreSplitF3KeyBinding}）调用 {@code setStatus} 将条目写入状态表后，
 * {@code rebuildCurrentList} 调用 {@code getEntry} 即可取到本条目并纳入渲染。
 */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {

    @Inject(method = "getEntry", at = @At("HEAD"), cancellable = true)
    private static void coresplit$serveCustomEntry(Identifier id, CallbackInfoReturnable<DebugScreenEntry> cir) {
        if (id.equals(CoreSplitDebugEntry.ID)) {
            cir.setReturnValue(CoreSplitDebugEntry.instance());
        }
    }
}
