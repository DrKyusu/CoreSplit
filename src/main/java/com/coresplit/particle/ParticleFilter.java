package com.coresplit.particle;

import com.coresplit.CoreSplitMod;
import com.coresplit.compat.ShaderPerformanceOptimizer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 粒子过滤器：基于玩家视线和距离过滤粒子。
 *
 * <p>策略：
 * <ul>
 *   <li>视野内（目视可见）的粒子：始终加载，不受距离限制</li>
 *   <li>视野外的粒子：距离超过"粒子渲染距离"时屏蔽</li>
 * </ul>
 *
 * <p>通过每帧（tick）更新相机状态，在 {@code ParticleEngine.createParticle}
 * 注入点根据粒子位置判断是否创建。
 *
 * <p>光影感知：光影激活时，视野外粒子的渲染距离自动缩减到 32 块，
 * 减少进入 Iris 着色器管线的粒子数量，降低 GPU 负载。
 */
public class ParticleFilter {

    private static final float FOV_MARGIN_RADIANS = 0.35f;
    private static final long CAMERA_UPDATE_INTERVAL_NS = 16_000_000L;
    private static final int SHADER_PARTICLE_RENDER_DISTANCE = 32;

    private volatile boolean enabled = true;
    private volatile int renderDistance = 64;

    private volatile double camX, camY, camZ;
    private volatile double lookX, lookY, lookZ;
    private volatile float halfFovY = 0.6f;

    private volatile long lastCameraUpdateNs = 0L;

    private static volatile ParticleFilter instance;

    public static ParticleFilter getInstance() {
        ParticleFilter result = instance;
        if (result == null) {
            synchronized (ParticleFilter.class) {
                result = instance;
                if (result == null) {
                    result = new ParticleFilter();
                    instance = result;
                }
            }
        }
        return result;
    }

    private ParticleFilter() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> updateCamera(client, 1.0f));
    }

    public void updateCamera(Minecraft mc, float partialTick) {
        long now = System.nanoTime();
        if (now - lastCameraUpdateNs < CAMERA_UPDATE_INTERVAL_NS) return;
        lastCameraUpdateNs = now;

        try {
            Entity cameraEntity = mc.getCameraEntity();
            if (cameraEntity == null) return;

            Vec3 eyePos = cameraEntity.getEyePosition(partialTick);
            this.camX = eyePos.x;
            this.camY = eyePos.y;
            this.camZ = eyePos.z;

            double rotX = Math.toRadians(cameraEntity.getXRot());
            double rotY = Math.toRadians(cameraEntity.getYRot());
            double cosPitch = Math.cos(rotX);
            double sinPitch = Math.sin(rotX);
            double cosYaw = Math.cos(rotY);
            double sinYaw = Math.sin(rotY);

            this.lookX = -sinYaw * cosPitch;
            this.lookY = sinPitch;
            this.lookZ = -cosYaw * cosPitch;

            float fov = 70.0f;
            if (mc.options != null) {
                fov = mc.options.fov().get().floatValue();
            }
            float fovRad = (float) Math.toRadians(fov);
            this.halfFovY = fovRad * 0.5f + FOV_MARGIN_RADIANS;
        } catch (Exception e) {
            CoreSplitMod.LOGGER.debug("[CoreSplit] ParticleFilter camera update failed", e);
        }
    }

    /**
     * 判断是否应该创建位于 (x, y, z) 的粒子。
     *
     * <p>光影感知：光影激活时，视野外粒子的渲染距离自动缩减，
     * 减少进入 Iris 着色器管线的粒子数量。
     *
     * @return true 表示允许创建，false 表示应屏蔽
     */
    public boolean shouldCreateParticle(double x, double y, double z) {
        if (!enabled) return true;

        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (isInView(dx, dy, dz, distSq)) {
            return true;
        }

        // 光影感知：光影下使用更短的渲染距离
        double maxDist = getEffectiveRenderDistance();
        return distSq <= maxDist * maxDist;
    }

    /**
     * 获取当前有效的粒子渲染距离。
     * 光影激活时返回缩减后的距离，否则返回配置值。
     */
    private double getEffectiveRenderDistance() {
        try {
            if (ShaderPerformanceOptimizer.getInstance().isShaderActive()) {
                return SHADER_PARTICLE_RENDER_DISTANCE;
            }
        } catch (Exception ignored) {}
        return renderDistance;
    }

    private boolean isInView(double dx, double dy, double dz, double distSq) {
        if (distSq < 1.0) return true;

        double dist = Math.sqrt(distSq);
        double cosAngle = (dx * lookX + dy * lookY + dz * lookZ) / dist;

        if (cosAngle < 0) return false;

        double angle = Math.acos(Math.min(1.0, cosAngle));
        return angle < halfFovY;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setRenderDistance(int value) {
        this.renderDistance = Math.max(16, Math.min(256, value));
    }

    public int getRenderDistance() {
        return renderDistance;
    }
}
