package com.coresplit.overlay;

import com.coresplit.CoreSplitMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Arrays;

public class OverlayKeyBinding {

    private static KeyMapping overlayKey;

    public static void register() {
        // 使用公共静态工厂方法
        KeyMapping.Category category = new KeyMapping.Category(
                Identifier.fromNamespaceAndPath("coresplit", "controls")
        );

        overlayKey = new KeyMapping(
                "key.coresplit.toggle_overlay",
                GLFW.GLFW_KEY_F6,
                category
        );

        try {
            Options options = Minecraft.getInstance().options;
            Field f = Options.class.getDeclaredField("keyMappings");
            f.setAccessible(true);
            KeyMapping[] oldKeys = (KeyMapping[]) f.get(options);
            KeyMapping[] newKeys = Arrays.copyOf(oldKeys, oldKeys.length + 1);
            newKeys[oldKeys.length] = overlayKey;
            f.set(options, newKeys);
            CoreSplitMod.LOGGER.info("[CoreSplit] Key binding registered: F6 = toggle overlay");
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Could not register keybinding in options", e);
        }
    }

    public static void handleInput() {
        if (overlayKey == null) return;
        while (overlayKey.consumeClick()) {
            CoreSplitMod.LOGGER.info("[CoreSplit] Overlay toggle pressed.");
        }
    }
}