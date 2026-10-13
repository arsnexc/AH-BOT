package dev.chunkfinder;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Detached camera. Mouse look is routed here by MouseLookMixin instead of turning the player. */
public final class FreecamModule extends Module {
    private static FreecamModule active;
    private final OverlaySettings settings = new OverlaySettings("chunkfinder-freecam.json", 5, 1, 10);
    private Entity camera;
    private Entity previousCamera;
    private boolean captured;
    private boolean up, down, left, right, jump, sneak, sprint;

    public FreecamModule() { super("Freecam", ModuleCategory.VISUALS, false); }

    public int speed() { return settings.range(); }
    public void setSpeed(int value) { settings.setRange(value); }

    public boolean toggle(Minecraft client) {
        if (isEnabled()) {
            stop(client);
            return true;
        }
        if (!FeatureAccess.allowed(client) || client.player.isRemoved()) return false;
        Entity detached = EntityType.MARKER.create(client.level, EntitySpawnReason.LOAD);
        if (detached == null) return false;
        previousCamera = client.getCameraEntity();
        camera = detached;
        camera.setPos(client.player.getX(), client.player.getEyeY(), client.player.getZ());
        camera.setYRot(client.player.getYRot());
        camera.setXRot(client.player.getXRot());
        camera.setOldPosAndRot();
        client.setCameraEntity(camera);
        active = this;
        super.setEnabled(true);
        return true;
    }

    /** Return true if this mouse movement was consumed by the detached camera. */
    public static boolean turnCamera(LocalPlayer player, double yaw, double pitch) {
        FreecamModule mod = active;
        Minecraft client = Minecraft.getInstance();
        if (mod == null || !mod.isEnabled() || mod.camera == null || player == null
                || player != client.player || client.getCameraEntity() != mod.camera
                || mod.camera.level() != player.level()) return false;
        mod.camera.turn(yaw, pitch);
        return true;
    }

    public void beginTick(Minecraft client) {
        if (!isEnabled()) return;
        if (!valid(client)) { stop(client); return; }
        // The player reads these bindings during its tick. Mask them until END_CLIENT_TICK.
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
        if (!valid(client)) { stop(client); return; }
        camera.setOldPosAndRot();
        if (captured && client.screen == null) moveCamera();
        restoreInputs(client);
    }

    private boolean valid(Minecraft client) {
        return FeatureAccess.allowed(client) && !client.player.isRemoved() && camera != null
                && !camera.isRemoved() && camera.level() == client.level
                && client.getCameraEntity() == camera;
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
        if (forward == 0 && strafe == 0 && vertical == 0) return;
        double yaw = Math.toRadians(camera.getYRot());
        Vec3 look = camera.getLookAngle();
        double dx = look.x * forward + Math.cos(yaw) * strafe;
        double dy = look.y * forward + vertical;
        double dz = look.z * forward + Math.sin(yaw) * strafe;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) return;
        double movement = speed() * (sprint ? 0.2 : 0.1) / length;
        camera.setPos(camera.getX() + dx * movement, camera.getY() + dy * movement,
                camera.getZ() + dz * movement);
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
        // Do not overwrite another mod's camera if it took ownership during freecam.
        if (client != null && client.getCameraEntity() == camera) {
            Entity restore = previousCamera;
            if (client.level == null || restore == null || restore.isRemoved()
                    || restore.level() != client.level) restore = client.player;
            if (restore != null) client.setCameraEntity(restore);
        }
        if (camera != null && !camera.isRemoved()) camera.setRemoved(Entity.RemovalReason.DISCARDED);
        if (active == this) active = null;
        camera = null;
        previousCamera = null;
        super.setEnabled(false);
    }

    public void renderHud(Minecraft client, GuiGraphics graphics) {
        if (!isEnabled() || client.options.hideGui || camera == null) return;
        String label = "FREECAM  ·  " + speed() + "x  ·  "
                + camera.getBlockX() + ", " + camera.getBlockY() + ", " + camera.getBlockZ();
        graphics.drawCenteredString(client.font, Component.literal(label), graphics.guiWidth() / 2,
                12, 0xFF8CE0BD);
    }
}
