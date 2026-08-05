package com.coresplit.overlay;

import com.coresplit.config.CoreSplitYaclConfig;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

/**
 * CoreSplit 自定义 F3 调试条目。
 *
 * <p>通过 Mixin 注入到 {@link net.minecraft.client.gui.components.debug.DebugScreenEntries#ENTRIES_BY_ID}，
 * 使 CoreSplit 性能信息以原生方式集成到 F3 调试界面（左/右列自动排版，样式与原版一致）。
 *
 * <p>可见性由 {@link net.minecraft.client.gui.components.debug.DebugScreenEntryList} 的状态管理，
 * 默认 IN_OVERLAY（F3 打开时显示），可通过 F3+S 快捷键切换（见 {@link CoreSplitF3KeyBinding}）。
 */
public class CoreSplitDebugEntry implements DebugScreenEntry {

    /** 条目标识，同时用作显示分组键 */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("coresplit", "debug");

    private static final CoreSplitDebugEntry INSTANCE = new CoreSplitDebugEntry();

    /** 单例访问，避免每次查询重复创建实例 */
    public static CoreSplitDebugEntry instance() {
        return INSTANCE;
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level serverOrClientLevel, LevelChunk clientChunk, LevelChunk serverChunk) {
        // 主开关关闭时不输出任何行
        if (!CoreSplitYaclConfig.isShowOverlay()) return;

        List<String> lines = CoreSplitOverlay.getF3Lines();
        if (!lines.isEmpty()) {
            // 以分组形式添加，确保 CoreSplit 各行作为整体排版
            displayer.addToGroup(ID, lines);
        }
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        // CoreSplit 信息为客户端本地数据，不涉及服务端敏感信息，始终允许显示
        return true;
    }
}
