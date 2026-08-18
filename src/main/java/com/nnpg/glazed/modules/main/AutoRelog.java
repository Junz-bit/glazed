package com.nnpg.glazed.modules.main;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * AutoRelog
 * Keluar dari server lalu otomatis join lagi begitu player menyentuh
 * ketinggian (Y) yang sudah ditentukan (rentang y0 sampai bedrock).
 *
 * CATATAN PENYESUAIAN:
 * - Ganti "Categories.Combat" / "Categories.Main" di bawah dengan kategori
 *   "Main" milik addon Glazed kamu (biasanya ada enum/kelas Category custom
 *   di com.nnpg.glazed, misalnya GlazedAddon.CATEGORY atau sejenisnya).
 * - Import Keybind & event class di atas mengikuti struktur Meteor Client
 *   umum; sesuaikan path package kalau versi meteor-client kamu berbeda
 *   (mis. meteordevelopment.meteorclient.utils.misc.Keybind pada beberapa versi).
 */
public class AutoRelog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> triggerHeight = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-height")
        .description("Ketinggian (Y) yang memicu auto relog. Bisa di-drag dari 0 sampai bedrock (-64).")
        .defaultValue(-58)
        .range(-64, 0)
        .sliderMin(-64)
        .sliderMax(0)
        .build()
    );

    private final Setting<Boolean> belowOrEqual = sgGeneral.add(new BoolSetting.Builder()
        .name("trigger-when-below-or-equal")
        .description("Jika ON: relog saat Y <= trigger-height. Jika OFF: relog saat Y >= trigger-height.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rejoinDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rejoin-delay-ticks")
        .description("Jeda (dalam tick) sebelum mencoba join ulang setelah disconnect.")
        .defaultValue(40)
        .range(0, 200)
        .sliderMin(0)
        .sliderMax(200)
        .build()
    );

    private boolean pendingReconnect = false;
    private int delayCounter = 0;
    private ServerInfo lastServer = null;

    public AutoRelog() {
        // Ganti Categories.Main sesuai kategori addon Glazed kamu
        super(Categories.Main, "auto-relog", "Keluar dan join ulang otomatis saat mencapai ketinggian tertentu.");

        // Default keybind ala Meteor: contoh set default bind lewat GLFW keycode.
        // Sesuaikan dengan cara Glazed set default bind di modul lain (biasanya lewat
        // konstruktor Module atau lewat method setBind()/keybind field).
        // Contoh umum:
        // setBind(Keybind.fromKey(GLFW.GLFW_KEY_UNKNOWN)); // unbound by default
    }

    @Override
    public void onActivate() {
        pendingReconnect = false;
        delayCounter = 0;
        lastServer = MinecraftClient.getInstance().getCurrentServerEntry();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (!pendingReconnect) {
            double y = mc.player.getY();
            boolean hit = belowOrEqual.get()
                ? y <= triggerHeight.get()
                : y >= triggerHeight.get();

            if (hit) {
                lastServer = mc.getCurrentServerEntry();
                pendingReconnect = true;
                delayCounter = 0;

                // Putuskan koneksi dari server
                mc.execute(() -> {
                    if (mc.world != null) {
                        mc.world.disconnect();
                    }
                    mc.disconnect();
                });

                info("Auto Relog: ketinggian terpicu (Y=" + String.format("%.1f", y) + "), disconnecting...");
            }
            return;
        }

        // Menunggu delay sebelum reconnect
        delayCounter++;
        if (delayCounter >= rejoinDelayTicks.get()) {
            pendingReconnect = false;
            reconnect(mc);
        }
    }

    private void reconnect(MinecraftClient mc) {
        if (lastServer == null) {
            error("Auto Relog: tidak ada data server terakhir untuk reconnect.");
            return;
        }

        ServerAddress address = ServerAddress.parse(lastServer.address);

        mc.execute(() -> {
            ConnectScreen.connect(
                new TitleScreen(),
                mc,
                address,
                lastServer,
                false,
                null
            );
        });

        info("Auto Relog: mencoba join ulang ke " + lastServer.address);
    }

    @Override
    public void onDeactivate() {
        pendingReconnect = false;
        delayCounter = 0;
    }
}
