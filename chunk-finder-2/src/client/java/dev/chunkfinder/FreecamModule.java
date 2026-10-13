package dev.chunkfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public final class FreecamModule extends Module {
    private Entity camera;
    private Entity previousCamera;
    private int speed = 5;
    private boolean captured;
    private boolean up, down, left, right, jump, sneak, sprint;

    public FreecamModule() { super("Freecam", ModuleCategory.VISUALS, false); }

    public int speed() { return speed; }
    public void setSpeed(int value) { speed = Math.max(1, Math.min(10, value)); }

    public boolean toggle(Minecraft client) {
        if (isEnabled()) {
            stop(client);
            return true;
        }
        if (!FeatureAccess.allowed(client) || client.player == null || client.level == null) return false;
        Entity detached = EntityType.MARKER.create(client.level, EntitySpawnReason.LOAD);
        if (detached == null) return false;
        previousCamera = client.getCameraEntity();
        camera = detached;
        camera.setPos(client.player.getX(), client.player.getEyeY(), client.player.getZ());
        camera.setYRot(client.player.getYRot());
        camera.setXRot(client.player.getXRot());
        camera.setOldPosAndRot();
        client.setCameraEntity(camera);
        super.setEnabled(true);
        return true;
    }

    public void beginTick(Minecraft client) {
        if (!isEnabled()) return;
        if (!FeatureAccess.allowed(client) || client.player == null || client.level == null
                || camera == null || camera.isRemoved()) {
            stop(client);
            return;
        }
        captureAndMask(client.options.keyUp, 0);
        captureAndMask(client.options.keyDown, 1);
        captureAndMask(client.options.keyLeft, 2);
        captureAndMask(client.options.keyRight, 3);
        captureAndMask(client.options.keyJump, 4);
        captureAndMask(client.options.keyShift, 5);
        captureAndMask(client.options.keySprint, 6);
        captured = true;
    }

    public void tick(Minecraft client) {
        if (!isEnabled()) return;
        if (!FeatureAccess.allowed(client) || client.player == null || client.level == null
                || camera == null || camera.isRemoved()) {
            stop(client);
            return;
        }
        camera.setYRot(client.player.getYRot());
        camera.setXRot(client.player.getXRot());
        if (captured && client.screen == null) moveCamera();
        restoreInputs(client);
    }

    private void captureAndMask(KeyMapping mapping, int key) {
        boolean wasDown = mapping.isDown();
        switch (key) {
            case 0 -> up = wasDown;
            case 1 -> down = wasDown;
            case 2 -> left = wasDown;
            case 3 -> right = wasDown;
            case 4 -> jump = wasDown;
            case 5 -> sneak = wasDown;
            case 6 -> sprint = wasDown;
            default -> throw new IllegalArgumentException("Unknown movement key");
        }
        mapping.setDown(false);
    }

    private void moveCamera() {
        double forward = (up ? 1.0 : 0.0) - (down ? 1.0 : 0.0);
        double strafe = (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
        double vertical = (jump ? 1.0 : 0.0) - (sneak ? 1.0 : 0.0);
        double magnitude = Math.sqrt(forward * forward + strafe * strafe + vertical * vertical);
        if (magnitude == 0.0) return;
        forward /= magnitude;
        strafe /= magnitude;
        vertical /= magnitude;

        double yaw = Math.toRadians(camera.getYRot());
        double moveSpeed = speed * (sprint ? 0.2 : 0.1);
        double dx = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double dz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        Vec3 position = camera.position();
        camera.setOldPosAndRot();
        camera.setPos(position.x + dx * moveSpeed, position.y + vertical * moveSpeed,
                position.z + dz * moveSpeed);
    }

    private void restoreInputs(Minecraft client) {
        if (!captured) return;
        client.options.keyUp.setDown(up);
        client.options.keyDown.setDown(down);
        client.options.keyLeft.setDown(left);
        client.options.keyRight.setDown(right);
        client.options.keyJump.setDown(jump);
        client.options.keyShift.setDown(sneak);
        client.options.keySprint.setDown(sprint);
        captured = false;
    }

    private void stop(Minecraft client) {
        restoreInputs(client);
        Entity restore = previousCamera;
        if (client.level == null || restore == null || restore.isRemoved() || restore.level() != client.level) {
            restore = client.player;
        }
        if (restore != null) client.setCameraEntity(restore);
        if (camera != null && !camera.isRemoved()) camera.setRemoved(Entity.RemovalReason.DISCARDED);
        camera = null;
        previousCamera = null;
        super.setEnabled(false);
    }

    public void renderHud(Minecraft client, GuiGraphics graphics) {
        if (!isEnabled() || client.options.hideGui) return;
        Component label = Component.literal("FREECAM  ·  CLIENT CAMERA");
        int x = (graphics.guiWidth() - client.font.width(label)) / 2;
        graphics.drawString(client.font, label, x, 12, 0xFF8CE0BD, true);
    }
}
