package com.liokasanovich.gun_management.network;

import com.liokasanovich.gun_management.GunManagement;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Server-to-client SimpleChannel packet carrying the server's active gunpack manifest (namespace + SHA-256).
 */
public record PacketSyncGunpacks(List<PackEntry> packs) {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(GunManagement.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public record PackEntry(String namespace, String displayName, String sha256) {
    }

    public record MismatchEntry(PackEntry serverPack, boolean hashMismatch) {
    }

    public static void register() {
        CHANNEL.registerMessage(
                0,
                PacketSyncGunpacks.class,
                PacketSyncGunpacks::encode,
                PacketSyncGunpacks::decode,
                PacketSyncGunpacks::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void encode(PacketSyncGunpacks msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.packs().size());
        for (PackEntry entry : msg.packs()) {
            buf.writeUtf(entry.namespace());
            buf.writeUtf(entry.displayName());
            buf.writeUtf(entry.sha256());
        }
    }

    public static PacketSyncGunpacks decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<PackEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new PackEntry(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        return new PacketSyncGunpacks(list);
    }

    public static void handle(PacketSyncGunpacks msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> ClientSyncBridge.handleSync(msg));
        ctx.setPacketHandled(true);
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
