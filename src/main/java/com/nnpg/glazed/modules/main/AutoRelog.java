package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * AutoRelog
 * Otomatis disconnect lalu join balik ke server (default quansmp.xyz)
 * begitu player menyentuh ketinggian (Y) tertentu (rentang y0 sampai bedrock/-64).
 */
public class AutoRelog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> triggerHeight = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-height")
        .description("Ketinggian (Y) yang memicu auto relog. Bisa di-drag dari 0 sampai bedrock (-64).")
        .defaultValue(-58)
        .min(-64)
        .max(0)
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
        .min(0)
        .max(200)
        .sliderMin(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<String> serverIp = sgGeneral.add(new StringSetting.Builder()
        .name("server-ip")
        .description("Alamat server yang dituju saat join ulang.")
        .defaultValue("quansmp.xyz")
        .build()
    );

    private final Setting<Integer> serverPort = sgGeneral.add(new IntSetting.Builder()
        .name("server-port")
        .description("Port server yang dituju saat join ulang.")
        .defaultValue(25565)
        .min(1)
        .max(65535)
        .build()
    );

    private boolean pendingReconnect = false;
    private int delayCounter = 0;

    public AutoRelog() {
        super(GlazedAddon.CATEGORY, "auto-relog", "Keluar dan join ulang otomatis saat mencapai ketinggian tertentu.");
    }

    @Override
    public void onActivate() {
        pendingReconnect = false;
        delayCounter = 0;
    }

    @Override
    public void onDeactivate() {
        pendingReconnect = false;
        delayCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        if (!pendingReconnect) {
            double y = mc.player.getY();
            boolean hit = belowOrEqual.get()
                ? y <= triggerHeight.get()
                : y >= triggerHeight.get();

            if (hit) {
                pendingReconnect = true;
                delayCounter = 0;

                info("Auto Relog: ketinggian terpicu (Y=" + String.format("%.1f", y) + "), disconnecting...");
                mc.level.disconnect(Component.empty());
            }
            return;
        }

        delayCounter++;
        if (delayCounter >= rejoinDelayTicks.get()) {
            pendingReconnect = false;
            reconnect();
        }
    }

    private void reconnect() {
        try {
            ServerAddress address = new ServerAddress(serverIp.get(), serverPort.get());
            ConnectScreen.startConnecting(new TitleScreen(), mc, address, null, false, null);
            info("Auto Relog: mencoba join ulang ke " + serverIp.get());
        } catch (Exception e) {
            error("Auto Relog: gagal reconnect - " + e.getMessage());
        }
    }
}
