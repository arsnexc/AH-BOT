package dev.chunkfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChunkFinderModule extends Module {
    private final ChunkFinderSettings settings = ChunkFinderSettings.load();
    private final ArrayDeque<ChunkCoord> queue = new ArrayDeque<>();
    private final Map<ChunkCoord, List<Detection>> results = new HashMap<>();
    private volatile List<Detection> renderDetections = List.of();
    private ClientLevel lastLevel;
    private ChunkScan activeScan;
    private long tickCount;
    private long nextRefreshTick;
    private static final int BLOCKS_PER_TICK = 4096;
    private int queueX = Integer.MIN_VALUE, queueZ = Integer.MIN_VALUE, queueDistance = -1;

    public ChunkFinderModule() {
        super("Chunk Finder", ModuleCategory.VISUALS, false);
        super.setEnabled(settings.enabled());
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        settings.setEnabled(enabled);
        if (!enabled) {
            queue.clear();
            activeScan = null;
            results.clear();
            nextRefreshTick = 0;
            publishDetections();
        }
    }
    public int sensitivity() { return settings.sensitivity(); }
    public int simulationDistance() { return settings.simulationDistance(); }
    public void adjustSensitivity(int n) {
        settings.setSensitivity(settings.sensitivity() + n);
        queue.clear(); activeScan = null; results.clear(); nextRefreshTick = 0; publishDetections();
    }
    public void adjustSimulationDistance(int n) {
        settings.setSimulationDistance(settings.simulationDistance() + n);
        queue.clear(); activeScan = null; results.clear(); nextRefreshTick = 0; publishDetections();
    }

    public void tick(Minecraft client) {
        if (!FeatureAccess.allowed(client)) {
            lastLevel = null;
            queue.clear();
            activeScan = null;
            results.clear();
            nextRefreshTick = 0;
            publishDetections();
            queueX = Integer.MIN_VALUE;
            queueZ = Integer.MIN_VALUE;
            return;
        }
        ClientLevel level = client.level;
        tickCount++;
        if (level == null || client.player == null) return;
        if (level != lastLevel) {
            lastLevel = level; results.clear(); queue.clear(); activeScan = null;
            publishDetections();
            queueX = Integer.MIN_VALUE; queueZ = Integer.MIN_VALUE;
        }
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        int radius = settings.simulationDistance();
        int move = Math.max(1, radius / 2);
        if ((queue.isEmpty() && activeScan == null && tickCount >= nextRefreshTick)
                || radius != queueDistance
                || Math.abs(cx - queueX) >= move || Math.abs(cz - queueZ) >= move) rebuild(level, cx, cz, radius);
        int budget = BLOCKS_PER_TICK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0) {
            if (activeScan == null) {
                ChunkCoord chunk = queue.pollFirst();
                if (chunk == null) break;
                BlockPos probe = new BlockPos(chunk.x() * 16 + 8, level.getMinY(), chunk.z() * 16 + 8);
                if (!level.hasChunkAt(probe)) { results.remove(chunk); publishDetections(); continue; }
                activeScan = new ChunkScan(chunk, 256 * Math.max(0, level.getMaxY() - level.getMinY()));
            }
            ChunkScan scan = activeScan;
            int amount = Math.min(budget, scan.total - scan.cursor);
            for (int i = 0; i < amount; i++) {
                int index = scan.cursor + i;
                int x = scan.chunk.x() * 16 + (index % 256) / 16;
                int z = scan.chunk.z() * 16 + (index % 256) % 16;
                int y = level.getMinY() + index / 256;
                BlockState state = level.getBlockState(pos.set(x, y, z));
                scan.observe(state, x, y, z);
            }
            scan.cursor += amount;
            budget -= amount;
            if (scan.cursor >= scan.total) {
                results.put(scan.chunk, scan.detections());
                activeScan = null;
                publishDetections();
            } else if (amount == 0) {
                activeScan = null;
            }
        }
        if (queue.isEmpty() && activeScan == null && nextRefreshTick <= tickCount)
            nextRefreshTick = tickCount + 40;
    }

    private void rebuild(ClientLevel level, int cx, int cz, int radius) {
        queue.clear();
        activeScan = null;
        List<ChunkCoord> chunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int x = cx - radius; x <= cx + radius; x++)
            for (int z = cz - radius; z <= cz + radius; z++) chunks.add(new ChunkCoord(x, z));
        chunks.sort(Comparator.comparingLong(c -> square(c.x() - cx) + square(c.z() - cz)));
        for (ChunkCoord chunk : chunks) {
            BlockPos probe = new BlockPos(chunk.x() * 16 + 8, level.getMinY(), chunk.z() * 16 + 8);
            if (level.hasChunkAt(probe)) queue.addLast(chunk);
            else results.remove(chunk);
        }
        results.keySet().removeIf(c -> Math.abs(c.x() - cx) > radius || Math.abs(c.z() - cz) > radius);
        publishDetections();
        queueX = cx; queueZ = cz; queueDistance = radius;
    }

    private final class ChunkScan {
        private final ChunkCoord chunk;
        private final int total;
        private int cursor;
        private int budding, activity, activityBlocks;
        private long gx, gy, gz, ax, ay, az;
        private int gxMin = Integer.MAX_VALUE, gyMin = Integer.MAX_VALUE, gzMin = Integer.MAX_VALUE;
        private int gxMax = Integer.MIN_VALUE, gyMax = Integer.MIN_VALUE, gzMax = Integer.MIN_VALUE;
        private int axMin = Integer.MAX_VALUE, ayMin = Integer.MAX_VALUE, azMin = Integer.MAX_VALUE;
        private int axMax = Integer.MIN_VALUE, ayMax = Integer.MIN_VALUE, azMax = Integer.MIN_VALUE;

        private ChunkScan(ChunkCoord chunk, int total) { this.chunk = chunk; this.total = total; }

        private void observe(BlockState state, int x, int y, int z) {
            if (state.is(Blocks.BUDDING_AMETHYST)) {
                budding++; gx += x; gy += y; gz += z;
                gxMin = Math.min(gxMin, x); gyMin = Math.min(gyMin, y); gzMin = Math.min(gzMin, z);
                gxMax = Math.max(gxMax, x); gyMax = Math.max(gyMax, y); gzMax = Math.max(gzMax, z);
            }
            int weight = weight(state, y);
            if (weight > 0) {
                activity += weight; activityBlocks++; ax += x; ay += y; az += z;
                axMin = Math.min(axMin, x); ayMin = Math.min(ayMin, y); azMin = Math.min(azMin, z);
                axMax = Math.max(axMax, x); ayMax = Math.max(ayMax, y); azMax = Math.max(azMax, z);
            }
        }

        private List<Detection> detections() {
            List<Detection> found = new ArrayList<>(2);
            if (budding >= Math.max(1, 10 - settings.sensitivity()))
                found.add(new Detection(Kind.GEODE, (double) gx / budding, (double) gy / budding,
                        (double) gz / budding, budding,
                        new AABB(gxMin, gyMin, gzMin, gxMax + 1, gyMax + 1, gzMax + 1)));
            if (activityBlocks > 0 && activity >= Math.max(4, 24 - settings.sensitivity() * 2))
                found.add(new Detection(Kind.ACTIVITY, (double) ax / activityBlocks,
                        (double) ay / activityBlocks, (double) az / activityBlocks, activity,
                        new AABB(axMin, ayMin, azMin, axMax + 1, ayMax + 1, azMax + 1)));
            return found;
        }
    }

    private void publishDetections() {
        List<Detection> snapshot = new ArrayList<>();
        for (List<Detection> chunk : results.values()) snapshot.addAll(chunk);
        renderDetections = List.copyOf(snapshot);
    }

    private static int weight(BlockState s, int y) {
        if (s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL) || s.is(Blocks.ENDER_CHEST)) return 4;
        if (s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER)
                || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.ANVIL)
                || s.is(Blocks.CHIPPED_ANVIL) || s.is(Blocks.DAMAGED_ANVIL)) return 3;
        if (y <= 80 && (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH)
                || s.is(Blocks.SOUL_WALL_TORCH) || s.is(Blocks.REDSTONE_TORCH) || s.is(Blocks.REDSTONE_WALL_TORCH)
                || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN))) return 2;
        if (y <= 80 && (s.is(Blocks.RAIL) || s.is(Blocks.POWERED_RAIL) || s.is(Blocks.DETECTOR_RAIL)
                || s.is(Blocks.ACTIVATOR_RAIL) || s.is(Blocks.REDSTONE_WIRE) || s.is(Blocks.REPEATER)
                || s.is(Blocks.COMPARATOR) || s.is(Blocks.LEVER))) return 2;
        return 0;
    }

    public void renderHud(Minecraft client, GuiGraphics g) {
        if (!isEnabled() || !FeatureAccess.allowed(client) || client.options.hideGui) return;
        List<Detection> found = new ArrayList<>(renderDetections);
        found.sort(Comparator.comparingDouble(d -> distance(client, d)));
        int count = Math.min(3, found.size()), x = 12, y = 12, w = 226, h = 57 + count * 25;
        g.fill(x, y, x + w, y + h, 0xD9101420); g.fill(x, y, x + 3, y + h, 0xFFB493FF);
        g.fill(x + 3, y + 30, x + w, y + 31, 0x554E4269);
        g.drawString(client.font, Component.literal("CHUNK FINDER"), x + 12, y + 8, 0xFFF0E8FF, true);
        g.drawString(client.font, Component.literal("VISUALS / ACTIVE"), x + w - 112, y + 8, 0xFFA9E3CD, true);
        g.drawString(client.font, Component.literal("RADIUS " + simulationDistance() + " CHUNKS  ·  SENS " + sensitivity()),
                x + 12, y + 19, 0xFFB6BAC8, false);
        if (found.isEmpty()) {
            g.drawString(client.font, Component.literal("Scanning loaded chunks…"), x + 12, y + 39, 0xFFD1D5E0, false);
            g.drawString(client.font, Component.literal("Press K to adjust module settings"), x + 12, y + 52, 0xFF8E96A8, false);
            return;
        }
        int row = y + 38;
        for (int i = 0; i < count; i++, row += 25) {
            Detection d = found.get(i);
            boolean geode = d.kind() == Kind.GEODE;
            g.drawString(client.font, Component.literal(geode ? "AMETHYST GEODE" : "ACTIVITY CLUES"),
                    x + 12, row, geode ? 0xFFD7B7FF : 0xFFFFCE8D, true);
            g.drawString(client.font, Component.literal(String.format("%d, %d, %d  ·  %dm",
                    (int) d.x(), (int) d.y(), (int) d.z(), Math.round(distance(client, d)))),
                    x + 12, row + 11, 0xFFE1E4EE, false);
            g.drawString(client.font, Component.literal(geode ? d.evidence() + " budding blocks" : "signal " + d.evidence()),
                    x + w - 88, row, 0xFF9FA7B8, false);
        }
    }

    public void renderWorld(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || !FeatureAccess.allowed(client) || renderDetections.isEmpty()
                || context.consumers() == null) return;
        Vec3 camera = context.gameRenderer().getMainCamera().position();
        PoseStack matrices = context.matrices();
        VertexConsumer lines = context.consumers().getBuffer(
                net.minecraft.client.renderer.rendertype.ChunkFinderThroughWallLines.renderType());
        for (Detection detection : renderDetections) {
            int color = detection.kind() == Kind.GEODE ? 0xFFB493FF : 0xFFFFCE8D;
            AABB bounds = detection.bounds();
            for (int layer = 2; layer >= 0; layer--) {
                double padding = 0.025 + layer * 0.045;
                float alpha = 1.0F - layer * 0.22F;
                int layeredColor = ((int) (alpha * 255) << 24) | (color & 0x00FFFFFF);
                AABB outline = bounds.inflate(padding);
                ShapeRenderer.renderShape(matrices, lines,
                        Shapes.box(outline.minX, outline.minY, outline.minZ, outline.maxX, outline.maxY, outline.maxZ),
                        -camera.x, -camera.y, -camera.z, layeredColor, 1.0F);
            }
        }
    }

    private static double distance(Minecraft c, Detection d) {
        double x = c.player.getX() - d.x(), y = c.player.getY() - d.y(), z = c.player.getZ() - d.z();
        return Math.sqrt(x * x + y * y + z * z);
    }
    private static long square(int n) { return (long) n * n; }
    private record ChunkCoord(int x, int z) { }
    private record Detection(Kind kind, double x, double y, double z, int evidence, AABB bounds) { }
    private enum Kind { GEODE, ACTIVITY }
}
