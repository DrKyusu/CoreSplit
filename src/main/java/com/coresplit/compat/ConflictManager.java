package com.coresplit.compat;

import com.coresplit.CoreSplitMod;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConflictManager {

    public enum ConflictType {
        FATAL("致命冲突", "可能导致游戏崩溃"),
        PERFORMANCE("性能冲突", "可能导致性能下降"),
        FEATURE("功能冲突", "功能可能失效或行为异常"),
        COMPATIBILITY("兼容性冲突", "功能可能部分可用");

        public final String displayName;
        public final String description;

        ConflictType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
    }

    public enum ResolutionStrategy {
        DISABLE_OTHER("禁用冲突模组", "禁用与CoreSplit冲突的其他模组"),
        DISABLE_SELF("降级运行", "CoreSplit禁用相关功能模块"),
        COMPATIBILITY_MODE("兼容模式", "启用CoreSplit的兼容模式"),
        MANUAL("手动处理", "由用户手动决定如何处理");

        public final String displayName;
        public final String description;

        ResolutionStrategy(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
    }

    public static class ConflictEntry {
        public final String modId;
        public final String modName;
        public final String modVersion;
        public final ConflictType type;
        public final String affectedModule;
        public final String description;
        public final ResolutionStrategy recommendedStrategy;
        public final List<String> solutions;
        public volatile boolean resolved = false;

        public ConflictEntry(String modId, String modName, String modVersion, ConflictType type,
                             String affectedModule, String description, 
                             ResolutionStrategy recommendedStrategy, List<String> solutions) {
            this.modId = modId;
            this.modName = modName;
            this.modVersion = modVersion;
            this.type = type;
            this.affectedModule = affectedModule;
            this.description = description;
            this.recommendedStrategy = recommendedStrategy;
            this.solutions = solutions != null ? solutions : Collections.emptyList();
        }

        public void markResolved() {
            this.resolved = true;
        }
    }

    private static class KnownConflict {
        final ConflictType type;
        final String affectedModule;
        final String description;
        final ResolutionStrategy strategy;
        final List<String> solutions;

        KnownConflict(ConflictType type, String affectedModule, String description, 
                      ResolutionStrategy strategy, String... solutions) {
            this.type = type;
            this.affectedModule = affectedModule;
            this.description = description;
            this.strategy = strategy;
            this.solutions = Arrays.asList(solutions);
        }
    }

    private static final Map<String, KnownConflict> KNOWN_CONFLICTS = new LinkedHashMap<>();

    static {
        KNOWN_CONFLICTS.put("c2me", new KnownConflict(
                ConflictType.PERFORMANCE,
                "chunk_engine",
                "C2ME和CoreSplit的区块引擎都实现了并行区块加载，可能导致资源争用",
                ResolutionStrategy.DISABLE_SELF,
                "禁用CoreSplit的Chunk Engine模块",
                "在配置中设置兼容性模式"
        ));

        KNOWN_CONFLICTS.put("async", new KnownConflict(
                ConflictType.FEATURE,
                "task_scheduler",
                "Async Entity Tick与CoreSplit的任务调度器可能冲突",
                ResolutionStrategy.COMPATIBILITY_MODE,
                "启用CoreSplit的兼容性模式",
                "禁用其中一个任务调度系统"
        ));

        KNOWN_CONFLICTS.put("lithium", new KnownConflict(
                ConflictType.PERFORMANCE,
                "performance_governor",
                "Lithium的实体AI优化与CoreSplit的性能调控器可能产生冲突",
                ResolutionStrategy.COMPATIBILITY_MODE,
                "启用CoreSplit的兼容性模式",
                "降低性能调控器的灵敏度"
        ));

        KNOWN_CONFLICTS.put("optifine", new KnownConflict(
                ConflictType.FATAL,
                "texture_system",
                "OptiFine与CoreSplit的纹理系统存在严重兼容性问题",
                ResolutionStrategy.MANUAL,
                "使用OptiFine时禁用CoreSplit的纹理和模型系统",
                "考虑使用Fabric渲染优化模组替代OptiFine"
        ));

        KNOWN_CONFLICTS.put("sodium", new KnownConflict(
                ConflictType.COMPATIBILITY,
                "overlay",
                "Sodium的渲染优化可能影响CoreSplit的性能监控叠加层",
                ResolutionStrategy.COMPATIBILITY_MODE,
                "启用兼容性模式",
                "调整叠加层的更新频率"
        ));

        KNOWN_CONFLICTS.put("iris", new KnownConflict(
                ConflictType.COMPATIBILITY,
                "texture_system",
                "Iris着色器可能与CoreSplit的PBR纹理系统存在兼容性问题",
                ResolutionStrategy.COMPATIBILITY_MODE,
                "在使用Iris时禁用PBR功能",
                "检查着色器包是否支持CoreSplit"
        ));
    }

    private final List<ConflictEntry> detectedConflicts = new ArrayList<>();
    private final Map<String, ConflictEntry> conflictMap = new ConcurrentHashMap<>();
    private volatile boolean detectionComplete = false;

    private static ConflictManager instance;

    public static synchronized ConflictManager getInstance() {
        if (instance == null) {
            instance = new ConflictManager();
        }
        return instance;
    }

    private ConflictManager() {}

    public void detectConflicts() {
        if (detectionComplete) return;

        FabricLoader loader = FabricLoader.getInstance();
        
        for (Map.Entry<String, KnownConflict> entry : KNOWN_CONFLICTS.entrySet()) {
            String modId = entry.getKey();
            KnownConflict known = entry.getValue();
            
            if (loader.isModLoaded(modId)) {
                Optional<ModContainer> modContainer = loader.getModContainer(modId);
                String modName = modId;
                String modVersion = "?";
                
                if (modContainer.isPresent()) {
                    ModMetadata metadata = modContainer.get().getMetadata();
                    modName = metadata.getName();
                    modVersion = metadata.getVersion().getFriendlyString();
                }
                
                ConflictEntry conflict = new ConflictEntry(
                        modId,
                        modName,
                        modVersion,
                        known.type,
                        known.affectedModule,
                        known.description,
                        known.strategy,
                        known.solutions
                );
                
                detectedConflicts.add(conflict);
                conflictMap.put(modId, conflict);
                
                CoreSplitMod.LOGGER.warn("[CoreSplit] 检测到冲突: {} ({}) - {}", 
                        modName, modVersion, known.description);
            }
        }

        detectUnknownConflicts(loader);
        
        detectionComplete = true;
        
        if (!detectedConflicts.isEmpty()) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] 共检测到 {} 个冲突", detectedConflicts.size());
        } else {
            CoreSplitMod.LOGGER.info("[CoreSplit] 未检测到已知冲突");
        }
    }

    private void detectUnknownConflicts(FabricLoader loader) {
        Set<String> renderMods = Set.of("sodium", "lithium", "phosphor", "starlight", "optifine", "iris");
        Set<String> chunkMods = Set.of("c2me", "ferritecore", "memoryleakfix");
        Set<String> entityMods = Set.of("async", "c3h6n6o6", "aiimprovements");

        int renderModCount = 0;
        int chunkModCount = 0;
        int entityModCount = 0;

        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (renderMods.contains(id)) renderModCount++;
            if (chunkMods.contains(id)) chunkModCount++;
            if (entityMods.contains(id)) entityModCount++;
        }

        if (renderModCount >= 2) {
            ConflictEntry conflict = new ConflictEntry(
                    "multiple_render_mods",
                    "多个渲染优化模组",
                    "N/A",
                    ConflictType.PERFORMANCE,
                    "rendering",
                    "同时安装了多个渲染优化模组，可能导致性能下降或不稳定",
                    ResolutionStrategy.MANUAL,
                    List.of("只保留一个渲染优化模组", "检查模组间的兼容性")
            );
            detectedConflicts.add(conflict);
            CoreSplitMod.LOGGER.warn("[CoreSplit] 检测到潜在冲突: 多个渲染优化模组同时运行");
        }

        if (chunkModCount >= 2) {
            ConflictEntry conflict = new ConflictEntry(
                    "multiple_chunk_mods",
                    "多个区块优化模组",
                    "N/A",
                    ConflictType.PERFORMANCE,
                    "chunk_engine",
                    "同时安装了多个区块优化模组，可能导致区块加载问题",
                    ResolutionStrategy.DISABLE_SELF,
                    List.of("禁用CoreSplit的Chunk Engine", "只保留一个区块优化模组")
            );
            detectedConflicts.add(conflict);
            CoreSplitMod.LOGGER.warn("[CoreSplit] 检测到潜在冲突: 多个区块优化模组同时运行");
        }
    }

    public boolean hasConflicts() {
        return !detectedConflicts.isEmpty();
    }

    public boolean hasFatalConflicts() {
        return detectedConflicts.stream().anyMatch(c -> c.type == ConflictType.FATAL);
    }

    public List<ConflictEntry> getConflicts() {
        return Collections.unmodifiableList(detectedConflicts);
    }

    public List<ConflictEntry> getConflictsByType(ConflictType type) {
        return detectedConflicts.stream()
                .filter(c -> c.type == type)
                .toList();
    }

    public List<ConflictEntry> getUnresolvedConflicts() {
        return detectedConflicts.stream()
                .filter(c -> !c.resolved)
                .toList();
    }

    public ConflictEntry getConflict(String modId) {
        return conflictMap.get(modId);
    }

    public boolean resolveConflict(String modId, ResolutionStrategy strategy) {
        ConflictEntry conflict = conflictMap.get(modId);
        if (conflict == null) return false;

        CoreSplitMod.LOGGER.info("[CoreSplit] 尝试解决冲突: {} -> {}", modId, strategy.displayName);

        switch (strategy) {
            case DISABLE_OTHER -> {
                CoreSplitMod.LOGGER.warn("[CoreSplit] 需要用户手动禁用模组: {}", modId);
            }
            case DISABLE_SELF -> {
                if (conflict.affectedModule != null) {
                    try {
                        Class<?> apiClass = Class.forName("com.coresplit.api.CoreSplitAPI");
                        java.lang.reflect.Method method = apiClass.getMethod("disableModule", String.class);
                        method.invoke(null, conflict.affectedModule);
                        CoreSplitMod.LOGGER.info("[CoreSplit] 已禁用受影响模块: {}", conflict.affectedModule);
                    } catch (Exception e) {
                        CoreSplitMod.LOGGER.error("[CoreSplit] 禁用模块失败", e);
                    }
                }
            }
            case COMPATIBILITY_MODE -> {
                try {
                    Class<?> apiClass = Class.forName("com.coresplit.api.CoreSplitAPI");
                    java.lang.reflect.Method method = apiClass.getMethod("applyPreset", String.class);
                    method.invoke(null, "minimal");
                    CoreSplitMod.LOGGER.info("[CoreSplit] 已切换到最小化预设以兼容其他模组");
                } catch (Exception e) {
                    CoreSplitMod.LOGGER.error("[CoreSplit] 切换预设失败", e);
                }
            }
            case MANUAL -> {
                CoreSplitMod.LOGGER.info("[CoreSplit] 等待用户手动处理冲突");
            }
        }

        conflict.markResolved();
        return true;
    }

    public void autoResolveConflicts() {
        for (ConflictEntry conflict : detectedConflicts) {
            if (!conflict.resolved) {
                if (conflict.type == ConflictType.FATAL) {
                    CoreSplitMod.LOGGER.error("[CoreSplit] 致命冲突无法自动解决: {}", conflict.modName);
                } else {
                    resolveConflict(conflict.modId, conflict.recommendedStrategy);
                }
            }
        }
    }

    public String generateConflictReport() {
        if (detectedConflicts.isEmpty()) {
            return "=== CoreSplit 冲突检测报告 ===\n未检测到任何冲突。\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== CoreSplit 冲突检测报告 ===\n");
        sb.append("检测到 ").append(detectedConflicts.size()).append(" 个冲突\n\n");

        for (ConflictEntry conflict : detectedConflicts) {
            sb.append("--- ").append(conflict.modName).append(" (").append(conflict.modId).append(" v").append(conflict.modVersion).append(") ---\n");
            sb.append("类型: ").append(conflict.type.displayName).append("\n");
            sb.append("影响模块: ").append(conflict.affectedModule).append("\n");
            sb.append("描述: ").append(conflict.description).append("\n");
            sb.append("推荐策略: ").append(conflict.recommendedStrategy.displayName).append("\n");
            sb.append("解决方案:\n");
            for (int i = 0; i < conflict.solutions.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(conflict.solutions.get(i)).append("\n");
            }
            sb.append("状态: ").append(conflict.resolved ? "已解决" : "未解决").append("\n\n");
        }

        return sb.toString();
    }

    public void dumpReport() {
        CoreSplitMod.LOGGER.info(generateConflictReport());
    }

    public boolean isDetectionComplete() {
        return detectionComplete;
    }

    public int getConflictCount() {
        return detectedConflicts.size();
    }

    public int getConflictCountByType(ConflictType type) {
        return (int) detectedConflicts.stream().filter(c -> c.type == type).count();
    }
}