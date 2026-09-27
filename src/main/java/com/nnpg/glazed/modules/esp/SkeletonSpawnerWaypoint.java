package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

import java.util.HashSet;
import java.util.Set;

public class SkeletonSpawnerWaypoint extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Only detect Skeleton Spawners at or above this Y level.")
        .defaultValue(0)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("beam")
        .description("Draw a vertical beam upward from each Skeleton Spawner.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> beamColor = sgRender.add(new ColorSetting.Builder()
        .name("beam-color")
        .description("Color of the Skeleton Spawner beam.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(beam::get)
        .build()
    );

    private final Set<BlockPos> skeletonSpawners = new HashSet<>();
    private int scanTicks;

    public SkeletonSpawnerWaypoint() {
        super(
            GlazedAddon.esp,
            "skeleton-spawner-waypoint",
            "Shows a vertical beam above Skeleton Spawners at or above the configured minimum Y."
        );
    }

    @Override
    public void onActivate() {
        skeletonSpawners.clear();
        scanTicks = 0;
        scanNearbyChunks();
    }

    @Override
    public void onDeactivate() {
        skeletonSpawners.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.level == null) return;

        ChunkPos chunkPos = event.chunk().getPos();

        skeletonSpawners.removeIf(pos -> new ChunkPos(pos).equals(chunkPos));

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            addIfSkeletonSpawner(blockEntity);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (++scanTicks >= 20) {
            scanTicks = 0;
            scanNearbyChunks();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null || !beam.get()) return;

        Color color = new Color(beamColor.get());

        for (BlockPos pos : skeletonSpawners) {
            if (pos.getY() < minY.get()) continue;

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            double topY = mc.level.getMaxBuildHeight();

            event.renderer.line(
                x, y, z,
                x, topY, z,
                color
            );
        }
    }

    private void scanNearbyChunks() {
        if (mc.player == null || mc.level == null) return;

        int radius = Math.max(2, mc.options.renderDistance().get() + 1);
        int centerX = mc.player.chunkPosition().x;
        int centerZ = mc.player.chunkPosition().z;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;

                for (BlockEntity blockEntity : mc.level.getChunk(chunkX, chunkZ).getBlockEntities().values()) {
                    addIfSkeletonSpawner(blockEntity);
                }
            }
        }
    }

    private void addIfSkeletonSpawner(BlockEntity blockEntity) {
        if (!(blockEntity instanceof SpawnerBlockEntity spawner)) return;

        BlockPos pos = spawner.getBlockPos();

        if (pos.getY() < minY.get()) {
            skeletonSpawners.remove(pos);
            return;
        }

        Entity renderedEntity = spawner.getSpawner().getRenderedEntity(mc.level, pos);

        if (renderedEntity != null && renderedEntity.getType() == EntityType.SKELETON) {
            skeletonSpawners.add(pos);
        } else {
            skeletonSpawners.remove(pos);
        }
    }
}
