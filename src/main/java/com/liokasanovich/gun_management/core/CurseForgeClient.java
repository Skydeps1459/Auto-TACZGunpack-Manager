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
 * Asynchronous CurseForge Core API v1 client for searching and downloading TaCZ gunpacks.
 * Requires a CurseForge API key configured in {@code config/gun_management-common.toml}.
 */
public final class CurseForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(CurseForgeClient.class);
    private static final String API_BASE = "https://api.curseforge.com/v1";
    private static final int MINECRAFT_GAME_ID = 432;
    private static final String USER_AGENT = "GunManagement/0.1.0";
    private static final Gson GSON = new Gson();

    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "GunManagement-CurseForge");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .executor(IO_EXECUTOR)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record CurseForgeProject(
            int id,
            String slug,
            String name,
            String summary,
            String author,
            @Nullable String iconUrl,
            long downloads
    ) {
    }

    public record CurseForgeFile(
            int fileId,
            String fileName,
            String downloadUrl,
            long fileSize
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private CurseForgeClient() {
    }

    public static CompletableFuture<List<CurseForgeProject>> searchGunpacksAsync(String query, String apiKey, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MISSING_API_KEY"));
        }
        String trimmed = query == null ? "" : query.trim();
        String effectiveQuery = trimmed.isEmpty()
                ? "tacz"
                : (trimmed.toLowerCase(Locale.ROOT).contains("tacz") ? trimmed : "tacz " + trimmed);
        String encodedQuery = URLEncoder.encode(effectiveQuery, StandardCharsets.UTF_8);
        String url = API_BASE + "/mods/search?gameId=" + MINECRAFT_GAME_ID
                + "&searchFilter=" + encodedQuery
                + "&sortField=6&sortOrder=desc&pageSize=" + limit;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey.trim())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() == 403) {
                        throw new RuntimeException("Invalid CurseForge API key (HTTP 403)");
                    }
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("CurseForge search failed with HTTP " + resp.statusCode());
                    }
                    JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                    List<CurseForgeProject> results = new ArrayList<>();
                    if (root != null && root.has("data") && root.get("data").isJsonArray()) {
                        for (JsonElement el : root.getAsJsonArray("data")) {
                            JsonObject obj = el.getAsJsonObject();
                            int id = obj.has("id") ? obj.get("id").getAsInt() : 0;
                            String slug = getString(obj, "slug", "");
                            if ("timeless-and-classics-zero".equals(slug)) {
                                continue;
                            }
                            String author = "Unknown";
                            if (obj.has("authors") && obj.get("authors").isJsonArray()
                                    && !obj.getAsJsonArray("authors").isEmpty()) {
                                author = getString(obj.getAsJsonArray("authors").get(0).getAsJsonObject(), "name", "Unknown");
                            }
                            String iconUrl = null;
                            if (obj.has("logo") && obj.get("logo").isJsonObject()) {
                                iconUrl = getNullableString(obj.getAsJsonObject("logo"), "thumbnailUrl");
                            }
                            long downloads = obj.has("downloadCount") ? obj.get("downloadCount").getAsLong() : 0L;
                            results.add(new CurseForgeProject(
                                    id,
                                    slug,
                                    getString(obj, "name", "Unknown"),
                                    getString(obj, "summary", ""),
                                    author,
                                    iconUrl,
                                    downloads
                            ));
                        }
                    }
                    return results;
                });
    }

    public static CompletableFuture<Optional<CurseForgeFile>> getLatestZipFileAsync(int modId, String apiKey, String gameVersion) {
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MISSING_API_KEY"));
        }
        String url = API_BASE + "/mods/" + modId + "/files?pageSize=20";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey.trim())
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("CurseForge file lookup failed with HTTP " + resp.statusCode());
                    }
                    JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                    if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
                        return Optional.<CurseForgeFile>empty();
                    }
                    JsonArray files = root.getAsJsonArray("data");
                    CurseForgeFile fallback = null;
                    for (JsonElement el : files) {
                        JsonObject f = el.getAsJsonObject();
                        String fileName = getString(f, "fileName", "");
                        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                            continue;
                        }
                        int fileId = f.has("id") ? f.get("id").getAsInt() : 0;
                        String dl = getString(f, "downloadUrl", "");
                        if (dl.isBlank() && fileId > 0) {
                            dl = buildCdnUrl(fileId, fileName);
                        }
                        long size = f.has("fileLength") ? f.get("fileLength").getAsLong() : 0L;
                        CurseForgeFile parsed = new CurseForgeFile(fileId, fileName, dl, size);
                        if (fallback == null) {
                            fallback = parsed;
                        }
                        if (matchesGameVersion(f, gameVersion)) {
                            return Optional.of(parsed);
                        }
                    }
                    return Optional.ofNullable(fallback);
                });
    }

    private static boolean matchesGameVersion(JsonObject fileObj, String targetVersion) {
        if (!fileObj.has("gameVersions") || !fileObj.get("gameVersions").isJsonArray()) {
            return false;
        }
        for (JsonElement el : fileObj.getAsJsonArray("gameVersions")) {
            if (el.isJsonPrimitive() && targetVersion.equals(el.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String buildCdnUrl(int fileId, String fileName) {
        int part1 = fileId / 1000;
        int part2 = fileId % 1000;
        return "https://edge.forgecdn.net/files/" + part1 + "/" + part2 + "/"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static CompletableFuture<Path> downloadAndInstallAsync(
            CurseForgeFile file,
            Path taczDir,
            @Nullable ProgressListener progressListener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Path tmpDir = taczDir.resolve(GunpackPaths.TMP_DIR_NAME);
            Path partFile = tmpDir.resolve(file.fileName() + ".part");
            Path finalFile = taczDir.resolve(file.fileName());

            try {
                GunpackPaths.ensureDirectories(taczDir);
                Files.createDirectories(tmpDir);

                HttpRequest request = HttpRequest.newBuilder(URI.create(file.downloadUrl()))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofMinutes(5))
                        .GET()
                        .build();

                HttpResponse<InputStream> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    throw new IOException("Download failed with HTTP " + resp.statusCode());
                }

                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(file.fileSize());
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

                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Installed CurseForge gunpack {} to {}", file.fileName(), finalFile);
                return finalFile;
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException ignored) {
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Failed to download " + file.fileName() + ": " + e.getMessage(), e);
            }
        }, IO_EXECUTOR);
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
