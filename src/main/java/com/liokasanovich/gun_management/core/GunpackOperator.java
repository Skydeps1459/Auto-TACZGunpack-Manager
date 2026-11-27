package com.liokasanovich.gun_management.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tacz.guns.resource.CommonAssetsManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handles enabling/disabling gunpacks (including Windows file-lock fallback via {@code PENDING_RESTART})
 * and triggering TaCZ's native pack reload.
 */
public final class GunpackOperator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackOperator.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PENDING_MAP_TYPE = new TypeToken<Map<String, Boolean>>() {}.getType();

    public enum Status {
        SUCCESS,
        ALREADY_IN_STATE,
        PENDING_RESTART,
        FAILED
    }

    public record OperationResult(Status status, Path targetPath, String message) {
    }

    private GunpackOperator() {
    }

    public static OperationResult setEnabled(GunpackInfo pack, boolean enable) {
        return setEnabled(GunpackPaths.getTaczDir(), pack, enable);
    }

    public static OperationResult setEnabled(Path taczDir, GunpackInfo pack, boolean enable) {
        if (pack.enabled() == enable) {
            return new OperationResult(Status.ALREADY_IN_STATE, pack.path(), "Gunpack is already in the requested state.");
        }

        try {
            GunpackPaths.ensureDirectories(taczDir);
        } catch (IOException e) {
            return new OperationResult(Status.FAILED, pack.path(), "Failed to prepare gunpack directories: " + e.getMessage());
        }

        Path source = pack.path();
        if (!Files.exists(source)) {
            return new OperationResult(Status.FAILED, source, "Source gunpack does not exist: " + source);
        }

        Path destDir = enable ? taczDir : taczDir.resolve(GunpackPaths.DISABLED_DIR_NAME);
        Path target = destDir.resolve(source.getFileName());

        try {
            movePack(source, target);
            removePendingOperation(taczDir, source.getFileName().toString());
            return new OperationResult(Status.SUCCESS, target, enable ? "Enabled " + pack.namespace() : "Disabled " + pack.namespace());
        } catch (FileSystemException lockEx) {
            // On Windows, open ZipFile / audio handles may hold a lock on the file until reload or restart.
            LOGGER.warn("File locked while moving {} -> {}: {}", source, target, lockEx.getMessage());
            recordPendingOperation(taczDir, source.getFileName().toString(), enable);
            return new OperationResult(
                    Status.PENDING_RESTART,
                    source,
                    "File is locked by the system; queued to " + (enable ? "enable" : "disable") + " on next restart."
            );
        } catch (IOException e) {
            LOGGER.error("Failed to move gunpack {} -> {}", source, target, e);
            return new OperationResult(Status.FAILED, source, "Failed to move gunpack: " + e.getMessage());
        }
    }

    private static void movePack(Path source, Path target) throws IOException {
        if (Files.exists(target) && !Files.isSameFile(source, target)) {
            throw new IOException("A gunpack with the same name already exists at " + target);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Executes any pending enable/disable moves queued from a previous session.
     * Should be called as early as possible during mod initialization before TaCZ scans packs.
     */
    public static void applyPendingOperations() {
        applyPendingOperations(GunpackPaths.getTaczDir());
    }

    public static void applyPendingOperations(Path taczDir) {
        Path pendingFile = taczDir.resolve(GunpackPaths.PENDING_OPS_FILE_NAME);
        if (!Files.isRegularFile(pendingFile)) {
            return;
        }

        Map<String, Boolean> pending = readPendingMap(pendingFile);
        if (pending.isEmpty()) {
            deleteQuietly(pendingFile);
            return;
        }

        try {
            GunpackPaths.ensureDirectories(taczDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create gunpack directories while applying pending ops", e);
            return;
        }

        Path disabledDir = taczDir.resolve(GunpackPaths.DISABLED_DIR_NAME);
        Map<String, Boolean> remaining = new HashMap<>();

        for (Map.Entry<String, Boolean> entry : pending.entrySet()) {
            String fileName = entry.getKey();
            boolean enable = Boolean.TRUE.equals(entry.getValue());
            Path source = enable ? disabledDir.resolve(fileName) : taczDir.resolve(fileName);
            Path target = enable ? taczDir.resolve(fileName) : disabledDir.resolve(fileName);

            if (!Files.exists(source)) {
                continue;
            }
            try {
                movePack(source, target);
                LOGGER.info("Applied pending gunpack operation: {} -> {}", source, target);
            } catch (IOException e) {
                LOGGER.warn("Still unable to apply pending gunpack move for {}: {}", fileName, e.getMessage());
                remaining.put(fileName, enable);
            }
        }

        if (remaining.isEmpty()) {
            deleteQuietly(pendingFile);
        } else {
            writePendingMap(pendingFile, remaining);
        }
    }

    public static Set<Path> getPendingRestartPaths(Path taczDir) {
        Path pendingFile = taczDir.resolve(GunpackPaths.PENDING_OPS_FILE_NAME);
        if (!Files.isRegularFile(pendingFile)) {
            return Set.of();
        }
        Map<String, Boolean> pending = readPendingMap(pendingFile);
        if (pending.isEmpty()) {
            return Set.of();
        }
        Set<Path> paths = new HashSet<>();
        Path disabledDir = taczDir.resolve(GunpackPaths.DISABLED_DIR_NAME);
        for (Map.Entry<String, Boolean> entry : pending.entrySet()) {
            boolean enable = Boolean.TRUE.equals(entry.getValue());
            Path p = enable ? disabledDir.resolve(entry.getKey()) : taczDir.resolve(entry.getKey());
            paths.add(p.toAbsolutePath().normalize());
        }
        return paths;
    }

    /**
     * Triggers TaCZ's native pack reload on client or dedicated server.
     */
    public static CompletableFuture<Void> reloadAllPacks() {
        if (!ModList.get().isLoaded("tacz")) {
            LOGGER.warn("TaCZ is not loaded; skipping pack reload.");
            return CompletableFuture.completedFuture(null);
        }

        if (FMLEnvironment.dist == Dist.CLIENT) {
            return ClientReloadBridge.reload();
        } else {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (server.isSameThread()) {
                CommonAssetsManager.reloadAllPack();
                return CompletableFuture.completedFuture(null);
            }
            return server.submit(CommonAssetsManager::reloadAllPack);
        }
    }

    private static final class ClientReloadBridge {
        private static CompletableFuture<Void> reload() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

            // Step 1: If an integrated server is running, reload server datapacks/gunpacks first
            // and await completion so CommonGunIndex & ServerMessageSyncGunPack are up-to-date.
            CompletableFuture<Void> serverReload = CompletableFuture.completedFuture(null);
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                serverReload = server.submit(() -> {
                    var packRepo = server.getPackRepository();
                    packRepo.reload();
                    return server.reloadResources(packRepo.getSelectedIds());
                }).thenCompose(f -> f);
            }

            // Step 2: Reload client resource packs without blocking the render thread.
            return serverReload.thenCompose(ignored -> {
                CompletableFuture<CompletableFuture<Void>> scheduled = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        scheduled.complete(mc.reloadResourcePacks());
                    } catch (Throwable t) {
                        scheduled.completeExceptionally(t);
                    }
                });
                return scheduled.thenCompose(f -> f);
            }).thenRun(() -> mc.execute(() -> {
                // Step 3: Force-rebuild CreativeModeTabs so newly added gunpacks appear in-game immediately.
                if (mc.player != null && mc.level != null) {
                    var features = mc.player.connection.enabledFeatures();
                    boolean opTab = mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks();
                    var registries = mc.level.registryAccess();
                    net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(features, !opTab, registries);
                    net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(features, opTab, registries);
                }
            }));
        }
    }

    private static void recordPendingOperation(Path taczDir, String fileName, boolean enable) {
        Path pendingFile = taczDir.resolve(GunpackPaths.PENDING_OPS_FILE_NAME);
        Map<String, Boolean> map = readPendingMap(pendingFile);
        map.put(fileName, enable);
        writePendingMap(pendingFile, map);
    }

    private static void removePendingOperation(Path taczDir, String fileName) {
        Path pendingFile = taczDir.resolve(GunpackPaths.PENDING_OPS_FILE_NAME);
        if (!Files.isRegularFile(pendingFile)) {
            return;
        }
        Map<String, Boolean> map = readPendingMap(pendingFile);
        if (map.remove(fileName) != null) {
            if (map.isEmpty()) {
                deleteQuietly(pendingFile);
            } else {
                writePendingMap(pendingFile, map);
            }
        }
    }

    private static Map<String, Boolean> readPendingMap(Path pendingFile) {
        if (!Files.isRegularFile(pendingFile)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(pendingFile, StandardCharsets.UTF_8)) {
            Map<String, Boolean> parsed = GSON.fromJson(reader, PENDING_MAP_TYPE);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            LOGGER.warn("Failed to read pending ops file {}", pendingFile, e);
            return new HashMap<>();
        }
    }

    private static void writePendingMap(Path pendingFile, Map<String, Boolean> map) {
        try (Writer writer = Files.newBufferedWriter(pendingFile, StandardCharsets.UTF_8)) {
            GSON.toJson(map, PENDING_MAP_TYPE, writer);
        } catch (IOException e) {
            LOGGER.warn("Failed to write pending ops file {}", pendingFile, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
