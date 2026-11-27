package com.liokasanovich.gun_management.core;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves and initializes the directory layout used for TaCZ gunpacks.
 */
public final class GunpackPaths {
    public static final String TACZ_DIR_NAME = "tacz";
    public static final String DISABLED_DIR_NAME = ".disabled";
    public static final String TMP_DIR_NAME = ".tmp";
    public static final String PENDING_OPS_FILE_NAME = ".pending_ops.json";

    private GunpackPaths() {
    }

    public static Path getTaczDir() {
        return FMLPaths.GAMEDIR.get().resolve(TACZ_DIR_NAME);
    }

    public static Path getDisabledDir() {
        return getTaczDir().resolve(DISABLED_DIR_NAME);
    }

    public static Path getTempDir() {
        return getTaczDir().resolve(TMP_DIR_NAME);
    }

    public static Path getPendingOpsFile() {
        return getTaczDir().resolve(PENDING_OPS_FILE_NAME);
    }

    public static void ensureDirectories(Path taczDir) throws IOException {
        Files.createDirectories(taczDir);
        Files.createDirectories(taczDir.resolve(DISABLED_DIR_NAME));
    }
}
