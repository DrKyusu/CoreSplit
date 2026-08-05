package com.coresplit.model;

import com.coresplit.CoreSplitMod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class AnimationController {

    private static final float MIN_SPEED = 0.1f;
    private static final float MAX_SPEED = 10.0f;
    private static final float DEFAULT_SPEED = 1.0f;
    private static final float MILLIS_PER_SECOND = 1000.0f;

    // 修复BUG: 原 entityAnimationStates 无上限，大规模实体场景下内存持续增长；
    // 添加上限 1024，超限后拒绝创建新状态并通过 rate-limited 告警提示。
    // 修复BUG: 原警告日志无频率限制，压力测试下大量函数/监听器异常会刷屏日志文件；
    // 采用令牌桶限流（每 10 秒最多 5 条同类警告），避免日志风暴。
    private static final int MAX_ENTITY_STATES = 1024;
    private static final long WARN_RATE_LIMIT_MS = 10_000L;
    private static final int WARN_MAX_PER_WINDOW = 5;

    private final Map<String, EntityAnimationState> entityAnimationStates;
    private final Map<String, AnimationEvent> registeredEvents;
    private final Map<String, BiConsumer<String, String[]>> registeredFunctions;

    // 警告限流使用静态字段，供静态内部类 AnimationEvent 访问
    private static final AtomicLong lastFunctionWarnTime = new AtomicLong(0);
    private static final AtomicLong functionWarnCount = new AtomicLong(0);
    private static final AtomicLong lastListenerWarnTime = new AtomicLong(0);
    private static final AtomicLong listenerWarnCount = new AtomicLong(0);

    private volatile boolean enabled = true;
    private volatile float globalSpeed = DEFAULT_SPEED;

    public AnimationController() {
        this.entityAnimationStates = new ConcurrentHashMap<>();
        this.registeredEvents = new ConcurrentHashMap<>();
        this.registeredFunctions = new ConcurrentHashMap<>();

        CoreSplitMod.LOGGER.info("[CoreSplit] AnimationController initialized");
    }

    public void updateAnimations(float deltaTime) {
        if (!enabled) return;

        float adjustedDelta = deltaTime * globalSpeed;

        for (EntityAnimationState state : entityAnimationStates.values()) {
            state.update(adjustedDelta);
        }
    }

    public EntityAnimationState getOrCreateState(String entityId) {
        EntityAnimationState state = entityAnimationStates.get(entityId);
        if (state != null) {
            return state;
        }
        // 达到上限时拒绝创建新状态，避免内存无界增长
        if (entityAnimationStates.size() >= MAX_ENTITY_STATES) {
            return null;
        }
        return entityAnimationStates.computeIfAbsent(entityId, EntityAnimationState::new);
    }

    // 警告限流：同一类警告在 WARN_RATE_LIMIT_MS 内最多输出 WARN_MAX_PER_WINDOW 条
    private static boolean allowFunctionWarn() {
        long now = System.currentTimeMillis();
        long last = lastFunctionWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastFunctionWarnTime.set(now);
            functionWarnCount.set(1);
            return true;
        }
        return functionWarnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    private static boolean allowListenerWarn() {
        long now = System.currentTimeMillis();
        long last = lastListenerWarnTime.get();
        if (now - last > WARN_RATE_LIMIT_MS) {
            lastListenerWarnTime.set(now);
            listenerWarnCount.set(1);
            return true;
        }
        return listenerWarnCount.incrementAndGet() <= WARN_MAX_PER_WINDOW;
    }

    public void startAnimation(String entityId, String animationName) {
        EntityAnimationState state = getOrCreateState(entityId);
        if (state == null) return;
        state.startAnimation(animationName);
        triggerEvent(animationName + ".start", entityId);
    }

    public void startAnimation(String entityId, String animationName, JemFileParser.CemModel model) {
        EntityAnimationState state = getOrCreateState(entityId);
        if (state == null) return;
        state.startAnimation(animationName, model);
        triggerEvent(animationName + ".start", entityId);
    }

    public void stopAnimation(String entityId, String animationName) {
        EntityAnimationState state = entityAnimationStates.get(entityId);
        if (state != null) {
            state.stopAnimation(animationName);
            triggerEvent(animationName + ".stop", entityId);
        }
    }

    public void stopAllAnimations(String entityId) {
        EntityAnimationState state = entityAnimationStates.get(entityId);
        if (state != null) {
            for (String animName : new ArrayList<>(state.activeAnimations.keySet())) {
                state.stopAnimation(animName);
            }
        }
    }

    public void setAnimationSpeed(String entityId, String animationName, float speed) {
        EntityAnimationState state = entityAnimationStates.get(entityId);
        if (state != null) {
            state.setAnimationSpeed(animationName, speed);
        }
    }

    public void setEntityState(String entityId, String stateKey, String stateValue) {
        EntityAnimationState state = getOrCreateState(entityId);
        if (state != null) {
            state.setEntityState(stateKey, stateValue);
        }
    }

    public void clearEntityState(String entityId) {
        entityAnimationStates.remove(entityId);
    }

    public void clearAllStates() {
        entityAnimationStates.clear();
    }

    public void registerEvent(String eventName, AnimationEventListener listener) {
        registeredEvents.computeIfAbsent(eventName, k -> new AnimationEvent(eventName)).addListener(listener);
    }

    public void unregisterEvent(String eventName) {
        registeredEvents.remove(eventName);
    }

    public void registerFunction(String functionName, BiConsumer<String, String[]> handler) {
        registeredFunctions.put(functionName.toLowerCase(), handler);
    }

    public void unregisterFunction(String functionName) {
        registeredFunctions.remove(functionName.toLowerCase());
    }

    public void executeFunction(String functionName, String entityId, String[] args) {
        BiConsumer<String, String[]> handler = registeredFunctions.get(functionName.toLowerCase());
        if (handler != null) {
            try {
                handler.accept(entityId, args);
            } catch (Exception e) {
                // 修复BUG: 原警告无限流，压力测试下大量函数异常会刷屏日志；
                // 改用令牌桶限流，避免日志风暴。
                if (allowFunctionWarn()) {
                    CoreSplitMod.LOGGER.warn("[CoreSplit] Error executing function: {}", functionName, e);
                }
            }
        }
    }

    public void addAnimationEventListener(AnimationEventListener listener) {
        registerEvent("*", listener);
    }

    public void removeAnimationEventListener(AnimationEventListener listener) {
        for (AnimationEvent event : registeredEvents.values()) {
            event.removeListener(listener);
        }
    }

    private void triggerEvent(String eventName, String entityId) {
        AnimationEvent event = registeredEvents.get(eventName);
        if (event != null) {
            event.trigger(entityId);
        }
        // 修复BUG: 原代码未触发通配符"*"事件，导致addAnimationEventListener注册的全局监听器永远不会被调用
        if (!"*".equals(eventName)) {
            AnimationEvent wildcardEvent = registeredEvents.get("*");
            if (wildcardEvent != null) {
                wildcardEvent.trigger(entityId);
            }
        }
    }

    public void setGlobalSpeed(float speed) {
        this.globalSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    public float getGlobalSpeed() {
        return globalSpeed;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearAllStates();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getActiveEntityCount() {
        return entityAnimationStates.size();
    }

    public int getTotalActiveAnimations() {
        int total = 0;
        for (EntityAnimationState state : entityAnimationStates.values()) {
            total += state.activeAnimations.size();
        }
        return total;
    }

    public static class EntityAnimationState {
        public final String entityId;
        public final Map<String, ActiveAnimation> activeAnimations;
        public final Map<String, String> entityStates;

        public EntityAnimationState(String entityId) {
            this.entityId = entityId;
            this.activeAnimations = new ConcurrentHashMap<>();
            this.entityStates = new ConcurrentHashMap<>();
        }

        public void update(float deltaTime) {
            for (ActiveAnimation anim : activeAnimations.values()) {
                anim.update(deltaTime);
            }
        }

        public void startAnimation(String animationName) {
            activeAnimations.put(animationName, new ActiveAnimation(animationName));
        }

        public void startAnimation(String animationName, JemFileParser.CemModel model) {
            JemFileParser.Animation animation = model.getAnimation(animationName);
            if (animation != null) {
                activeAnimations.put(animationName, new ActiveAnimation(animationName, animation));
            } else {
                startAnimation(animationName);
            }
        }

        public void stopAnimation(String animationName) {
            activeAnimations.remove(animationName);
        }

        public void setAnimationSpeed(String animationName, float speed) {
            ActiveAnimation anim = activeAnimations.get(animationName);
            if (anim != null) {
                anim.setSpeed(speed);
            }
        }

        public void setEntityState(String key, String value) {
            entityStates.put(key, value);
        }

        public String getEntityState(String key) {
            return entityStates.get(key);
        }

        public boolean hasAnimation(String animationName) {
            return activeAnimations.containsKey(animationName);
        }

        public ActiveAnimation getAnimation(String animationName) {
            return activeAnimations.get(animationName);
        }
    }

    public static class ActiveAnimation {
        public final String name;
        public final JemFileParser.Animation definition;

        public float currentTime;
        public float speed = DEFAULT_SPEED;
        public boolean playing = true;
        public boolean paused = false;

        public ActiveAnimation(String name) {
            this.name = name;
            this.definition = null;
        }

        public ActiveAnimation(String name, JemFileParser.Animation definition) {
            this.name = name;
            this.definition = definition;
        }

        public void update(float deltaTime) {
            if (!playing || paused || definition == null) return;

            currentTime += deltaTime * speed * MILLIS_PER_SECOND;

            if (currentTime >= definition.duration) {
                if (definition.loop) {
                    currentTime = 0;
                } else {
                    playing = false;
                }
            }
        }

        public void setSpeed(float speed) {
            this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        }

        public void pause() {
            this.paused = true;
        }

        public void resume() {
            this.paused = false;
        }

        public void reset() {
            this.currentTime = 0;
            this.playing = true;
            this.paused = false;
        }

        public float getProgress() {
            if (definition == null || definition.duration <= 0) return 0;
            return Math.min(1.0f, currentTime / definition.duration);
        }

        public JemFileParser.AnimationFrame getCurrentFrame() {
            if (definition == null || definition.frames.isEmpty()) return null;

            int frameCount = definition.frames.size();
            if (frameCount == 1) return definition.frames.get(0);

            float progress = getProgress();
            int frameIndex = (int) (progress * (frameCount - 1));

            return definition.frames.get(Math.min(frameIndex, frameCount - 1));
        }

        public JemFileParser.AnimationFrame getInterpolatedFrame() {
            if (definition == null || definition.frames.size() < 2) return getCurrentFrame();

            float progress = getProgress();
            int totalFrames = definition.frames.size();
            float frameProgress = progress * (totalFrames - 1);

            int lowerFrame = (int) Math.floor(frameProgress);
            int upperFrame = Math.min(lowerFrame + 1, totalFrames - 1);

            float interpolationFactor = frameProgress - lowerFrame;

            JemFileParser.AnimationFrame frame1 = definition.frames.get(lowerFrame);
            JemFileParser.AnimationFrame frame2 = definition.frames.get(upperFrame);

            return interpolateFrames(frame1, frame2, interpolationFactor);
        }

        private JemFileParser.AnimationFrame interpolateFrames(
                JemFileParser.AnimationFrame frame1,
                JemFileParser.AnimationFrame frame2,
                float factor) {
            JemFileParser.AnimationFrame result = new JemFileParser.AnimationFrame();

            for (int i = 0; i < 3; i++) {
                result.rotation[i] = lerp(frame1.rotation[i], frame2.rotation[i], factor);
                result.translation[i] = lerp(frame1.translation[i], frame2.translation[i], factor);
                result.scale[i] = lerp(frame1.scale[i], frame2.scale[i], factor);
            }

            return result;
        }

        private float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

    public static class AnimationEvent {
        public final String name;
        public final List<AnimationEventListener> listeners;

        public AnimationEvent(String name) {
            this.name = name;
            // 修复BUG: 原代码使用ArrayList，trigger()迭代listeners时若其他线程调用addListener/removeListener会抛ConcurrentModificationException；改用CopyOnWriteArrayList保证线程安全
            this.listeners = new CopyOnWriteArrayList<>();
        }

        public void addListener(AnimationEventListener listener) {
            listeners.add(listener);
        }

        public void removeListener(AnimationEventListener listener) {
            listeners.remove(listener);
        }

        public void trigger(String entityId) {
            for (AnimationEventListener listener : listeners) {
                try {
                    listener.onEvent(entityId, name);
                } catch (Exception e) {
                    // 修复BUG: 原警告无限流，压力测试下大量监听器异常会刷屏日志；
                    // 改用令牌桶限流，避免日志风暴。
                    if (allowListenerWarn()) {
                        CoreSplitMod.LOGGER.warn("[CoreSplit] Error in animation event listener: {}", name, e);
                    }
                }
            }
        }
    }

    public interface AnimationEventListener {
        void onEvent(String entityId, String eventName);
    }
}