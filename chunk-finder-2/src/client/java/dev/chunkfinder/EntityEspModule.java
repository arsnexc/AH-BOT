package dev.chunkfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-visible entity or dropped-item ESP. Does not fetch hidden entities or load chunks. */
public final class EntityEspModule extends Module {
    public enum Mode { LIVING, DROPPED_ITEMS }
    private static final int MAX_OUTLINES = 32;
    private final Mode mode;
    private final OverlaySettings settings;

    public EntityEspModule(Mode mode) {
        super(mode == Mode.LIVING ? "Entity ESP" : "Dropped Item ESP", ModuleCategory.VISUALS, false);
        this.mode = mode;
        this.settings = new OverlaySettings(mode == Mode.LIVING
                ? "chunkfinder-entities.json" : "chunkfinder-dropped-items.json", 48, 16, 128);
        super.setEnabled(settings.enabled());
    }

    public Mode mode() { return mode; }
    public int range() { return settings.range(); }
    public void setRange(int value) { settings.setRange(value); }
    public boolean option() { return settings.option(); }
    public void setOption(boolean value) { settings.setOption(value); }
    @Override public void setEnabled(boolean value) { super.setEnabled(value); settings.setEnabled(value); }

    private List<Entity> visible(Minecraft client, Vec3 viewpoint) {
        List<Entity> found = new ArrayList<>();
        if (!isEnabled() || !FeatureAccess.allowed(client)) return found;
        double maxDistance = (double) range() * range();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.position().distanceToSqr(viewpoint) > maxDistance) continue;
            if (mode == Mode.LIVING) {
                if (!(entity instanceof LivingEntity) || entity == client.player) continue;
                if (!option() && entity instanceof Player) continue;
            } else if (!(entity instanceof ItemEntity)) continue;
            found.add(entity);
        }
        found.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(viewpoint)));
        if (found.size() > MAX_OUTLINES) return found.subList(0, MAX_OUTLINES);
        return found;
    }

    public void renderWorld(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || !FeatureAccess.allowed(client) || context.consumers() == null) return;
        Vec3 camera = context.gameRenderer().getMainCamera().position();
        List<Entity> found = visible(client, camera);
        if (found.isEmpty()) return;
        PoseStack matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(
                net.minecraft.client.renderer.rendertype.ChunkFinderThroughWallLines.renderType());
        for (Entity entity : found) {
            int color = color(entity);
            AABB box = entity.getBoundingBox().inflate(0.07);
            ShapeRenderer.renderShape(matrices, lines,
                    Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                    -camera.x, -camera.y, -camera.z, color, 1.0F);
        }
    }

    private int color(Entity entity) {
        if (mode == Mode.DROPPED_ITEMS) return 0xFFFFD27B;
        if (entity instanceof Player) return 0xFF82B6FF;
        return entity instanceof Monster ? 0xFFFF808D : 0xFF89E6B6;
    }

    public void renderHud(Minecraft client, GuiGraphics g) {
        if (!isEnabled() || !FeatureAccess.allowed(client) || client.options.hideGui) return;
        Vec3 viewpoint = client.getCameraEntity() == null
                ? client.player.position() : client.getCameraEntity().position();
        List<Entity> found = visible(client, viewpoint);
        int x = 12;
        int y = mode == Mode.LIVING ? 157 : Math.max(12, g.guiHeight() - 123);
        int count = Math.min(2, found.size());
        int height = 31 + count * 14;
        if (y + height > g.guiHeight() - 4) y = Math.max(4, g.guiHeight() - height - 4);
        g.fill(x, y, x + 214, y + height, 0xD9101420);
        g.fill(x, y, x + 3, y + height, mode == Mode.LIVING ? 0xFF82B6FF : 0xFFFFD27B);
        g.drawString(client.font, Component.literal(mode == Mode.LIVING ? "ENTITY ESP" : "DROPPED ITEM ESP"),
                x + 11, y + 7, 0xFFF0F5FF, true);
        g.drawString(client.font, Component.literal(found.size() + (found.size() == MAX_OUTLINES ? "+" : "") + " nearby"),
                x + 146, y + 7, 0xFFADBACB, false);
        for (int i = 0; i < count; i++) {
            Entity entity = found.get(i);
            String label;
            if (entity instanceof ItemEntity item) {
                label = item.getItem().getHoverName().getString();
                if (option()) label += " ×" + item.getItem().getCount();
            } else label = entity.getName().getString();
            if (client.font.width(label) > 188)
                label = client.font.substrByWidth(Component.literal(label), 185).getString() + "…";
            g.drawString(client.font, Component.literal(label), x + 11, y + 23 + i * 14, 0xFFD7E5F1, false);
        }
    }
}
