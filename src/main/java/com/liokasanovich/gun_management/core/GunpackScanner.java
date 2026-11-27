package com.liokasanovich.gun_management.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans local enabled ({@code tacz/}) and disabled ({@code tacz/.disabled/}) gunpacks
 * and extracts metadata, item counts, and SHA-256 digests.
 */
public final class GunpackScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackScanner.class);
    private static final Gson GSON = new Gson();
    private static final String META_FILE = "gunpack.meta.json";
    private static final String INFO_FILE = "gunpack_info.json";

    private GunpackScanner() {
    }

    public static CompletableFuture<List<GunpackInfo>> scanAllAsync() {
        return scanAllAsync(GunpackPaths.getTaczDir(), Set.of());
    }

    public static CompletableFuture<List<GunpackInfo>> scanAllAsync(Path taczDir, Set<Path> pendingRestartPaths) {
        return CompletableFuture.supplyAsync(() -> scanAll(taczDir, pendingRestartPaths));
    }

    public static List<GunpackInfo> scanAll() {
        return scanAll(GunpackPaths.getTaczDir(), Set.of());
    }

    public static List<GunpackInfo> scanAll(Path taczDir, Set<Path> pendingRestartPaths) {
        try {
            GunpackPaths.ensureDirectories(taczDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to initialize gunpack directories at {}", taczDir, e);
            return List.of();
        }

        List<GunpackInfo> results = new ArrayList<>();
        scanDirectory(taczDir, true, pendingRestartPaths, results);
        scanDirectory(taczDir.resolve(GunpackPaths.DISABLED_DIR_NAME), false, pendingRestartPaths, results);

        results.sort(Comparator.comparing(GunpackInfo::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(GunpackInfo::fileName, String.CASE_INSENSITIVE_ORDER));
        return results;
    }

    private static void scanDirectory(Path dir, boolean enabled, Set<Path> pendingRestartPaths, List<GunpackInfo> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                GunpackInfo info = null;
                boolean pending = pendingRestartPaths.contains(entry.toAbsolutePath().normalize());
                if (Files.isDirectory(entry)) {
                    info = parseDirectoryPack(entry, enabled, pending);
                } else if (name.endsWith(".zip") && Files.isRegularFile(entry)) {
                    info = parseZipPack(entry, enabled, pending);
                }
                if (info != null) {
                    out.add(info);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan gunpack directory {}", dir, e);
        }
    }

    @Nullable
    private static GunpackInfo parseDirectoryPack(Path dir, boolean enabled, boolean pendingRestart) {
        Path metaPath = dir.resolve(META_FILE);
        if (!Files.isRegularFile(metaPath)) {
            return null;
        }

        String namespace = readNamespaceFromFile(metaPath, dir.getFileName().toString());
        if (namespace == null || namespace.isBlank()) {
            return null;
        }

        PackDetails details = readDetailsFromDir(dir, namespace);
        int guns = countJsonFilesInDir(dir.resolve("data").resolve(namespace).resolve("index").resolve("guns"));
        int ammo = countJsonFilesInDir(dir.resolve("data").resolve(namespace).resolve("index").resolve("ammo"));
        int attachments = countJsonFilesInDir(dir.resolve("data").resolve(namespace).resolve("index").resolve("attachments"));

        return new GunpackInfo(
                namespace,
                details.displayName(namespace),
                details.version,
                details.description,
                details.authors,
                details.license,
                details.logo,
                dir,
                true,
                enabled,
                pendingRestart,
                guns,
                ammo,
                attachments,
                "",
                0L
        );
    }

    @Nullable
    private static GunpackInfo parseZipPack(Path zipPath, boolean enabled, boolean pendingRestart) {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry metaEntry = zipFile.getEntry(META_FILE);
            if (metaEntry == null) {
                return null;
            }

            String namespace;
            try (InputStream in = zipFile.getInputStream(metaEntry)) {
                namespace = readNamespace(in, zipPath.getFileName().toString());
            }
            if (namespace == null || namespace.isBlank()) {
                return null;
            }

            PackDetails details = PackDetails.EMPTY;
            ZipEntry infoEntry = zipFile.getEntry("assets/" + namespace + "/" + INFO_FILE);
            if (infoEntry != null) {
                try (InputStream in = zipFile.getInputStream(infoEntry)) {
                    details = parsePackDetails(in);
                }
            }

            String gunPrefix = "data/" + namespace + "/index/guns/";
            String ammoPrefix = "data/" + namespace + "/index/ammo/";
            String attachmentPrefix = "data/" + namespace + "/index/attachments/";
            int guns = 0;
            int ammo = 0;
            int attachments = 0;

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (!entryName.endsWith(".json")) {
                    continue;
                }
                if (entryName.startsWith(gunPrefix)) {
                    guns++;
                } else if (entryName.startsWith(ammoPrefix)) {
                    ammo++;
                } else if (entryName.startsWith(attachmentPrefix)) {
                    attachments++;
                }
            }

            long size = Files.size(zipPath);
            String sha256 = computeSha256(zipPath);

            return new GunpackInfo(
                    namespace,
                    details.displayName(namespace),
                    details.version,
                    details.description,
                    details.authors,
                    details.license,
                    details.logo,
                    zipPath,
                    false,
                    enabled,
                    pendingRestart,
                    guns,
                    ammo,
                    attachments,
                    sha256,
                    size
            );
        } catch (IOException e) {
            LOGGER.warn("Failed to read zip gunpack {}: {}", zipPath, e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String readNamespaceFromFile(Path metaPath, String fallbackName) {
        try (InputStream in = Files.newInputStream(metaPath)) {
            return readNamespace(in, fallbackName);
        } catch (IOException e) {
            LOGGER.warn("Failed to read {} for {}: {}", META_FILE, fallbackName, e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String readNamespace(InputStream in, String fallbackName) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            if (obj == null) {
                return null;
            }
            if (obj.has("namespace") && !obj.get("namespace").isJsonNull()) {
                return obj.get("namespace").getAsString();
            }
            return null;
        } catch (JsonParseException | IllegalStateException | IOException e) {
            LOGGER.warn("Invalid {} in {}: {}", META_FILE, fallbackName, e.getMessage());
            return null;
        }
    }

    private static PackDetails readDetailsFromDir(Path dir, String namespace) {
        Path infoPath = dir.resolve("assets").resolve(namespace).resolve(INFO_FILE);
        if (!Files.isRegularFile(infoPath)) {
            return PackDetails.EMPTY;
        }
        try (InputStream in = Files.newInputStream(infoPath)) {
            return parsePackDetails(in);
        } catch (IOException e) {
            LOGGER.debug("Failed to read {} from {}: {}", INFO_FILE, dir, e.getMessage());
            return PackDetails.EMPTY;
        }
    }

    private static PackDetails parsePackDetails(InputStream in) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            if (obj == null) {
                return PackDetails.EMPTY;
            }
            String name = getString(obj, "name", null);
            String version = getString(obj, "version", "1.0.0");
            String desc = getString(obj, "desc", "");
            String license = getString(obj, "license", "All Rights Reserved");
            String logo = getString(obj, "logo", null);

            List<String> authors = new ArrayList<>();
            if (obj.has("authors") && obj.get("authors").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("authors")) {
                    if (el.isJsonPrimitive()) {
                        authors.add(el.getAsString());
                    }
                }
            }
            return new PackDetails(name, version, desc, Collections.unmodifiableList(authors), license, logo);
        } catch (JsonParseException | IllegalStateException | IOException e) {
            return PackDetails.EMPTY;
        }
    }

    private static String getString(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    private static int countJsonFilesInDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    public static String computeSha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            LOGGER.warn("Failed to compute SHA-256 for {}", file, e);
            return "";
        }
    }

    private record PackDetails(
            @Nullable String name,
            String version,
            String description,
            List<String> authors,
            String license,
            @Nullable String logo
    ) {
        static final PackDetails EMPTY = new PackDetails(null, "1.0.0", "", List.of(), "All Rights Reserved", null);

        String displayName(String fallbackNamespace) {
            return (name != null && !name.isBlank()) ? name : fallbackNamespace;
        }
    }
}
