package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * AutoRelog
 * Otomatis disconnect lalu join balik ke server yang sama begitu
 * player menyentuh ketinggian (Y) tertentu (rentang y0 sampai bedrock/-64).
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

    private boolean pendingReconnect = false;
    private int delayCounter = 0;
    private ServerData lastServer = null;

    public AutoRelog() {
        super(GlazedAddon.CATEGORY, "auto-relog", "Keluar dan join ulang otomatis saat mencapai ketinggian tertentu.");
    }

    @Override
    public void onActivate() {
        pendingReconnect = false;
        delayCounter = 0;
        lastServer = mc.getCurrentServer();
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
                ServerData server = mc.getCurrentServer();
                if (server == null) {
                    error("Auto Relog: tidak terhubung ke server, tidak bisa relog.");
                    return;
                }

                lastServer = server;
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
        if (lastServer == null) {
            error("Auto Relog: tidak ada data server terakhir untuk reconnect.");
            return;
        }

        ServerAddress address = ServerAddress.parse(lastServer.ip);
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, lastServer);

        info("Auto Relog: mencoba join ulang ke " + lastServer.ip);
    }
}
