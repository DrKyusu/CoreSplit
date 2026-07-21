package com.coresplit.config;

import com.coresplit.CoreSplitMod;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class CoreSplitYaclConfig {

    // Client-side settings stored in memory (separate from server TOML config)
    private static ClientOptimizationMode optimizationMode = ClientOptimizationMode.AUTO;
    private static boolean showOverlay = true;
    private static float fontScale = 1.0f;

    public static void load() {
        // Client config is initialized with defaults; YACL screen allows editing
        CoreSplitMod.LOGGER.info("[CoreSplit] Client YACL config initialized.");
    }

    // Getters for overlay and other client systems
    public static ClientOptimizationMode getOptimizationMode() { return optimizationMode; }
    public static boolean isShowOverlay()                       { return showOverlay; }
    public static float getFontScale()                          { return fontScale; }

    public static Screen makeScreen(Screen parent) {
        try {
            var builder = YetAnotherConfigLib.createBuilder()
                    .title(Component.literal("CoreSplit Settings"));

            // ── Engine category ──
            var engineCategory = ConfigCategory.createBuilder()
                    .name(Component.literal("Engine"))
                    .option(Option.<ClientOptimizationMode>createBuilder()
                            .name(Component.literal("Optimization Mode"))
                            .description(OptionDescription.of(Component.literal(
                                    "Select the optimization engine strategy")))
                            .binding(ClientOptimizationMode.AUTO,
                                    CoreSplitYaclConfig::getOptimizationMode,
                                    v -> optimizationMode = v)
                            .controller(opt -> EnumControllerBuilder.create(opt)
                                    .enumClass(ClientOptimizationMode.class)
                                    .valueFormatter(m -> Component.literal(m.getLabel())))
                            .build())
                    .option(Option.<Boolean>createBuilder()
                            .name(Component.literal("Compatibility Mode"))
                            .description(OptionDescription.of(Component.literal(
                                    "Enable compatibility mode for conflicting mods")))
                            .binding(false,
                                    () -> CoreSplitMod.getConfig() != null
                                            && CoreSplitMod.getConfig().isCompatibilityMode(),
                                    v -> {})
                            .controller(BooleanControllerBuilder::create)
                            .build())
                    .build();

            // ── Overlay category ──
            var overlayCategory = ConfigCategory.createBuilder()
                    .name(Component.literal("F3 Overlay"))
                    .option(Option.<Boolean>createBuilder()
                            .name(Component.literal("Show Overlay"))
                            .binding(true,
                                    CoreSplitYaclConfig::isShowOverlay,
                                    v -> showOverlay = v)
                            .controller(BooleanControllerBuilder::create)
                            .build())
                    .build();

            // ── Compatibility info ──
            var compatCategory = ConfigCategory.createBuilder()
                    .name(Component.literal("Compatibility"))
                    .option(LabelOption.create(Component.literal(
                            "Detected mod compatibility info is logged at startup.")))
                    .build();

            builder.category(engineCategory);
            builder.category(overlayCategory);
            builder.category(compatCategory);

            return builder.build().generateScreen(parent);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to build YACL screen", e);
            return parent;
        }
    }
}