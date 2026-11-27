package com.liokasanovich.gun_management.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous Modrinth REST API v2 client for searching, resolving, and downloading TaCZ gunpacks.
 */
public final class ModrinthClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModrinthClient.class);
    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "GunManagement/0.1.0 (Minecraft 1.21.1 NeoForge)";
    private static final Gson GSON = new Gson();

    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "GunManagement-Modrinth");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .executor(IO_EXECUTOR)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record ModrinthProject(
            String projectId,
            String slug,
            String title,
            String description,
            String author,
            @Nullable String iconUrl,
            long downloads,
            String dateModified,
            @Nullable String latestVersion
    ) {
    }

    public record ModrinthVersion(
            String versionId,
            String projectId,
            String versionNumber,
            String fileName,
            String downloadUrl,
            String sha256,
            long fileSize
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private ModrinthClient() {
    }

    public static CompletableFuture<List<ModrinthProject>> searchGunpacksAsync(String query, int limit, int offset) {
        String trimmed = query == null ? "" : query.trim();
        boolean isDefault = trimmed.isEmpty();
        String effectiveQuery = isDefault
                ? "tacz gunpack"
                : (trimmed.toLowerCase(Locale.ROOT).contains("tacz") ? trimmed : "tacz " + trimmed);
        String sortIndex = isDefault ? "downloads" : "relevance";

        String encodedQuery = URLEncoder.encode(effectiveQuery, StandardCharsets.UTF_8);
        String encodedFacets = URLEncoder.encode(
                "[[\"project_type:datapack\",\"project_type:resourcepack\"]]",
                StandardCharsets.UTF_8);
        String url = API_BASE + "/search?query=" + encodedQuery
                + "&facets=" + encodedFacets
                + "&index=" + sortIndex
                + "&limit=" + limit
                + "&offset=" + offset;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Modrinth search failed with HTTP " + resp.statusCode());
                    }
                    JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                    List<ModrinthProject> list = new ArrayList<>();
                    if (root != null && root.has("hits") && root.get("hits").isJsonArray()) {
                        for (JsonElement el : root.getAsJsonArray("hits")) {
                            JsonObject obj = el.getAsJsonObject();
                            String slug = getString(obj, "slug", "");
                            if ("timeless-and-classics-zero".equals(slug)) {
                                continue;
                            }
                            list.add(new ModrinthProject(
                                    getString(obj, "project_id", ""),
                                    slug,
                                    getString(obj, "title", "Unknown"),
                                    getString(obj, "description", ""),
                                    getString(obj, "author", "Unknown"),
                                    getNullableString(obj, "icon_url"),
                                    obj.has("downloads") ? obj.get("downloads").getAsLong() : 0L,
                                    getString(obj, "date_modified", ""),
                                    getNullableString(obj, "latest_version")
                            ));
                        }
                    }
                    return list;
                });
    }

    public static CompletableFuture<Optional<ModrinthVersion>> getLatestCompatibleVersionAsync(String projectId) {
        String encodedGameVersions = URLEncoder.encode("[\"1.21.1\"]", StandardCharsets.UTF_8);
        String preferredUrl = API_BASE + "/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
                + "/version?game_versions=" + encodedGameVersions;
        String fallbackUrl = API_BASE + "/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
                + "/version";

        return fetchFirstZipVersion(preferredUrl).thenCompose(opt -> {
            if (opt.isPresent()) {
                return CompletableFuture.completedFuture(opt);
            }
            return fetchFirstZipVersion(fallbackUrl);
        });
    }

    private static CompletableFuture<Optional<ModrinthVersion>> fetchFirstZipVersion(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Modrinth version lookup failed with HTTP " + resp.statusCode());
                    }
                    JsonArray arr = GSON.fromJson(resp.body(), JsonArray.class);
                    if (arr == null || arr.isEmpty()) {
                        return Optional.<ModrinthVersion>empty();
                    }
                    for (JsonElement el : arr) {
                        Optional<ModrinthVersion> parsed = parseVersionObject(el.getAsJsonObject());
                        if (parsed.isPresent()) {
                            return parsed;
                        }
                    }
                    return Optional.<ModrinthVersion>empty();
                });
    }

    public static CompletableFuture<Optional<ModrinthVersion>> findVersionBySha256Async(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = API_BASE + "/version_file/" + URLEncoder.encode(sha256, StandardCharsets.UTF_8) + "?algorithm=sha256";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() == 404) {
                        return Optional.<ModrinthVersion>empty();
                    }
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Modrinth hash lookup failed with HTTP " + resp.statusCode());
                    }
                    JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
                    return obj != null ? parseVersionObject(obj) : Optional.<ModrinthVersion>empty();
                });
    }

    public static CompletableFuture<Path> downloadAndInstallAsync(
            ModrinthVersion version,
            Path taczDir,
            @Nullable ProgressListener progressListener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Path tmpDir = taczDir.resolve(GunpackPaths.TMP_DIR_NAME);
            Path partFile = tmpDir.resolve(version.fileName() + ".part");
            Path finalFile = taczDir.resolve(version.fileName());

            try {
                GunpackPaths.ensureDirectories(taczDir);
                Files.createDirectories(tmpDir);

                HttpRequest request = HttpRequest.newBuilder(URI.create(version.downloadUrl()))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                HttpResponse<InputStream> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    throw new IOException("Download failed with HTTP " + resp.statusCode());
                }

                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(version.fileSize());
                long downloaded = 0L;
                long lastNotifyNanos = 0L;

                try (InputStream in = resp.body();
                     OutputStream out = Files.newOutputStream(partFile)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (progressListener != null) {
                            long now = System.nanoTime();
                            if (now - lastNotifyNanos >= 100_000_000L || (contentLength > 0 && downloaded >= contentLength)) {
                                lastNotifyNanos = now;
                                progressListener.onProgress(downloaded, contentLength);
                            }
                        }
                    }
                }

                if (!version.sha256().isBlank()) {
                    String actualHash = GunpackScanner.computeSha256(partFile);
                    if (!actualHash.equalsIgnoreCase(version.sha256())) {
                        throw new IOException("SHA-256 mismatch: expected " + version.sha256() + ", got " + actualHash);
                    }
                }

                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Installed Modrinth gunpack {} to {}", version.fileName(), finalFile);
                return finalFile;
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException ignored) {
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Failed to download " + version.fileName() + ": " + e.getMessage(), e);
            }
        }, IO_EXECUTOR);
    }

    public static CompletableFuture<byte[]> downloadBytesAsync(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("HTTP " + resp.statusCode());
                    }
                    return resp.body();
                });
    }

    private static Optional<ModrinthVersion> parseVersionObject(JsonObject obj) {
        if (!obj.has("files") || !obj.get("files").isJsonArray()) {
            return Optional.empty();
        }
        JsonArray files = obj.getAsJsonArray("files");
        JsonObject chosenFile = null;
        for (JsonElement fEl : files) {
            JsonObject f = fEl.getAsJsonObject();
            String name = getString(f, "filename", "");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                continue;
            }
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                chosenFile = f;
                break;
            }
            if (chosenFile == null) {
                chosenFile = f;
            }
        }
        if (chosenFile == null) {
            return Optional.empty();
        }
        String url = getString(chosenFile, "url", "");
        String fileName = getString(chosenFile, "filename", "");
        if (url.isBlank() || fileName.isBlank()) {
            return Optional.empty();
        }
        String sha256 = "";
        if (chosenFile.has("hashes") && chosenFile.get("hashes").isJsonObject()) {
            sha256 = getString(chosenFile.getAsJsonObject("hashes"), "sha256", "");
        }
        long size = chosenFile.has("size") ? chosenFile.get("size").getAsLong() : 0L;

        return Optional.of(new ModrinthVersion(
                getString(obj, "id", ""),
                getString(obj, "project_id", ""),
                getString(obj, "version_number", "1.0.0"),
                fileName,
                url,
                sha256,
                size
        ));
    }

    private static String getString(JsonObject obj, String key, String defaultVal) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return defaultVal;
    }

    @Nullable
    private static String getNullableString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
}
