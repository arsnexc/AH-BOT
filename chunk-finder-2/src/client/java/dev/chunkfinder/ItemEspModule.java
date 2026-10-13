package dev.chunkfinder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ItemEspModule extends Module {
    private static final int BLOCKS_PER_TICK = 8192;
    private static final int MAX_RENDERED_BLOCKS = 48;
    private static final int MAX_MATCHES_PER_CHUNK = 128;
    private static final long DISCOVERY_INTERVAL_TICKS = 40;

    private final ItemEspSettings settings = ItemEspSettings.load();
    private final ArrayDeque<ChunkCoord> queue = new ArrayDeque<>();
    private final Map<ChunkCoord, List<BlockPos>> results = new HashMap<>();
    private volatile List<BlockPos> renderBlocks = List.of();
    private Block targetBlock = resolveBlock(settings.blockId());
    private ClientLevel lastLevel;
    private ChunkScan activeScan;
    private long clientTick;
    private long nextDiscoveryTick;
    private int queueX = Integer.MIN_VALUE;
    private int queueZ = Integer.MIN_VALUE;
    private int queueRadius = -1;
    private int loadedChunks;
    private String scanStatus = "Waiting for world";

    public ItemEspModule() {
        super("Block ESP", ModuleCategory.VISUALS, false);
        super.setEnabled(settings.enabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        settings.setEnabled(enabled);
        if (!enabled) reset("Module is off");
    }

    public boolean tracesEnabled() { return settings.traces(); }
    public boolean blockHighlightEnabled() { return settings.blockHighlight(); }
    public int searchRadius() { return settings.searchRadius(); }
    public Block targetBlock() { return targetBlock; }
    public String targetBlockId() { return BuiltInRegistries.BLOCK.getKey(targetBlock).toString(); }
    public String targetBlockName() { return targetBlock.getName().getString(); }
    public int detectedCount() { return renderBlocks.size(); }
    public int scannedChunkCount() { return results.size(); }
    public int loadedChunkCount() { return loadedChunks; }
    public String scanStatus() { return scanStatus; }

    public void setTracesEnabled(boolean enabled) { settings.setTraces(enabled); }
    public void setBlockHighlightEnabled(boolean enabled) { settings.setBlockHighlight(enabled); }

    public void setSearchRadius(int radius) {
        settings.setSearchRadius(radius);
        queueRadius = -1;
        queue.clear();
        activeScan = null;
        results.clear();
        publishResults();
    }

    public void setTargetBlock(String blockId) {
        Block next = resolveBlock(blockId);
        if (next == null || next == Blocks.AIR || next == targetBlock) return;
        targetBlock = next;
        settings.setBlockId(BuiltInRegistries.BLOCK.getKey(next).toString());
        queueRadius = -1;
        queue.clear();
        activeScan = null;
        results.clear();
        publishResults();
        scanStatus = "Target changed; scan queued";
    }

    public void requestRescan() {
        if (!isEnabled()) {
            scanStatus = "Enable module first";
            return;
        }
        queue.clear();
        activeScan = null;
        results.clear();
        queueRadius = -1;
        nextDiscoveryTick = 0;
        scanStatus = "Rescan queued";
        publishResults();
    }

    public void tick(Minecraft client) {
        if (!isEnabled()) return;
        if (client == null || client.player == null || client.level == null) {
            reset("Waiting for world");
            return;
        }

        ClientLevel level = client.level;
        clientTick++;
        if (level != lastLevel) {
            reset("Loading dimension");
            lastLevel = level;
        }

        pruneUnloadedOrChanged(level);
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        int radius = settings.searchRadius();
        int moveThreshold = Math.max(1, radius / 2);
        boolean moved = queueX == Integer.MIN_VALUE
                || Math.abs(centerX - queueX) >= moveThreshold
                || Math.abs(centerZ - queueZ) >= moveThreshold;
        if (moved || radius != queueRadius
                || (queue.isEmpty() && activeScan == null && clientTick >= nextDiscoveryTick)) {
            scheduleScan(level, centerX, centerZ, radius);
        }
        scanLoadedBlocks(level);
    }

    private void scheduleScan(ClientLevel level, int centerX, int centerZ, int radius) {
        queue.clear();
        activeScan = null;
        List<ChunkCoord> chunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) chunks.add(new ChunkCoord(x, z));
        }
        chunks.sort(Comparator.comparingLong(c -> square(c.x() - centerX) + square(c.z() - centerZ)));

        int probeY = clampY(level, 0);
        int loaded = 0;
        for (ChunkCoord chunk : chunks) {
            BlockPos probe = new BlockPos(chunk.x() * 16 + 8, probeY, chunk.z() * 16 + 8);
            if (!level.hasChunkAt(probe)) continue;
            loaded++;
            if (!results.containsKey(chunk)) queue.addLast(chunk);
        }

        results.keySet().removeIf(c -> Math.abs(c.x() - centerX) > radius
                || Math.abs(c.z() - centerZ) > radius);
        loadedChunks = loaded;
        queueX = centerX;
        queueZ = centerZ;
        queueRadius = radius;
        nextDiscoveryTick = clientTick + DISCOVERY_INTERVAL_TICKS;
        scanStatus = loaded == 0 ? "Waiting for loaded chunks" : "Scanning loaded chunks";
        publishResults();
    }

    private void scanLoadedBlocks(ClientLevel level) {
        int minY = level.getMinY();
        int maxY = level.getMaxY();
        int blocksPerChunk = 16 * 16 * Math.max(0, maxY - minY);
        if (blocksPerChunk == 0) {
            scanStatus = "World has no scan height";
            return;
        }

        int budget = BLOCKS_PER_TICK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0) {
            if (activeScan == null) {
                ChunkCoord next = queue.pollFirst();
                if (next == null) break;
                BlockPos probe = new BlockPos(next.x() * 16 + 8, clampY(level, 0), next.z() * 16 + 8);
                if (!level.hasChunkAt(probe)) continue;
                activeScan = new ChunkScan(next, blocksPerChunk);
            }

            ChunkScan scan = activeScan;
            int amount = Math.min(budget, scan.totalBlocks() - scan.cursor());
            for (int i = 0; i < amount; i++) {
                int index = scan.cursor() + i;
                int localY = index / 256;
                int localXZ = index % 256;
                int x = scan.chunk().x() * 16 + localXZ / 16;
                int z = scan.chunk().z() * 16 + localXZ % 16;
                int y = minY + localY;
                if (scan.found().size() < MAX_MATCHES_PER_CHUNK
                        && level.getBlockState(pos.set(x, y, z)).is(targetBlock)) {
                    scan.found().add(new BlockPos(x, y, z));
                }
            }
            scan.advance(amount);
            budget -= amount;
            if (scan.cursor() >= scan.totalBlocks()) {
                results.put(scan.chunk(), List.copyOf(scan.found()));
                activeScan = null;
                publishResults();
            }
        }

        if (queue.isEmpty() && activeScan == null) {
            scanStatus = loadedChunks == 0 ? "Waiting for loaded chunks"
                    : renderBlocks.isEmpty() ? "No matches in loaded chunks" : "Matches found";
            nextDiscoveryTick = clientTick + DISCOVERY_INTERVAL_TICKS;
        } else {
            scanStatus = "Scanning loaded chunks";
        }
    }

    private void pruneUnloadedOrChanged(ClientLevel level) {
        boolean changed = false;
        var iterator = results.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkCoord, List<BlockPos>> entry = iterator.next();
            ChunkCoord chunk = entry.getKey();
            BlockPos probe = new BlockPos(chunk.x() * 16 + 8, clampY(level, 0), chunk.z() * 16 + 8);
            if (!level.hasChunkAt(probe)) {
                iterator.remove();
                changed = true;
                continue;
            }
            List<BlockPos> oldBlocks = entry.getValue();
            List<BlockPos> liveBlocks = oldBlocks.stream()
                    .filter(pos -> level.getBlockState(pos).is(targetBlock))
                    .toList();
            if (liveBlocks.size() != oldBlocks.size()) {
                entry.setValue(liveBlocks);
                changed = true;
            }
        }
        if (changed) publishResults();
    }

    private void reset(String status) {
        queue.clear();
        activeScan = null;
        results.clear();
        loadedChunks = 0;
        queueX = Integer.MIN_VALUE;
        queueZ = Integer.MIN_VALUE;
        queueRadius = -1;
        nextDiscoveryTick = 0;
        lastLevel = null;
        scanStatus = status;
        publishResults();
    }

    private void publishResults() {
        List<BlockPos> snapshot = new ArrayList<>();
        for (List<BlockPos> chunk : results.values()) snapshot.addAll(chunk);
        renderBlocks = List.copyOf(snapshot);
    }

    public void renderHud(Minecraft client, GuiGraphics graphics) {
        if (!isEnabled() || client.options.hideGui || client.player == null || client.level == null) return;
        int width = 230;
        List<BlockPos> found = new ArrayList<>(renderBlocks);
        found.sort(Comparator.comparingDouble(pos -> client.player.blockPosition().distSqr(pos)));
        int count = Math.min(3, found.size());
        int height = count == 0 ? 66 : 43 + count * 18;
        int x = client.getWindow().getGuiScaledWidth() - width - 12;
        int y = client.getWindow().getGuiScaledHeight() - height - 12;

        graphics.fill(x, y, x + width, y + height, 0xD9101420);
        graphics.fill(x, y, x + 3, y + height, 0xFFFFCE8D);
        graphics.drawString(client.font, Component.literal("ITEM ESP"), x + 12, y + 8, 0xFFFFE9C8, true);
        String targetName = targetBlockName();
        if (client.font.width(targetName) > 118) targetName = client.font.substrByWidth(Component.literal(targetName), 118).getString();
        graphics.drawString(client.font, Component.literal(targetName), x + width - client.font.width(targetName) - 12,
                y + 8, 0xFFFFCE8D, false);

        if (count == 0) {
            graphics.drawString(client.font, Component.literal(scanStatus), x + 12, y + 27, 0xFFE1E4EE, false);
            String progress = "Loaded " + scannedChunkCount() + "/" + loadedChunks
                    + " chunks  ·  radius " + searchRadius();
            graphics.drawString(client.font, Component.literal(progress), x + 12, y + 43, 0xFF9FA7B8, false);
        } else {
            for (int i = 0; i < count; i++) {
                BlockPos pos = found.get(i);
                int rowY = y + 26 + i * 18;
                String coordinates = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
                int distance = (int) Math.sqrt(client.player.blockPosition().distSqr(pos));
                graphics.drawString(client.font, Component.literal(coordinates), x + 12, rowY, 0xFFE1E4EE, false);
                graphics.drawString(client.font, Component.literal(distance + "m"), x + width - 32, rowY,
                        0xFF9FA7B8, false);
            }
        }
    }

    public void renderWorld(WorldRenderContext context) {
        if (!isEnabled() || (!tracesEnabled() && !blockHighlightEnabled()) || context.consumers() == null) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || renderBlocks.isEmpty()) return;

        Vec3 camera = context.gameRenderer().getMainCamera().position();
        List<BlockPos> visible = new ArrayList<>(renderBlocks);
        visible.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(camera)));
        if (visible.size() > MAX_RENDERED_BLOCKS) visible = visible.subList(0, MAX_RENDERED_BLOCKS);

        PoseStack matrices = context.matrices();
        PoseStack.Pose pose = matrices.last();
        VertexConsumer lines = context.consumers().getBuffer(
                net.minecraft.client.renderer.rendertype.ChunkFinderThroughWallLines.renderType());
        for (BlockPos pos : visible) {
            if (blockHighlightEnabled()) {
                AABB bounds = new AABB(pos).inflate(0.035);
                ShapeRenderer.renderShape(matrices, lines,
                        Shapes.box(bounds.minX - pos.getX(), bounds.minY - pos.getY(), bounds.minZ - pos.getZ(),
                                bounds.maxX - pos.getX(), bounds.maxY - pos.getY(), bounds.maxZ - pos.getZ()),
                        pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z,
                        0xFFFFCE8D, 1.6F);
            }
            if (tracesEnabled()) {
                Vec3 target = Vec3.atCenterOf(pos).subtract(camera);
                lines.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(255, 206, 141, 220)
                        .setNormal(pose, 0.0F, 1.0F, 0.0F).setLineWidth(2.0F);
                lines.addVertex(pose, (float) target.x, (float) target.y, (float) target.z)
                        .setColor(255, 206, 141, 220).setNormal(pose, 0.0F, 1.0F, 0.0F).setLineWidth(2.0F);
            }
        }
    }

    private static Block resolveBlock(String rawId) {
        if (rawId == null || rawId.isBlank()) return Blocks.DIAMOND_ORE;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block != Blocks.AIR && BuiltInRegistries.BLOCK.getKey(block).toString().equals(rawId)) return block;
        }
        return Blocks.DIAMOND_ORE;
    }

    private static int clampY(ClientLevel level, int requested) {
        return Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, requested));
    }

    private static long square(int value) { return (long) value * value; }

    private record ChunkCoord(int x, int z) { }

    private static final class ChunkScan {
        private final ChunkCoord chunk;
        private final int totalBlocks;
        private final List<BlockPos> found = new ArrayList<>();
        private int cursor;

        private ChunkScan(ChunkCoord chunk, int totalBlocks) {
            this.chunk = chunk;
            this.totalBlocks = totalBlocks;
        }

        private ChunkCoord chunk() { return chunk; }
        private int totalBlocks() { return totalBlocks; }
        private List<BlockPos> found() { return found; }
        private int cursor() { return cursor; }
        private void advance(int amount) { cursor += amount; }
    }
}
