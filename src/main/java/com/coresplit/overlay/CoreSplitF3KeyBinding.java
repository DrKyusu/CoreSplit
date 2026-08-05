package com.coresplit.overlay;

import com.coresplit.CoreSplitMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * F3+S 快捷键：切换 CoreSplit F3 调试页面的显示。
 *
 * <p>按键默认为 S（即 F3+S），归类到原版 "Debug" 类别，可在「选项 → 控制」中自定义。
 *
 * <p>注册方式沿用 {@link OverlayKeyBinding} 的反射模式（向 {@link Options#keyMappings} 追加），
 * 与本模组现有按键注册保持一致。
 *
 * <p>切换逻辑通过 {@link com.coresplit.mixin.KeyboardHandlerMixin} 注入到原版
 * {@code KeyboardHandler.handleDebugKeys}，仅在 F3 修饰键按下时触发，
 * 因此不会与移动键（S 后退）冲突——F3 未按下时 S 正常用于移动。
 *
 * <p>可见性状态由原版 {@link net.minecraft.client.gui.components.debug.DebugScreenEntryList}
 * 管理（IN_OVERLAY / NEVER），切换会自动持久化到 debug-profile.json，
 * 与原版 F3 调试条目的切换行为完全一致。
 */
public class CoreSplitF3KeyBinding {

    private static volatile KeyMapping f3Key;

    public static void register() {
        try {
            KeyMapping.Category category = new KeyMapping.Category(
                    Identifier.fromNamespaceAndPath("minecraft", "debug")
            );

            f3Key = new KeyMapping(
                    "key.coresplit.f3_debug",
                    GLFW.GLFW_KEY_S,
                    category
            );

            Options options = Minecraft.getInstance().options;
            // 修复BUG: options 可能为 null（客户端初始化未完成时），反射访问字段会 NPE
            if (options == null) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Minecraft options not available, skipping F3 keybinding registration");
                return;
            }
            Field f = Options.class.getDeclaredField("keyMappings");
            f.setAccessible(true);
            KeyMapping[] oldKeys = (KeyMapping[]) f.get(options);
            // 修复BUG: oldKeys 可能为 null，Arrays.copyOf 会抛 NPE
            if (oldKeys == null) {
                CoreSplitMod.LOGGER.warn("[CoreSplit] Existing key mappings array is null, skipping F3 keybinding registration");
                return;
            }
            KeyMapping[] newKeys = Arrays.copyOf(oldKeys, oldKeys.length + 1);
            newKeys[oldKeys.length] = f3Key;
            f.set(options, newKeys);
            CoreSplitMod.LOGGER.info("[CoreSplit] F3 debug keybinding registered (default: F3+S, customizable in controls)");
        } catch (Throwable t) {
            // 修复BUG: KeyMapping/Options 为客户端专属，服务端或客户端未完全初始化时可能抛出异常，包裹后避免崩溃
            CoreSplitMod.LOGGER.warn("[CoreSplit] Could not register F3 debug keybinding", t);
        }
    }

    public static KeyMapping getKeyMapping() {
        return f3Key;
    }

    /**
     * 切换 CoreSplit F3 页面显示状态。
     *
     * <p>当前可见（IN_OVERLAY / ALWAYS_ON）→ 隐藏（NEVER）；
     * 当前隐藏（NEVER）→ 显示（F3 打开时 IN_OVERLAY，否则 ALWAYS_ON）。
     */
    public static void toggle() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.debugEntries == null) return;

            DebugScreenEntryStatus current = mc.debugEntries.getStatus(CoreSplitDebugEntry.ID);
            DebugScreenEntryStatus next;
            if (current == DebugScreenEntryStatus.NEVER) {
                next = mc.debugEntries.isOverlayVisible()
                        ? DebugScreenEntryStatus.IN_OVERLAY
                        : DebugScreenEntryStatus.ALWAYS_ON;
            } else {
                next = DebugScreenEntryStatus.NEVER;
            }

            // setStatus 会自动 rebuildCurrentList() 并持久化到 debug-profile.json
            mc.debugEntries.setStatus(CoreSplitDebugEntry.ID, next);

            boolean enabled = next != DebugScreenEntryStatus.NEVER;
            mc.showDebugChat(Component.translatable(
                    enabled ? "coresplit.f3.enabled" : "coresplit.f3.disabled"));
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] F3 toggle failed", e);
        }
    }
}
