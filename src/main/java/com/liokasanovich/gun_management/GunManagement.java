package com.liokasanovich.gun_management;

import com.liokasanovich.gun_management.command.GunManagerCommand;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import com.liokasanovich.gun_management.network.PacketSyncGunpacks;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(GunManagement.MODID)
public class GunManagement {
    public static final String MODID = "gun_management";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GunManagement(IEventBus modEventBus, ModContainer modContainer) {
        GunpackOperator.applyPendingOperations();

        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::registerPayloads);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                PacketSyncGunpacks.TYPE,
                PacketSyncGunpacks.STREAM_CODEC,
                PacketSyncGunpacks::handleClient
        );
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        var taczDir = GunpackPaths.getTaczDir();
        var pending = GunpackOperator.getPendingRestartPaths(taczDir);
        GunpackScanner.scanAllAsync(taczDir, pending).thenAccept(packs -> {
            var entries = packs.stream()
                    .filter(GunpackInfo::enabled)
                    .map(p -> new PacketSyncGunpacks.PackEntry(p.namespace(), p.displayName(), p.sha256()))
                    .toList();
            if (!entries.isEmpty()) {
                serverPlayer.server.execute(() ->
                        PacketDistributor.sendToPlayer(serverPlayer, new PacketSyncGunpacks(entries)));
            }
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GunManagerCommand.register(event.getDispatcher());
    }
}
