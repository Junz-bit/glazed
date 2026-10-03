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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import java.util.ArrayDeque;
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

    private final Setting<Integer> scanIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("Ticks between chunk scans.")
        .defaultValue(5)
        .min(2)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> chunksPerScan = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-scan")
        .description("Maximum chunks scanned per scan cycle.")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
        .build()
    );

    private final Setting<Double> surfaceMarkerSize = sgRender.add(new DoubleSetting.Builder()
        .name("surface-marker-size")
        .description("Flat marker size on the surface directly above the geode.")
        .defaultValue(3.0)
        .min(1.0)
        .sliderMax(8.0)
        .build());

    private final Setting<SettingColor> surfaceMarkerColor = sgRender.add(new ColorSetting.Builder()
        .name("surface-marker-color")
        .description("Color of the flat surface marker.")
        .defaultValue(new SettingColor(170, 80, 255, 80))
        .build());

    private final Setting<SettingColor> waypointTracerColor = sgRender.add(new ColorSetting.Builder()
        .name("waypoint-tracer-color")
        .description("Color of the tracer to the surface marker.")
        .defaultValue(new SettingColor(80, 255, 120, 180))
        .build());

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

        tickCounter++;

        if (tickCounter % SCAN_INTERVAL_TICKS == 0) enqueueAround();

        if (tickCounter % scanIntervalTicks.get() == 0) {
            drainScanQueue();
        }

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
        if (mc.level == null) return;

        int limit = Math.max(1, chunksPerScan.get());
        int minYValue = Math.min(minY.get(), maxY.get());
        int maxYValue = Math.max(minY.get(), maxY.get());
        int required = threshold.get();

        scannerRunning.set(true);
        try {
            for (int i = 0; i < limit; i++) {
                ChunkPos pos = scanQueue.poll();
                if (pos == null) break;

                queuedChunks.remove(ChunkPos.asLong(pos.x, pos.z));

                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk != null) {
                    scanChunk(chunk, minYValue, maxYValue, required);
                }
            }
        } finally {
            scannerRunning.set(false);
        }
    }


    private BlockPos findGeodeCentre(LevelChunk chunk, int fromY, int toY) {
        ChunkPos chunkPos = chunk.getPos();
        int originX = chunkPos.getMinBlockX();
        int originZ = chunkPos.getMinBlockZ();

        Set<BlockPos> blocks = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int bottom = Math.max(fromY, chunk.getMinY());
        int top = Math.min(toY, chunk.getMinY() + chunk.getHeight() - 1);

        for (int y = bottom; y <= top; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    cursor.set(originX + x, y, originZ + z);
                    BlockState state = chunk.getBlockState(cursor);

                    if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)) {
                        blocks.add(cursor.immutable());
                    }
                }
            }
        }

        if (blocks.isEmpty()) return null;

        // Split separate amethyst formations instead of averaging every
        // amethyst block in the whole chunk.
        Set<BlockPos> remaining = new HashSet<>(blocks);
        List<BlockPos> largest = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            remaining.remove(seed);

            List<BlockPos> component = new ArrayList<>();
            queue.clear();
            queue.add(seed);

            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                component.add(pos);

                for (Direction direction : Direction.values()) {
                    BlockPos next = pos.relative(direction);
                    if (remaining.remove(next)) queue.add(next);
                }
            }

            if (component.size() > largest.size()) largest = component;
        }

        if (largest.isEmpty()) return null;

        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;

        for (BlockPos pos : largest) {
            sumX += pos.getX() + 0.5;
            sumY += pos.getY() + 0.5;
            sumZ += pos.getZ() + 0.5;
        }

        double cx = sumX / largest.size();
        double cy = sumY / largest.size();
        double cz = sumZ / largest.size();

        // Pick an actual geode block closest to the component centre.
        BlockPos best = largest.get(0);
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : largest) {
            double dx = pos.getX() + 0.5 - cx;
            double dy = pos.getY() + 0.5 - cy;
            double dz = pos.getZ() + 0.5 - cz;
            double distance = dx * dx + dy * dy + dz * dz;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }

        return best;
    }

    private void scanChunk(LevelChunk chunk, int fromY, int toY, int required) {
        ChunkPos chunkPos=chunk.getPos();
        int bottom=Math.max(fromY,chunk.getMinY()), top=Math.min(toY,chunk.getMinY()+chunk.getHeight()-1);
        BlockPos centre=findGeodeCentre(chunk,bottom,top);
        if(centre==null){forget(chunkPos);return;}
        Set<BlockPos> hits=new HashSet<>(); int radius=Math.max(4,scanRadius.get()); BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int dx=-radius;dx<=radius;dx++) for(int dy=-radius;dy<=radius;dy++) for(int dz=-radius;dz<=radius;dz++){
            cursor.set(centre.getX()+dx,centre.getY()+dy,centre.getZ()+dz); BlockState state=chunk.getBlockState(cursor);
            if(state.is(Blocks.AMETHYST_BLOCK)||state.is(Blocks.BUDDING_AMETHYST)) hits.add(cursor.immutable());
        }
        if(hits.size()<required){forget(chunkPos);return;}
        foundPositions.put(chunkPos,hits); waypointData.put(chunkPos,buildWaypoint(centre,chunkPos)); markedChunks.add(chunkPos);
    }

    private WaypointData buildWaypoint(BlockPos centre, ChunkPos chunkPos) {
        int radius = Math.max(4, scanRadius.get());
        int totalGrowth = 0;
        int largeClusters = 0;
        int growthScore = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = mc.level.getBlockState(cursor);

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

        int surfaceY = mc.level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            centre.getX(),
            centre.getZ()
        );

        return new WaypointData(
            new Vector3d(centre.getX() + 0.5, surfaceY + 0.02, centre.getZ() + 0.5),
            percentage,
            largeClusters,
            chunkPos
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

        int drawn = 0;
        for (WaypointData data : waypointData.values()) {
            if (!markedChunks.contains(data.chunkPos) || drawn++ >= maxWaypoints.get()) continue;

            double x = data.position.x;
            double y = data.position.y;
            double z = data.position.z;
            double half = surfaceMarkerSize.get() / 2.0;

            // Thin, flat surface square. No tall geode bounding box.
            Color markerColor = new Color(surfaceMarkerColor.get());
            event.renderer.box(
                x - half, y, z - half,
                x + half, y + 0.035, z + half,
                markerColor, markerColor, ShapeMode.Both, 0
            );

            if (waypointTracer.get()) {
                Color tracerColor = new Color(waypointTracerColor.get());
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    x, y + 0.04, z,
                    tracerColor
                );
            }
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
            String count = data.largeClusters + " clusters";

            double pw = text.getWidth(percent, true);
            double cw = text.getWidth(count, true);
            double fontH = text.getHeight(true);

            // Two separate top boxes: percentage and icon.
            float percentW = (float) Math.max(34.0, pw + 16.0);
            float iconW = 25.0f;
            float boxH = 22.0f;
            float gap = 5.0f;
            float rowW = percentW + gap + iconW;
            float rowLeft = -rowW / 2.0f;

            // Slightly transparent backing, with a little breathing room.
            event.drawContext.fill(
                (int) (rowLeft - 3),
                (int) (-boxH - 4),
                (int) (rowLeft + rowW + 3),
                (int) (-2),
                0x50000000
            );

            // Percentage box.
            event.drawContext.fill(
                (int) rowLeft,
                (int) (-boxH - 2),
                (int) (rowLeft + percentW),
                (int) 0,
                0x990F0F12
            );

            text.beginBig();
            text.render(
                percent,
                rowLeft + (percentW - pw) / 2.0,
                -boxH + 3.0,
                percentColor,
                true
            );
            text.end();

            // Icon box, separate from the percentage box.
            float iconLeft = rowLeft + percentW + gap;

            event.drawContext.fill(
                (int) iconLeft,
                (int) (-boxH - 2),
                (int) (iconLeft + iconW),
                (int) 0,
                0x990F0F12
            );

            RenderUtils.drawItem(
                event.drawContext,
                Items.AMETHYST_CLUSTER.getDefaultInstance(),
                (int) iconLeft + 4,
                (int) (-boxH + 3),
                1.0f,
                true
            );

            // Cluster count in its own box underneath, with a visible gap.
            float countW = (float) Math.max(58.0, cw + 16.0);
            float countTop = 5.0f;
            float countBottom = countTop + (float) fontH + 8.0f;

            event.drawContext.fill(
                (int) (-countW / 2.0f - 3),
                (int) countTop,
                (int) (countW / 2.0f + 3),
                (int) countBottom,
                0x990F0F12
            );

            text.beginBig();
            text.render(
                count,
                -cw / 2.0,
                countTop + 4.0,
                countColor,
                true
            );
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

        private WaypointData(Vector3d position, int percentage, int largeClusters, ChunkPos chunkPos) {
            this.position = position;
            this.percentage = percentage;
            this.largeClusters = largeClusters;
            this.chunkPos = chunkPos;
        }
    }
}
