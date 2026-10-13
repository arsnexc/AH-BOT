package dev.chunkfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** Outlines the entity or block under the crosshair. Target selection uses vanilla hitResult. */
public final class TargetRendererModule extends Module {
    private final OverlaySettings settings = new OverlaySettings("chunkfinder-target.json", 32, 8, 64);

    public TargetRendererModule() {
        super("Target Renderer", ModuleCategory.VISUALS, false);
        super.setEnabled(settings.enabled());
    }

    @Override public void setEnabled(boolean value) { super.setEnabled(value); settings.setEnabled(value); }
    public int range() { return settings.range(); }
    public void setRange(int value) { settings.setRange(value); }
    public boolean showBlocks() { return settings.option(); }
    public void setShowBlocks(boolean value) { settings.setOption(value); }

    private AABB bounds(Minecraft client) {
        if (!FeatureAccess.allowed(client) || client.hitResult == null) return null;
        AABB box;
        if (client.hitResult instanceof EntityHitResult hit) {
            Entity entity = hit.getEntity();
            if (entity.isRemoved()) return null;
            box = entity.getBoundingBox();
        } else if (showBlocks() && client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            box = new AABB(hit.getBlockPos());
        } else return null;
        return box.getCenter().distanceToSqr(client.player.position()) <= (double) range() * range() ? box : null;
    }

    public void renderWorld(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || context.consumers() == null) return;
        AABB box = bounds(client);
        if (box == null) return;
        Vec3 camera = context.gameRenderer().getMainCamera().position();
        PoseStack matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(
                net.minecraft.client.renderer.rendertype.ChunkFinderThroughWallLines.renderType());
        int color = client.hitResult instanceof EntityHitResult ? 0xFFFAABDF : 0xFFB2EAB6;
        for (int layer = 1; layer >= 0; layer--) {
            AABB outline = box.inflate(0.025 + layer * 0.045);
            ShapeRenderer.renderShape(matrices, lines,
                    Shapes.box(outline.minX, outline.minY, outline.minZ,
                            outline.maxX, outline.maxY, outline.maxZ),
                    -camera.x, -camera.y, -camera.z, color, 1.0F);
        }
    }

    public void renderHud(Minecraft client, GuiGraphics graphics) {
        if (!isEnabled() || client.options.hideGui || bounds(client) == null) return;
        String label = client.hitResult instanceof EntityHitResult hit
                ? hit.getEntity().getName().getString() : client.level.getBlockState(
                ((BlockHitResult) client.hitResult).getBlockPos()).getBlock().getName().getString();
        String text = "TARGET  ·  " + label;
        if (client.font.width(text) > 205) text = client.font.substrByWidth(Component.literal(text), 205).getString();
        int x = (graphics.guiWidth() - Math.max(100, client.font.width(text) + 26)) / 2;
        int width = Math.max(100, client.font.width(text) + 26);
        int y = graphics.guiHeight() / 2 + 15;
        graphics.fill(x, y, x + width, y + 23, 0xDC111823);
        graphics.fill(x, y, x + 3, y + 23, 0xFFFAABDF);
        graphics.drawCenteredString(client.font, Component.literal(text), x + width / 2, y + 8, 0xFFEBF0F8);
    }
}
