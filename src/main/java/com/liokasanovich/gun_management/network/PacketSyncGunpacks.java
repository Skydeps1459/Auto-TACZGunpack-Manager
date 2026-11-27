package com.liokasanovich.gun_management.network;

import com.liokasanovich.gun_management.GunManagement;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-to-client payload carrying the server's active gunpack manifest (namespace + SHA-256).
 */
public record PacketSyncGunpacks(List<PackEntry> packs) implements CustomPacketPayload {
    public static final Type<PacketSyncGunpacks> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(GunManagement.MODID, "sync_gunpacks"));

    public record PackEntry(String namespace, String displayName, String sha256) {
        public static final StreamCodec<FriendlyByteBuf, PackEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PackEntry::namespace,
                ByteBufCodecs.STRING_UTF8, PackEntry::displayName,
                ByteBufCodecs.STRING_UTF8, PackEntry::sha256,
                PackEntry::new
        );
    }

    public static final StreamCodec<FriendlyByteBuf, PacketSyncGunpacks> STREAM_CODEC = StreamCodec.composite(
            PackEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), PacketSyncGunpacks::packs,
            PacketSyncGunpacks::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(PacketSyncGunpacks payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientSyncBridge.handleSync(payload));
    }

    public record MismatchEntry(PackEntry serverPack, boolean hashMismatch) {
    }

    private static final class ClientSyncBridge {
        private static void handleSync(PacketSyncGunpacks payload) {
            if (payload.packs().isEmpty()) {
                return;
            }
            Path taczDir = GunpackPaths.getTaczDir();
            Set<Path> pending = GunpackOperator.getPendingRestartPaths(taczDir);
            GunpackScanner.scanAllAsync(taczDir, pending).thenAccept(localPacks -> {
                Map<String, GunpackInfo> enabledByNs = localPacks.stream()
                        .filter(GunpackInfo::enabled)
                        .collect(Collectors.toMap(GunpackInfo::namespace, p -> p, (a, b) -> a));

                List<MismatchEntry> missing = new ArrayList<>();
                for (PackEntry serverPack : payload.packs()) {
                    GunpackInfo local = enabledByNs.get(serverPack.namespace());
                    if (local == null) {
                        missing.add(new MismatchEntry(serverPack, false));
                    } else if (!serverPack.sha256().isBlank()
                            && !local.sha256().isBlank()
                            && !serverPack.sha256().equalsIgnoreCase(local.sha256())) {
                        missing.add(new MismatchEntry(serverPack, true));
                    }
                }

                if (!missing.isEmpty()) {
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    mc.execute(() -> mc.setScreen(
                            new com.liokasanovich.gun_management.client.gui.SyncConfirmScreen(mc.screen, missing)));
                }
            });
        }
    }
}
