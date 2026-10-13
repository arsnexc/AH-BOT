package dev.chunkfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Approximate client-side path preview. Never changes projectile physics or loads chunks. */
public final class TrajectoriesModule extends Module {
    private static final int MAX_TRACKED = 24;
    private final OverlaySettings settings = new OverlaySettings("chunkfinder-trajectories.json", 36, 12, 72);
    private int visibleCount;

    public TrajectoriesModule() {
        super("Trajectories", ModuleCategory.VISUALS, false);
        super.setEnabled(settings.enabled());
    }

    @Override public void setEnabled(boolean value) { super.setEnabled(value); settings.setEnabled(value); }
    public int steps() { return settings.range(); }
    public void setSteps(int value) { settings.setRange(value); }
    public boolean showItems() { return settings.option(); }
    public void setShowItems(boolean value) { settings.setOption(value); }

    public void renderWorld(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        visibleCount = 0;
        if (!isEnabled() || !FeatureAccess.allowed(client) || context.consumers() == null) return;
        Vec3 camera = context.gameRenderer().getMainCamera().position();
        PoseStack.Pose pose = context.matrices().last();
        VertexConsumer lines = context.consumers().getBuffer(
                net.minecraft.client.renderer.rendertype.ChunkFinderThroughWallLines.renderType());
        for (Entity entity : client.level.entitiesForRendering()) {
            boolean item = entity instanceof ItemEntity;
            if (!(entity instanceof Projectile) && !(item && showItems())) continue;
            if (entity.isRemoved() || entity.position().distanceToSqr(camera) > 96.0 * 96.0
                    || entity.getDeltaMovement().lengthSqr() < 0.0004) continue;
            if (visibleCount++ >= MAX_TRACKED) break;
            Vec3 position = entity.position();
            Vec3 velocity = entity.getDeltaMovement();
            double gravity = entity.isNoGravity() ? 0.0 : entity.getGravity();
            int color = item ? 0xFFFFD69A : 0xFF80D5FF;
            for (int step = 0; step < steps(); step++) {
                Vec3 next = position.add(velocity);
                BlockPos probe = new BlockPos((int) Math.floor(next.x), (int) Math.floor(next.y),
                        (int) Math.floor(next.z));
                if (!client.level.hasChunkAt(probe)) break;
                BlockHitResult hit = client.level.clip(new ClipContext(position, next,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
                boolean collided = hit.getType() == HitResult.Type.BLOCK;
                if (collided) next = hit.getLocation();
                addSegment(lines, pose, position.subtract(camera), next.subtract(camera), color);
                if (collided) break;
                position = next;
                velocity = velocity.scale(0.99).add(0.0, -gravity, 0.0);
            }
        }
    }

    private static void addSegment(VertexConsumer lines, PoseStack.Pose pose, Vec3 a, Vec3 b, int color) {
        lines.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(color).setNormal(pose, 0.0F, 1.0F, 0.0F).setLineWidth(1.8F);
        lines.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(color).setNormal(pose, 0.0F, 1.0F, 0.0F).setLineWidth(1.8F);
    }

    public void renderHud(Minecraft client, GuiGraphics graphics) {
        if (!isEnabled() || !FeatureAccess.allowed(client) || client.options.hideGui) return;
        int x = 12, y = graphics.guiHeight() - 49;
        graphics.fill(x, y, x + 208, y + 37, 0xDA101722);
        graphics.fill(x, y, x + 3, y + 37, 0xFF80D5FF);
        graphics.drawString(client.font, Component.literal("TRAJECTORIES"), x + 11, y + 7, 0xFFCBECFF, true);
        graphics.drawString(client.font, Component.literal(visibleCount + " moving objects  ·  approximate path"),
                x + 11, y + 21, 0xFFAABBCD, false);
    }
}
