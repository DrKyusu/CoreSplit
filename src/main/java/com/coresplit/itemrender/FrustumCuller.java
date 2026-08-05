package com.coresplit.itemrender;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class FrustumCuller {

    private final ItemRenderConfig config;

    private Vec3 cameraPos = Vec3.ZERO;
    private Vec3 cameraLook = Vec3.ZERO;
    private float fov = 70.0f;
    private float aspectRatio = 1.777f;
    private float marginRadians = 0.26f;

    private float halfFovX = 0.6f;
    private float halfFovY = 0.4f;

    private Vec3 rightVec = Vec3.ZERO;
    private Vec3 upVec = Vec3.ZERO;

    public FrustumCuller(ItemRenderConfig config) {
        this.config = config;
        updateMargin();
    }

    public void updateMargin() {
        this.marginRadians = (float) Math.toRadians(config.getFovMarginDegrees());
        recomputeDerived();
    }

    public void updateCamera(Minecraft mc, float partialTick) {
        Entity cameraEntity = mc.getCameraEntity();
        if (cameraEntity == null) return;

        this.cameraPos = cameraEntity.getEyePosition(partialTick);

        double rotX = Math.toRadians(cameraEntity.getXRot());
        double rotY = Math.toRadians(cameraEntity.getYRot());

        double cosPitch = Math.cos(rotX);
        double sinPitch = Math.sin(rotX);
        double cosYaw = Math.cos(rotY);
        double sinYaw = Math.sin(rotY);

        this.cameraLook = new Vec3(
                -sinYaw * cosPitch,
                sinPitch,
                -cosYaw * cosPitch
        ).normalize();

        Vec3 worldUp = new Vec3(0, 1, 0);
        this.rightVec = cameraLook.cross(worldUp).normalize();
        this.upVec = rightVec.cross(cameraLook).normalize();

        if (mc.options != null) {
            this.fov = mc.options.fov().get().floatValue();
        }
        if (mc.getWindow() != null && mc.getWindow().getHeight() > 0) {
            this.aspectRatio = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        }

        recomputeDerived();
    }

    private void recomputeDerived() {
        float fovRad = (float) Math.toRadians(fov);
        float margin = marginRadians;
        this.halfFovY = fovRad * 0.5f + margin;
        this.halfFovX = (float) Math.atan(Math.tan(fovRad * 0.5f + margin) * aspectRatio);
    }

    public boolean isInView(double x, double y, double z, float radius) {
        Vec3 toTarget = new Vec3(
                x - cameraPos.x,
                y - cameraPos.y,
                z - cameraPos.z
        );

        double dist = toTarget.length();
        if (dist < 0.001) return true;

        double cosAngle = toTarget.dot(cameraLook) / dist;
        if (cosAngle < 0) return false;

        double angle = Math.acos(Math.min(1.0, cosAngle));
        if (angle > halfFovY + radius / Math.max(dist, 1.0)) {
            return false;
        }

        Vec3 toTargetNorm = toTarget.scale(1.0 / dist);
        double rightDot = toTargetNorm.dot(rightVec);
        double upDot = toTargetNorm.dot(upVec);

        double halfW = Math.tan(halfFovX) + radius / Math.max(dist, 1.0);
        double halfH = Math.tan(halfFovY) + radius / Math.max(dist, 1.0);

        if (Math.abs(rightDot) > halfW) return false;
        if (Math.abs(upDot) > halfH) return false;

        return true;
    }

    public boolean isInViewFast(double x, double y, double z) {
        Vec3 toTarget = new Vec3(
                x - cameraPos.x,
                y - cameraPos.y,
                z - cameraPos.z
        );

        double distSq = toTarget.lengthSqr();
        if (distSq < 1.0) return true;

        double cosAngle = toTarget.dot(cameraLook) / Math.sqrt(distSq);
        if (cosAngle < 0) return false;

        double angle = Math.acos(Math.min(1.0, cosAngle));
        return angle < halfFovY;
    }

    public Vec3 getCameraPos() {
        return cameraPos;
    }

    public float getHalfFovX() {
        return halfFovX;
    }

    public float getHalfFovY() {
        return halfFovY;
    }
}
