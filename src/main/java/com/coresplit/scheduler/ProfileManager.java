package com.coresplit.scheduler;

import com.coresplit.CoreSplitMod;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileManager {

    private static final String PROFILES_DIR = "coresplit_profiles";
    private static final String PROFILE_EXTENSION = ".toml";

    private final Path profilesPath;
    private final Map<String, SchedulerProfile> profiles = new ConcurrentHashMap<>(); // 修复BUG: 使用 ConcurrentHashMap 替代 HashMap，防止多线程并发读写导致 ConcurrentModificationException 或内部数据结构损坏
    private volatile String activeProfileName = null;
    private final int maxAvailableCores = Runtime.getRuntime().availableProcessors(); // 修复BUG: 集中管理系统最大可用核心数，用于配置值的范围校验

    public ProfileManager() {
        this(FabricLoader.getInstance().getConfigDir().resolve(PROFILES_DIR));
    }

    public ProfileManager(Path customPath) {
        this.profilesPath = customPath;
        try {
            Files.createDirectories(profilesPath);
        } catch (Exception e) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Failed to create profiles directory: {}", e.getMessage());
        }
        loadAllProfiles();
        CoreSplitMod.LOGGER.info("[CoreSplit] ProfileManager initialized - {} profiles loaded", profiles.size());
    }

    public SchedulerProfile createProfile(String name, int cores, int threads, float multiplier) {
        String sanitizedName = sanitizeProfileName(name);
        
        if (profiles.containsKey(sanitizedName)) {
            CoreSplitMod.LOGGER.warn("[CoreSplit] Profile '{}' already exists", sanitizedName);
            return null;
        }

        cores = validateCoreCount(cores); // 修复BUG: 创建配置前校验 CPU 核心数范围 [1, 系统最大核心数]，防止越界值
        threads = validateThreadCount(threads, cores); // 修复BUG: 创建配置前校验线程数范围 [cores, cores*4]
        multiplier = validateMultiplier(multiplier); // 修复BUG: 创建配置前校验线程倍率范围 [0.5, 4.0]

        SchedulerProfile profile = new SchedulerProfile(sanitizedName, cores, threads, multiplier);
        profiles.put(sanitizedName, profile);
        saveProfile(profile);
        
        CoreSplitMod.LOGGER.info("[CoreSplit] Profile '{}' created", sanitizedName);
        return profile;
    }

    public boolean saveProfile(SchedulerProfile profile) {
        try {
            Path filePath = profilesPath.resolve(profile.getName() + PROFILE_EXTENSION);
            StringBuilder content = new StringBuilder();
            content.append("# CoreSplit Scheduler Profile\n");
            content.append("name = \"").append(profile.getName()).append("\"\n");
            content.append("cores = ").append(profile.getCores()).append("\n");
            content.append("threads = ").append(profile.getThreads()).append("\n");
            content.append("thread_multiplier = ").append(profile.getThreadMultiplier()).append("\n");
            content.append("created_at = ").append(profile.getCreatedAt()).append("\n");
            content.append("updated_at = ").append(System.currentTimeMillis()).append("\n");
            
            Files.writeString(filePath, content.toString());
            return true;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to save profile '{}': {}", profile.getName(), e.getMessage());
            return false;
        }
    }

    public SchedulerProfile loadProfile(String name) {
        String sanitizedName = sanitizeProfileName(name);
        try {
            Path filePath = profilesPath.resolve(sanitizedName + PROFILE_EXTENSION);
            if (!Files.exists(filePath)) {
                return null;
            }

            try (FileConfig config = FileConfig.of(filePath)) {
                config.load();

                int cores = config.getOrElse("cores", 1);
                int threads = config.getOrElse("threads", 1);
                float multiplier = config.getOrElse("thread_multiplier", 1.0f);
                long createdAt = config.getOrElse("created_at", System.currentTimeMillis());

                cores = validateCoreCount(cores); // 修复BUG: 从配置文件加载后校验 CPU 核心数，防止文件中被手动篡改为非法值导致后续计算越界
                threads = validateThreadCount(threads, cores); // 修复BUG: 从配置文件加载后校验线程数范围
                multiplier = validateMultiplier(multiplier); // 修复BUG: 从配置文件加载后校验线程倍率范围

                SchedulerProfile profile = new SchedulerProfile(sanitizedName, cores, threads, multiplier);
                profile.setCreatedAt(createdAt);
                profiles.put(sanitizedName, profile);

                return profile;
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to load profile '{}': {}", sanitizedName, e.getMessage());
            return null;
        }
    }

    public boolean deleteProfile(String name) {
        String sanitizedName = sanitizeProfileName(name);
        try {
            Path filePath = profilesPath.resolve(sanitizedName + PROFILE_EXTENSION);
            if (!Files.exists(filePath)) {
                return false;
            }

            Files.delete(filePath);
            profiles.remove(sanitizedName);
            
            if (sanitizedName.equals(activeProfileName)) {
                activeProfileName = null;
            }

            CoreSplitMod.LOGGER.info("[CoreSplit] Profile '{}' deleted", sanitizedName);
            return true;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to delete profile '{}': {}", sanitizedName, e.getMessage());
            return false;
        }
    }

    public boolean activateProfile(String name) {
        SchedulerProfile profile = getProfile(name);
        if (profile == null) {
            return false;
        }
        activeProfileName = profile.getName();
        CoreSplitMod.LOGGER.info("[CoreSplit] Profile '{}' activated", activeProfileName);
        return true;
    }

    public SchedulerProfile getActiveProfile() {
        if (activeProfileName == null) {
            return null;
        }
        return profiles.get(activeProfileName);
    }

    public String getActiveProfileName() {
        return activeProfileName;
    }

    public SchedulerProfile getProfile(String name) {
        return profiles.get(sanitizeProfileName(name));
    }

    public List<SchedulerProfile> getAllProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public Set<String> getProfileNames() {
        return new HashSet<>(profiles.keySet());
    }

    public int getProfileCount() {
        return profiles.size();
    }

    private void loadAllProfiles() {
        try {
            if (!Files.exists(profilesPath)) {
                return;
            }

            // 修复BUG: Files.list 返回的 Stream 持有文件描述符，未关闭会导致资源泄漏；使用 try-with-resources 确保流被正确关闭
            try (var stream = Files.list(profilesPath)) {
                stream.filter(path -> path.toString().endsWith(PROFILE_EXTENSION))
                        .forEach(path -> {
                            String name = path.getFileName().toString();
                            name = name.substring(0, name.length() - PROFILE_EXTENSION.length());
                            loadProfile(name);
                        });
            }
        } catch (Exception e) {
            CoreSplitMod.LOGGER.error("[CoreSplit] Failed to load profiles: {}", e.getMessage());
        }
    }

    private int validateCoreCount(int cores) { // 修复BUG: 集中校验 CPU 核心数范围 [1, 系统最大核心数]，符合项目配置约束
        return Math.max(1, Math.min(maxAvailableCores, cores));
    }

    private int validateThreadCount(int threads, int cores) { // 修复BUG: 集中校验线程数范围 [cores, cores*4]，符合项目配置约束
        int minThreads = Math.max(1, cores);
        int maxThreads = cores * 4;
        return Math.max(minThreads, Math.min(maxThreads, threads));
    }

    private float validateMultiplier(float multiplier) { // 修复BUG: 集中校验线程倍率范围 [0.5, 4.0]，符合项目配置约束
        return Math.max(0.5f, Math.min(4.0f, multiplier));
    }

    private String sanitizeProfileName(String name) {
        if (name == null || name.isEmpty()) {
            return "unnamed";
        }
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        return sanitized;
    }

    public static class SchedulerProfile {
        private final String name;
        private int cores;
        private int threads;
        private float threadMultiplier;
        private long createdAt;
        private long updatedAt;

        public SchedulerProfile(String name, int cores, int threads, float threadMultiplier) {
            this.name = name;
            this.cores = Math.max(1, cores); // 修复BUG: 校验核心数最小值为 1，防止 0 或负值导致后续线程计算异常
            this.threads = Math.max(1, threads); // 修复BUG: 校验线程数最小值为 1，防止 0 或负值
            this.threadMultiplier = Math.max(0.5f, Math.min(4.0f, threadMultiplier)); // 修复BUG: 校验线程倍率范围 [0.5, 4.0]
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
        }

        public String getName() {
            return name;
        }

        public int getCores() {
            return cores;
        }

        public void setCores(int cores) {
            this.cores = Math.max(1, cores); // 修复BUG: setter 中校验核心数最小值，防止外部传入非法值
            this.updatedAt = System.currentTimeMillis();
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = Math.max(1, threads); // 修复BUG: setter 中校验线程数最小值，防止外部传入非法值
            this.updatedAt = System.currentTimeMillis();
        }

        public float getThreadMultiplier() {
            return threadMultiplier;
        }

        public void setThreadMultiplier(float threadMultiplier) {
            this.threadMultiplier = Math.max(0.5f, Math.min(4.0f, threadMultiplier)); // 修复BUG: setter 中校验线程倍率范围 [0.5, 4.0]
            this.updatedAt = System.currentTimeMillis();
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}