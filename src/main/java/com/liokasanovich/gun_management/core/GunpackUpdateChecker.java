package com.liokasanovich.gun_management.core;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks local {@code .zip} gunpacks against Modrinth by SHA-256 hash to detect newer versions.
 */
public final class GunpackUpdateChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackUpdateChecker.class);

    public record PackUpdate(
            GunpackInfo localPack,
            String projectId,
            String currentVersionNumber,
            ModrinthClient.ModrinthVersion latestVersion
    ) {
    }

    private static final Map<Path, PackUpdate> UPDATES_BY_PATH = new ConcurrentHashMap<>();
    private static final AtomicBoolean STARTUP_CHECKED = new AtomicBoolean(false);
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private GunpackUpdateChecker() {
    }

    public static void checkOnceOnStartupAsync() {
        if (STARTUP_CHECKED.compareAndSet(false, true)) {
            checkUpdatesAsync(GunpackPaths.getTaczDir());
        }
    }

    public static boolean isChecking() {
        return CHECKING.get();
    }

    public static int getUpdateCount() {
        return UPDATES_BY_PATH.size();
    }

    public static List<PackUpdate> getCachedUpdates() {
        return List.copyOf(UPDATES_BY_PATH.values());
    }

    @Nullable
    public static PackUpdate getUpdateFor(GunpackInfo pack) {
        if (pack == null) {
            return null;
        }
        return UPDATES_BY_PATH.get(pack.path().toAbsolutePath().normalize());
    }

    public static void clearUpdateFor(GunpackInfo pack) {
        if (pack != null) {
            UPDATES_BY_PATH.remove(pack.path().toAbsolutePath().normalize());
        }
    }

    public static CompletableFuture<List<PackUpdate>> checkUpdatesAsync(Path taczDir) {
        if (!CHECKING.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(getCachedUpdates());
        }

        Set<Path> pending = GunpackOperator.getPendingRestartPaths(taczDir);
        return GunpackScanner.scanAllAsync(taczDir, pending)
                .thenCompose(packs -> {
                    List<GunpackInfo> zipPacks = new ArrayList<>();
                    List<String> hashes = new ArrayList<>();
                    for (GunpackInfo pack : packs) {
                        if (!pack.directory() && pack.sha256() != null && !pack.sha256().isBlank()) {
                            zipPacks.add(pack);
                            hashes.add(pack.sha256().toLowerCase(Locale.ROOT));
                        }
                    }
                    if (hashes.isEmpty()) {
                        UPDATES_BY_PATH.clear();
                        return CompletableFuture.completedFuture(List.<PackUpdate>of());
                    }

                    return ModrinthClient.lookupVersionsBySha256BatchAsync(hashes)
                            .thenCompose(matchedByHash -> resolveLatestForMatched(zipPacks, matchedByHash));
                })
                .whenComplete((res, err) -> {
                    CHECKING.set(false);
                    if (err != null) {
                        LOGGER.warn("Gunpack update check failed: {}", err.getMessage());
                    }
                });
    }

    private static CompletableFuture<List<PackUpdate>> resolveLatestForMatched(
            List<GunpackInfo> zipPacks,
            Map<String, ModrinthClient.ModrinthVersion> matchedByHash
    ) {
        if (matchedByHash.isEmpty()) {
            UPDATES_BY_PATH.clear();
            return CompletableFuture.completedFuture(List.of());
        }

        Map<String, CompletableFuture<Optional<ModrinthClient.ModrinthVersion>>> projectFutures = new LinkedHashMap<>();
        for (ModrinthClient.ModrinthVersion current : matchedByHash.values()) {
            projectFutures.computeIfAbsent(
                    current.projectId(),
                    ModrinthClient::fetchLatestZipVersionFreshAsync
            );
        }

        CompletableFuture<?>[] all = projectFutures.values().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(all).thenApply(ignored -> {
            Map<Path, PackUpdate> nextUpdates = new LinkedHashMap<>();
            for (GunpackInfo pack : zipPacks) {
                String hash = pack.sha256().toLowerCase(Locale.ROOT);
                ModrinthClient.ModrinthVersion current = matchedByHash.get(hash);
                if (current == null) {
                    continue;
                }
                CompletableFuture<Optional<ModrinthClient.ModrinthVersion>> future = projectFutures.get(current.projectId());
                if (future == null) {
                    continue;
                }
                Optional<ModrinthClient.ModrinthVersion> latestOpt = future.join();
                if (latestOpt.isEmpty()) {
                    continue;
                }
                ModrinthClient.ModrinthVersion latest = latestOpt.get();
                boolean newerVersion = !latest.versionId().equals(current.versionId())
                        && !latest.sha256().equalsIgnoreCase(hash);
                if (newerVersion) {
                    Path normalized = pack.path().toAbsolutePath().normalize();
                    nextUpdates.put(normalized, new PackUpdate(
                            pack,
                            current.projectId(),
                            current.versionNumber(),
                            latest
                    ));
                }
            }
            UPDATES_BY_PATH.clear();
            UPDATES_BY_PATH.putAll(nextUpdates);
            LOGGER.info("Gunpack update check finished: {} update(s) available.", nextUpdates.size());
            return List.copyOf(nextUpdates.values());
        });
    }
}
