package com.liokasanovich.gun_management.client.texture;

import com.liokasanovich.gun_management.GunManagement;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.ModrinthClient;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Asynchronously loads and caches PNG icons from local gunpacks and remote Modrinth URLs
 * as Minecraft {@link DynamicTexture} instances.
 */
public final class PackIconCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackIconCache.class);
    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private PackIconCache() {
    }

    @Nullable
    public static ResourceLocation getOrLoadLocal(GunpackInfo pack) {
        String key = "local:" + pack.path().toAbsolutePath().normalize();
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (!IN_FLIGHT.add(key)) {
            return null;
        }

        CompletableFuture.supplyAsync(() -> readLocalIconBytes(pack))
                .thenAccept(bytes -> {
                    if (bytes == null) {
                        IN_FLIGHT.remove(key);
                        return;
                    }
                    registerOnMainThread(key, bytes);
                });
        return null;
    }

    @Nullable
    public static ResourceLocation getOrLoadRemote(@Nullable String iconUrl) {
        if (iconUrl == null || iconUrl.isBlank()) {
            return null;
        }
        String key = "remote:" + iconUrl;
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (!IN_FLIGHT.add(key)) {
            return null;
        }

        ModrinthClient.downloadBytesAsync(iconUrl)
                .thenAccept(bytes -> registerOnMainThread(key, bytes))
                .exceptionally(ex -> {
                    IN_FLIGHT.remove(key);
                    LOGGER.debug("Failed to fetch remote icon {}: {}", iconUrl, ex.getMessage());
                    return null;
                });
        return null;
    }

    private static void registerOnMainThread(String key, byte[] bytes) {
        Minecraft.getInstance().execute(() -> {
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                NativeImage image = NativeImage.read(in);
                DynamicTexture texture = new DynamicTexture(image);
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        GunManagement.MODID, "pack_icon_" + COUNTER.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(id, texture);
                CACHE.put(key, id);
            } catch (Exception e) {
                LOGGER.debug("Failed to decode pack icon for {}: {}", key, e.getMessage());
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    private static byte @Nullable [] readLocalIconBytes(GunpackInfo pack) {
        List<String> candidates = buildIconCandidates(pack.namespace(), pack.logoPath());
        if (pack.directory()) {
            for (String rel : candidates) {
                Path p = pack.path().resolve(rel);
                if (Files.isRegularFile(p)) {
                    try {
                        return Files.readAllBytes(p);
                    } catch (IOException ignored) {
                    }
                }
            }
            return null;
        }

        try (ZipFile zip = new ZipFile(pack.path().toFile())) {
            for (String rel : candidates) {
                ZipEntry entry = zip.getEntry(rel);
                if (entry != null && !entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static List<String> buildIconCandidates(String namespace, @Nullable String logoPath) {
        List<String> list = new ArrayList<>();
        if (logoPath != null && !logoPath.isBlank()) {
            String ns = namespace;
            String path = logoPath;
            int colon = logoPath.indexOf(':');
            if (colon > 0) {
                ns = logoPath.substring(0, colon);
                path = logoPath.substring(colon + 1);
            }
            if (!path.startsWith("textures/") && !path.startsWith("assets/")) {
                list.add("assets/" + ns + "/textures/" + path);
            }
            if (!path.startsWith("assets/")) {
                list.add("assets/" + ns + "/" + path);
            } else {
                list.add(path);
            }
        }
        list.add("assets/" + namespace + "/icon.png");
        list.add("icon.png");
        list.add("pack.png");
        return list;
    }
}
