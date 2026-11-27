package com.liokasanovich.gun_management.core;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable metadata snapshot for a local TaCZ gunpack (zip archive or directory).
 */
public record GunpackInfo(
        String namespace,
        String displayName,
        String version,
        String description,
        List<String> authors,
        String license,
        @Nullable String logoPath,
        Path path,
        boolean directory,
        boolean enabled,
        boolean pendingRestart,
        int gunCount,
        int ammoCount,
        int attachmentCount,
        String sha256,
        long fileSizeBytes
) {
    public String fileName() {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }
}
