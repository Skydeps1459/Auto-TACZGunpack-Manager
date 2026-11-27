package com.liokasanovich.gun_management;

import com.liokasanovich.gun_management.command.GunManagerCommand;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import com.liokasanovich.gun_management.network.PacketSyncGunpacks;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

@Mod(GunManagement.MODID)
public class GunManagement {
    public static final String MODID = "gun_management";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GunManagement() {
        GunpackOperator.applyPendingOperations();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PacketSyncGunpacks::register);
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
                        PacketSyncGunpacks.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> serverPlayer),
                                new PacketSyncGunpacks(entries)));
            }
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        GunManagerCommand.register(event.getDispatcher());
    }
}
