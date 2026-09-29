package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AmethystESP extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("threshold").description("Connected amethyst blocks needed for a chunk to be marked.")
        .defaultValue(12).min(1).sliderRange(1, 100).build());

    private final Setting<Integer> simDistance = sgGeneral.add(new IntSetting.Builder()
        .name("sim-distance").description("Chunk radius to scan around you.")
        .defaultValue(8).min(1).sliderRange(1, 32).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y level to scan.")
        .defaultValue(-58).min(-64).sliderRange(-64, 320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y level to scan.")
        .defaultValue(30).min(-64).sliderRange(-64, 320).build());

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius").description("How far around the middle of a geode to look for unlit spawnable space.")
        .defaultValue(8).min(1).max(24).sliderRange(1, 24).build());

    private final Setting<Boolean> linkGeodes = sgGeneral.add(new BoolSetting.Builder()
        .name("link-geodes").description("Fill in the chunks between every pair of qualifying geodes.")
        .defaultValue(true).build());

    private final Setting<Integer> linkThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("link-threshold").description("Hits a chunk needs before it counts as a link anchor.")
        .defaultValue(12).min(1).max(100).sliderRange(1, 100)
        .visible(linkGeodes::get).build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications").description("Toast when a new geode is found.")
        .defaultValue(true).build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw lines to the amethyst blocks.")
        .defaultValue(true).build());

    private final Setting<Boolean> chunkBox = sgRender.add(new BoolSetting.Builder()
        .name("chunk-box").description("Draw a box around the whole marked chunk.")
        .defaultValue(true).build());

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("esp-color").description("Colour for the amethyst blocks.")
        .defaultValue(new SettingColor(180, 100, 255, 255)).build());

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color").description("Colour for the chunk box.")
        .defaultValue(new SettingColor(180, 100, 255, 60)).build());

    private final Setting<SettingColor> linkColor = sgRender.add(new ColorSetting.Builder()
        .name("link-color").description("Colour of the area drawn between linked geodes.")
        .defaultValue(new SettingColor(180, 100, 255, 40))
        .visible(linkGeodes::get).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the boxes are drawn.")
        .defaultValue(ShapeMode.Lines).build());

    private final Setting<Integer> maxBoxes = sgRender.add(new IntSetting.Builder()
        .name("max-boxes").description("Cap on rendered block boxes, to keep frames stable.")
        .defaultValue(400).min(1).sliderRange(50, 2000).build());

    private final Setting<Integer> maxTracers = sgRender.add(new IntSetting.Builder()
        .name("max-tracers").description("Cap on rendered tracers.")
        .defaultValue(80).min(1).sliderRange(10, 400).build());

    private final Setting<Boolean> waypoint = sgRender.add(new BoolSetting.Builder()
        .name("waypoint").description("Show a waypoint above each detected amethyst geode.")
        .defaultValue(true).build());

    private final Setting<Double> waypointScale = sgRender.add(new DoubleSetting.Builder()
        .name("waypoint-scale").description("Scale of the amethyst waypoint.")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 3.0)
        .visible(waypoint::get).build());

    private final Setting<Boolean> waypointTracer = sgRender.add(new BoolSetting.Builder()
        .name("waypoint-tracer").description("Draw a tracer from the player to each waypoint.")
        .defaultValue(true).visible(waypoint::get).build());

    private final Setting<SettingColor> waypointColor = sgRender.add(new ColorSetting.Builder()
        .name("waypoint-color").description("Color of the waypoint percentage and tracer.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(waypoint::get).build());

    private final Setting<SettingColor> waypointCountColor = sgRender.add(new ColorSetting.Builder()
        .name("waypoint-count-color").description("Color of the large amethyst cluster count.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(waypoint::get).build());

    private final Setting<Integer> maxWaypoints = sgRender.add(new IntSetting.Builder()
        .name("max-waypoints").description("Maximum number of amethyst waypoints rendered at once.")
        .defaultValue(40).min(1).sliderRange(1, 100)
        .visible(waypoint::get).build());

    private static final int MAX_HITS_PER_CHUNK = 9000;
    private static final int NEIGHBOUR_LIGHT = 4;
    private static final int PRUNE_EXTRA_CHUNKS = 6;
    private static final int SCAN_INTERVAL_TICKS = 10;

    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> markedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> linkedChunks = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Set<BlockPos>> foundPositions = new ConcurrentHashMap<>();
    private final Map<ChunkPos, WaypointData> waypointData = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<ChunkPos> scanQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean scannerRunning = new AtomicBoolean(false);
    private final LinkedBlockingQueue<ChunkPos> pendingToasts = new LinkedBlockingQueue<>();

    private ExecutorService scanExecutor;
    private int tickCounter;

    public AmethystESP() {
        super(GlazedAddon.esp, "amethyst-esp", "Finds amethyst geodes and marks the chunks holding them.");
    }

    @Override
    public void onActivate() {
        reset();
        scanExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Glazed-AmethystESP");
            thread.setDaemon(true);
            return thread;
        });
        enqueueAround();
    }

    @Override
    public void onDeactivate() {
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
        reset();
    }

    private void reset() {
        queuedChunks.clear();
        markedChunks.clear();
        linkedChunks.clear();
        foundPositions.clear();
        waypointData.clear();
        scanQueue.clear();
        pendingToasts.clear();
        scannerRunning.set(false);
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (++tickCounter % SCAN_INTERVAL_TICKS == 0) enqueueAround();
        drainScanQueue();
        pruneFarClusters();
        rebuildLinks();
        showPendingToasts();
    }

    private void enqueueAround() {
        if (mc.player == null) return;
        int radius = simDistance.get();
        ChunkPos center = mc.player.chunkPosition();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++)
                enqueueChunk(center.x + dx, center.z + dz);
    }

    private void enqueueChunk(int chunkX, int chunkZ) {
        long encoded = ChunkPos.asLong(chunkX, chunkZ);
        if (!queuedChunks.add(encoded)) return;
        scanQueue.add(new ChunkPos(chunkX, chunkZ));
    }

    private void drainScanQueue() {
        if (scanExecutor == null || scannerRunning.get() || scanQueue.isEmpty()) return;

        List<ChunkPos> batch = new ArrayList<>();
        scanQueue.drainTo(batch);
        if (batch.isEmpty()) return;

        for (ChunkPos pos : batch) queuedChunks.remove(ChunkPos.asLong(pos.x, pos.z));

        List<LevelChunk> chunks = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) chunks.add(chunk);
        }
        if (chunks.isEmpty()) return;

        scannerRunning.set(true);
        int minYValue = Math.min(minY.get(), maxY.get());
        int maxYValue = Math.max(minY.get(), maxY.get());
        int required = threshold.get();

        scanExecutor.submit(() -> {
            try {
                for (LevelChunk chunk : chunks) scanChunk(chunk, minYValue, maxYValue, required);
            } finally {
                scannerRunning.set(false);
            }
        });
    }

    private BlockPos findGeodeCentre(LevelChunk chunk, int fromY, int toY) {
        ChunkPos chunkPos = chunk.getPos();
        int originX = chunkPos.getMinBlockX();
        int originZ = chunkPos.getMinBlockZ();
        long sumX = 0, sumY = 0, sumZ = 0;
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = fromY; y <= toY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    cursor.set(originX + x, y, originZ + z);
                    BlockState state = chunk.getBlockState(cursor);
                    if (!state.is(Blocks.AMETHYST_BLOCK) && !state.is(Blocks.BUDDING_AMETHYST)) continue;
                    sumX += cursor.getX(); sumY += cursor.getY(); sumZ += cursor.getZ(); count++;
                }
            }
        }
        if (count == 0) return null;
        return new BlockPos((int) (sumX / count), (int) (sumY / count), (int) (sumZ / count));
    }

    private void scanChunk(LevelChunk chunk, int fromY, int toY, int required) {
        ChunkPos chunkPos = chunk.getPos();
        Level level = mc.level;
        if (level == null) { forget(chunkPos); return; }

        int bottom = Math.max(fromY, chunk.getMinY());
        int top = Math.min(toY, chunk.getMinY() + chunk.getHeight() - 1);
        BlockPos centre = findGeodeCentre(chunk, bottom, top);

        if (centre == null) { forget(chunkPos); return; }

        Set<BlockPos> hits = darkSpots(level, centre);
        if (hits.isEmpty()) { forget(chunkPos); return; }

        foundPositions.put(chunkPos, hits);
        waypointData.put(chunkPos, buildWaypoint(level, centre));

        if (hits.size() < required) {
            markedChunks.remove(chunkPos);
            return;
        }

        if (markedChunks.add(chunkPos) && notifications.get()) pendingToasts.add(chunkPos);
    }

    private WaypointData buildWaypoint(Level level, BlockPos centre) {
        int radius = Math.max(4, scanRadius.get());
        int totalGrowth = 0;
        int largeClusters = 0;
        int growthScore = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);

                    int stage = 0;
                    if (state.is(Blocks.SMALL_AMETHYST_BUD)) stage = 25;
                    else if (state.is(Blocks.MEDIUM_AMETHYST_BUD)) stage = 50;
                    else if (state.is(Blocks.LARGE_AMETHYST_BUD)) stage = 75;
                    else if (state.is(Blocks.AMETHYST_CLUSTER)) stage = 100;

                    if (stage == 0) continue;
                    totalGrowth++;
                    growthScore += stage;
                    if (state.is(Blocks.AMETHYST_CLUSTER)) largeClusters++;
                }
            }
        }

        int percentage = totalGrowth == 0 ? 0 :
            Math.max(0, Math.min(100, Math.round((float) growthScore / totalGrowth)));

        return new WaypointData(
            new Vector3d(centre.getX() + 0.5, centre.getY() + 1.5, centre.getZ() + 0.5),
            percentage,
            largeClusters
        );
    }

    private Set<BlockPos> darkSpots(Level level, BlockPos centre) {
        Set<BlockPos> hits = new HashSet<>();
        int radius = scanRadius.get();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (hits.size() >= MAX_HITS_PER_CHUNK) return hits;
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.is(Blocks.AIR) && !state.is(Blocks.AMETHYST_CLUSTER)) continue;
                    if (level.getBrightness(LightLayer.BLOCK, cursor) != 0) continue;

                    BlockPos darkAir = null;
                    boolean lit = false;

                    for (Direction direction : Direction.values()) {
                        neighbour.set(cursor.getX() + direction.getStepX(), cursor.getY() + direction.getStepY(), cursor.getZ() + direction.getStepZ());
                        int light = level.getBrightness(LightLayer.BLOCK, neighbour);
                        if (light > NEIGHBOUR_LIGHT) { lit = true; break; }
                        if (darkAir == null && light == 0 && level.getBlockState(neighbour).is(Blocks.AIR))
                            darkAir = neighbour.immutable();
                    }
                    if (lit || darkAir == null) continue;

                    for (Direction direction : Direction.values()) {
                        neighbour.set(darkAir.getX() + direction.getStepX(), darkAir.getY() + direction.getStepY(), darkAir.getZ() + direction.getStepZ());
                        if (level.getBrightness(LightLayer.BLOCK, neighbour) > NEIGHBOUR_LIGHT) { lit = true; break; }
                    }
                    if (!lit) hits.add(cursor.immutable());
                }
            }
        }
        return hits;
    }

    private void forget(ChunkPos pos) {
        foundPositions.remove(pos);
        waypointData.remove(pos);
        markedChunks.remove(pos);
    }

    private void rebuildLinks() {
        linkedChunks.clear();
        if (!linkGeodes.get()) return;

        int need = linkThreshold.get();
        List<ChunkPos> hot = new ArrayList<>();
        for (Map.Entry<ChunkPos, Set<BlockPos>> entry : foundPositions.entrySet())
            if (entry.getValue().size() >= need) hot.add(entry.getKey());

        if (hot.isEmpty()) return;
        linkedChunks.addAll(hot);

        for (int i = 0; i < hot.size(); i++) {
            for (int j = i + 1; j < hot.size(); j++) {
                ChunkPos a = hot.get(i), b = hot.get(j);
                for (int x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++)
                    for (int z = Math.min(a.z, b.z); z <= Math.max(a.z, b.z); z++)
                        linkedChunks.add(new ChunkPos(x, z));
            }
        }
    }

    private void pruneFarClusters() {
        if (mc.player == null) return;
        int radius = simDistance.get() + PRUNE_EXTRA_CHUNKS;
        ChunkPos center = mc.player.chunkPosition();
        foundPositions.keySet().removeIf(pos -> isOutside(pos, center, radius));
        markedChunks.removeIf(pos -> isOutside(pos, center, radius));
        linkedChunks.removeIf(pos -> isOutside(pos, center, radius));
        waypointData.keySet().removeIf(pos -> isOutside(pos, center, radius));
    }

    private static boolean isOutside(ChunkPos pos, ChunkPos center, int radius) {
        return Math.abs(pos.x - center.x) > radius || Math.abs(pos.z - center.z) > radius;
    }

    private void showPendingToasts() {
        ChunkPos pos = pendingToasts.poll();
        if (pos == null) return;
        Set<BlockPos> hits = foundPositions.get(pos);
        int count = hits == null ? 0 : hits.size();
        mc.getToastManager().addToast(new MeteorToast.Builder(title)
            .text("Geode found: " + count + " blocks at " + (pos.x * 16 + 8) + ", " + (pos.z * 16 + 8))
            .icon(Items.AMETHYST_CLUSTER).build());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        enqueueChunk(event.pos.getX() >> 4, event.pos.getZ() >> 4);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        Color blockColor = new Color(espColor.get());

        if (chunkBox.get()) {
            Color boxColor = new Color(chunkColor.get());
            int lowY = Math.min(minY.get(), maxY.get());
            int highY = Math.max(minY.get(), maxY.get());
            for (ChunkPos pos : markedChunks)
                event.renderer.box(pos.getMinBlockX(), lowY, pos.getMinBlockZ(), pos.getMaxBlockX() + 1, highY, pos.getMaxBlockZ() + 1, boxColor, boxColor, shapeMode.get(), 0);
        }

        if (linkGeodes.get() && !linkedChunks.isEmpty()) {
            Color areaColor = new Color(linkColor.get());
            int lowY = Math.min(minY.get(), maxY.get());
            int highY = Math.max(minY.get(), maxY.get());
            for (ChunkPos pos : linkedChunks) {
                if (markedChunks.contains(pos)) continue;
                event.renderer.box(pos.getMinBlockX(), lowY, pos.getMinBlockZ(), pos.getMaxBlockX() + 1, highY, pos.getMaxBlockZ() + 1, areaColor, areaColor, shapeMode.get(), 0);
            }
        }

        int boxes = 0;
        for (Set<BlockPos> positions : foundPositions.values()) {
            for (BlockPos pos : positions) {
                if (boxes++ >= maxBoxes.get()) break;
                event.renderer.box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, blockColor, blockColor, shapeMode.get(), 0);
            }
        }

        if (waypoint.get() && waypointTracer.get()) {
            Color tracerColor = new Color(waypointColor.get());
            int drawnWaypoints = 0;
            for (WaypointData data : waypointData.values()) {
                if (!markedChunks.contains(data.chunkPos) || drawnWaypoints++ >= maxWaypoints.get()) continue;
                event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    data.position.x, data.position.y, data.position.z, tracerColor);
            }
        }

        if (!tracers.get()) return;

        int drawn = 0;
        for (Set<BlockPos> positions : foundPositions.values()) {
            if (positions.isEmpty()) continue;
            if (drawn++ >= maxTracers.get()) return;

            double x = 0, y = 0, z = 0;
            for (BlockPos pos : positions) {
                x += pos.getX() + 0.5; y += pos.getY() + 0.5; z += pos.getZ() + 0.5;
            }
            int count = positions.size();
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                x / count, y / count, z / count, blockColor);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!waypoint.get() || mc.player == null || mc.level == null) return;

        TextRenderer text = TextRenderer.get();
        Color percentColor = new Color(waypointColor.get());
        Color countColor = new Color(waypointCountColor.get());
        int rendered = 0;

        for (WaypointData data : waypointData.values()) {
            if (!markedChunks.contains(data.chunkPos) || rendered++ >= maxWaypoints.get()) continue;
            if (!NametagUtils.to2D(data.position, waypointScale.get())) continue;

            NametagUtils.begin(data.position, event.drawContext);

            String percent = data.percentage + "%";
            String count = String.valueOf(data.largeClusters);
            double percentWidth = text.getWidth(percent, true);
            double countWidth = text.getWidth(count, true);
            double height = text.getHeight(true);

            text.beginBig();
            text.render(percent, -percentWidth / 2, -height * 1.9, percentColor, true);
            text.end();

            RenderUtils.drawItem(event.drawContext, Items.AMETHYST_CLUSTER.getDefaultInstance(),
                (int) (-countWidth / 2 - 13), (int) (height * 0.1), 1.5f, true);

            text.beginBig();
            text.render(count, 7 - countWidth / 2, height * 0.15, countColor, true);
            text.end();

            NametagUtils.end(event.drawContext);
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(markedChunks.size());
    }

    private static final class WaypointData {
        private final Vector3d position;
        private final int percentage;
        private final int largeClusters;
        private ChunkPos chunkPos;

        private WaypointData(Vector3d position, int percentage, int largeClusters) {
            this.position = position;
            this.percentage = percentage;
            this.largeClusters = largeClusters;
        }
    }
}
