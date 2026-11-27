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
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Asynchronous GitHub client for searching repositories tagged with {@code tacz},
 * fetching/filtering READMEs and gunpack {@code .zip} files, and downloading via acceleration mirrors.
 */
public final class GitHubClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_BASE = "https://api.github.com";
    private static final String USER_AGENT = "GunManagement/0.1.0";
    private static final Gson GSON = new Gson();

    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "GunManagement-GitHub");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .executor(IO_EXECUTOR)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public enum MirrorNode {
        DIRECT("gui.gun_management.mirror.direct", ""),
        GHFAST("gui.gun_management.mirror.ghfast", "https://ghfast.top/"),
        GH_PROXY_COM("gui.gun_management.mirror.gh_proxy_com", "https://gh-proxy.com/"),
        GHPROXY_NET("gui.gun_management.mirror.ghproxy_net", "https://ghproxy.net/");

        private final String translationKey;
        private final String prefix;

        MirrorNode(String translationKey, String prefix) {
            this.translationKey = translationKey;
            this.prefix = prefix;
        }

        public String translationKey() {
            return translationKey;
        }

        public String wrapUrl(String rawUrl) {
            if (prefix.isEmpty() || rawUrl.startsWith(prefix)) {
                return rawUrl;
            }
            return prefix + rawUrl;
        }

        public MirrorNode next() {
            MirrorNode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public record GitHubRepo(
            String fullName,
            String name,
            String owner,
            String description,
            @Nullable String avatarUrl,
            int stars,
            String defaultBranch,
            String htmlUrl
    ) {
    }

    public record GunpackZipAsset(
            String fileName,
            String downloadUrl,
            long sizeBytes,
            String sourceLabel
    ) {
    }

    public record RepoDetails(
            String filteredReadme,
            List<GunpackZipAsset> zipAssets
    ) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private static final Map<String, RepoDetails> DETAILS_CACHE = new ConcurrentHashMap<>();

    private GitHubClient() {
    }

    public static CompletableFuture<List<GitHubRepo>> searchTaczReposAsync(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        String q = trimmed.isEmpty()
                ? "topic:tacz"
                : "topic:tacz " + trimmed;
        String encodedQ = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String url = API_BASE + "/search/repositories?q=" + encodedQ + "&sort=stars&order=desc&per_page=" + limit;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> {
                    if (resp.statusCode() == 403) {
                        throw new RuntimeException("GitHub API rate limit exceeded (HTTP 403). Try again later.");
                    }
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("GitHub search failed with HTTP " + resp.statusCode());
                    }
                    JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                    List<GitHubRepo> candidates = new ArrayList<>();
                    if (root != null && root.has("items") && root.get("items").isJsonArray()) {
                        for (JsonElement el : root.getAsJsonArray("items")) {
                            JsonObject obj = el.getAsJsonObject();
                            String fullName = getString(obj, "full_name", "");
                            String repoName = getString(obj, "name", "");
                            if (isExcludedRepo(fullName, repoName)) {
                                continue;
                            }
                            JsonObject ownerObj = obj.has("owner") && obj.get("owner").isJsonObject()
                                    ? obj.getAsJsonObject("owner") : null;
                            candidates.add(new GitHubRepo(
                                    fullName,
                                    repoName,
                                    ownerObj != null ? getString(ownerObj, "login", "Unknown") : "Unknown",
                                    getString(obj, "description", ""),
                                    ownerObj != null ? getNullableString(ownerObj, "avatar_url") : null,
                                    obj.has("stargazers_count") ? obj.get("stargazers_count").getAsInt() : 0,
                                    getString(obj, "default_branch", "main"),
                                    getString(obj, "html_url", "")
                            ));
                            if (candidates.size() >= Math.min(limit, 25)) {
                                break;
                            }
                        }
                    }

                    // Only include repositories that actually contain at least one .zip gunpack (using cache first).
                    List<CompletableFuture<Optional<GitHubRepo>>> checks = candidates.stream()
                            .map(repo -> {
                                RepoDetails cached = DETAILS_CACHE.get(repo.fullName());
                                if (cached != null) {
                                    return CompletableFuture.completedFuture(
                                            cached.zipAssets().isEmpty() ? Optional.<GitHubRepo>empty() : Optional.of(repo));
                                }
                                return findGunpackZipsAsync(repo)
                                        .thenApply(zips -> {
                                            DETAILS_CACHE.putIfAbsent(repo.fullName(),
                                                    new RepoDetails(filterReadmeText(repo.description()), zips));
                                            return zips.isEmpty() ? Optional.<GitHubRepo>empty() : Optional.of(repo);
                                        })
                                        .exceptionally(ex -> Optional.empty());
                            })
                            .toList();

                    return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
                            .thenApply(v -> checks.stream()
                                    .map(CompletableFuture::join)
                                    .flatMap(Optional::stream)
                                    .limit(limit)
                                    .toList());
                });
    }

    public static CompletableFuture<RepoDetails> fetchRepoDetailsAsync(GitHubRepo repo) {
        CompletableFuture<String> readmeFuture = fetchFilteredReadmeAsync(repo);
        RepoDetails cached = DETAILS_CACHE.get(repo.fullName());
        CompletableFuture<List<GunpackZipAsset>> zipsFuture = (cached != null && !cached.zipAssets().isEmpty())
                ? CompletableFuture.completedFuture(cached.zipAssets())
                : findGunpackZipsAsync(repo);
        return readmeFuture.thenCombine(zipsFuture, (readme, zips) -> {
            RepoDetails details = new RepoDetails(readme, zips);
            DETAILS_CACHE.put(repo.fullName(), details);
            return details;
        });
    }

    private static CompletableFuture<String> fetchFilteredReadmeAsync(GitHubRepo repo) {
        String url = API_BASE + "/repos/" + repo.fullName() + "/readme";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        return "";
                    }
                    JsonObject obj = GSON.fromJson(resp.body(), JsonObject.class);
                    if (obj == null || !obj.has("content")) {
                        return "";
                    }
                    String rawBase64 = obj.get("content").getAsString().replaceAll("\\s+", "");
                    String decoded = new String(Base64.getDecoder().decode(rawBase64), StandardCharsets.UTF_8);
                    return filterReadmeText(decoded);
                })
                .exceptionally(ex -> "");
    }

    /**
     * Strips code blocks, markdown badges, tables, URLs, HTML tags, and noisy formatting
     * so README text reads cleanly in-game.
     */
    public static String filterReadmeText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown
                .replaceAll("(?s)```.*?```", "")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", "")
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[`*_~>]+", "")
                .replaceAll("(?m)^\\s*[-=]{3,}\\s*$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > 600) {
            return text.substring(0, 600) + "...";
        }
        return text;
    }

    private static CompletableFuture<List<GunpackZipAsset>> findGunpackZipsAsync(GitHubRepo repo) {
        String releasesUrl = API_BASE + "/repos/" + repo.fullName() + "/releases?per_page=5";
        HttpRequest relReq = HttpRequest.newBuilder(URI.create(releasesUrl))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(relReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(resp -> {
                    List<GunpackZipAsset> assets = new ArrayList<>();
                    if (resp.statusCode() == 200) {
                        JsonArray releases = GSON.fromJson(resp.body(), JsonArray.class);
                        if (releases != null) {
                            for (JsonElement rEl : releases) {
                                JsonObject rel = rEl.getAsJsonObject();
                                String tag = getString(rel, "tag_name", "release");
                                if (rel.has("assets") && rel.get("assets").isJsonArray()) {
                                    for (JsonElement aEl : rel.getAsJsonArray("assets")) {
                                        JsonObject asset = aEl.getAsJsonObject();
                                        String name = getString(asset, "name", "");
                                        String dl = getString(asset, "browser_download_url", "");
                                        long size = asset.has("size") ? asset.get("size").getAsLong() : 0L;
                                        if (isGunpackZipName(name) && !dl.isBlank()) {
                                            assets.add(new GunpackZipAsset(name, dl, size, "Release " + tag));
                                        }
                                    }
                                }
                                if (!assets.isEmpty()) {
                                    break;
                                }
                            }
                        }
                    }
                    if (!assets.isEmpty()) {
                        return CompletableFuture.completedFuture(assets);
                    }
                    return findRootZipContentsAsync(repo);
                })
                .exceptionally(ex -> List.of());
    }

    private static CompletableFuture<List<GunpackZipAsset>> findRootZipContentsAsync(GitHubRepo repo) {
        String treeUrl = API_BASE + "/repos/" + repo.fullName()
                + "/git/trees/" + URLEncoder.encode(repo.defaultBranch(), StandardCharsets.UTF_8) + "?recursive=1";
        HttpRequest req = HttpRequest.newBuilder(URI.create(treeUrl))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    List<GunpackZipAsset> assets = new ArrayList<>();
                    if (resp.statusCode() != 200) {
                        return assets;
                    }
                    JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
                    if (root == null || !root.has("tree") || !root.get("tree").isJsonArray()) {
                        return assets;
                    }
                    boolean hasGunpackMeta = false;
                    for (JsonElement el : root.getAsJsonArray("tree")) {
                        JsonObject item = el.getAsJsonObject();
                        String type = getString(item, "type", "");
                        String path = getString(item, "path", "");
                        if (!"blob".equals(type)) {
                            continue;
                        }
                        if ("gunpack.meta.json".equalsIgnoreCase(path)) {
                            hasGunpackMeta = true;
                        }
                        int slash = path.lastIndexOf('/');
                        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
                        if (isGunpackZipName(fileName)) {
                            String rawUrl = "https://raw.githubusercontent.com/" + repo.fullName()
                                    + "/" + repo.defaultBranch() + "/" + path;
                            long size = item.has("size") ? item.get("size").getAsLong() : 0L;
                            assets.add(new GunpackZipAsset(fileName, rawUrl, size, "Repo File"));
                        }
                    }
                    if (assets.isEmpty() && hasGunpackMeta) {
                        String archiveUrl = "https://github.com/" + repo.fullName()
                                + "/archive/refs/heads/" + repo.defaultBranch() + ".zip";
                        assets.add(new GunpackZipAsset(
                                repo.name() + "-" + repo.defaultBranch() + ".zip",
                                archiveUrl,
                                0L,
                                "Repo Archive"));
                    }
                    return assets;
                })
                .exceptionally(ex -> List.of());
    }

    private static boolean isExcludedRepo(String fullName, String repoName) {
        String lowerFull = fullName.toLowerCase(Locale.ROOT);
        String lowerName = repoName.toLowerCase(Locale.ROOT);
        return lowerFull.equals("mcmodderanchor/tacz")
                || lowerFull.equals("skydeps1459/auto-taczgunpack-manager")
                || lowerName.equals("tacz")
                || lowerName.contains("wiki")
                || lowerName.contains("docs");
    }

    private static boolean isGunpackZipName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip")) {
            return false;
        }
        return !lower.contains("source")
                && !lower.contains("javadoc")
                && !lower.contains("src")
                && !lower.contains("-dev")
                && !lower.contains("-api")
                && !lower.contains("installer")
                && !lower.contains("windows")
                && !lower.contains("linux")
                && !lower.contains("macos");
    }

    public static CompletableFuture<Path> downloadAndInstallAsync(
            GunpackZipAsset asset,
            MirrorNode mirror,
            Path taczDir,
            @Nullable ProgressListener progressListener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Path tmpDir = taczDir.resolve(GunpackPaths.TMP_DIR_NAME);
            Path partFile = tmpDir.resolve(asset.fileName() + ".part");
            Path finalFile = taczDir.resolve(asset.fileName());
            String targetUrl = mirror.wrapUrl(asset.downloadUrl());

            try {
                GunpackPaths.ensureDirectories(taczDir);
                Files.createDirectories(tmpDir);

                HttpResponse<InputStream> resp = sendDownload(targetUrl);
                if (resp.statusCode() != 200 && mirror != MirrorNode.DIRECT) {
                    LOGGER.warn("Mirror {} failed with HTTP {}, falling back to direct GitHub URL.",
                            mirror.name(), resp.statusCode());
                    resp.body().close();
                    resp = sendDownload(asset.downloadUrl());
                }
                if (resp.statusCode() != 200) {
                    throw new IOException("Download failed with HTTP " + resp.statusCode());
                }

                long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(asset.sizeBytes());
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

                if (!isValidZipArchive(partFile)) {
                    throw new IOException("Downloaded file is not a valid ZIP archive: " + asset.fileName());
                }

                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Installed GitHub gunpack {} to {}", asset.fileName(), finalFile);
                return finalFile;
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException ignored) {
                }
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Failed to download " + asset.fileName() + ": " + e.getMessage(), e);
            }
        }, IO_EXECUTOR);
    }

    private static HttpResponse<InputStream> sendDownload(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean isValidZipArchive(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            return entries.hasMoreElements();
        } catch (IOException e) {
            return false;
        }
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
