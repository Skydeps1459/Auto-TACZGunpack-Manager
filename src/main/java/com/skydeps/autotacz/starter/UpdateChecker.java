package com.skydeps.autotacz.starter;

import com.skydeps.autotacz.config.StarterConfig;
import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.remote.HttpFetcher;
import com.skydeps.autotacz.remote.JsonManifestSource;
import com.skydeps.autotacz.remote.RemotePack;
import com.skydeps.autotacz.scan.GunpackIndex;
import com.skydeps.autotacz.scan.GunpackScanner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 启动前的枪包更新检查。
 *
 * <p>设计要点，都是为了「绝不阻塞游戏启动」：
 * <ul>
 *   <li>整个检查在<b>后台线程</b>执行，界面只轮询 {@link #snapshot()}，不做阻塞等待</li>
 *   <li>网络异常<b>不抛给调用方</b>，而是变成 {@link Status#NETWORK_ERROR} 状态，
 *       让界面提供「跳过」按钮继续启动</li>
 *   <li>任何意外异常都收敛为 {@link Status#FAILED}，同样不阻止启动</li>
 * </ul>
 *
 * <p>状态机：
 * <pre>
 *   IDLE → SCANNING → FETCHING → DONE
 *                        ↓
 *             NETWORK_ERROR / FAILED   （均可跳过）
 * </pre>
 */
public final class UpdateChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateChecker.class);

    private final StarterConfig config;
    private final Path taczRoot;

    /**
     * HTTP 客户端工厂。抽成可替换字段是为了让测试能注入虚假客户端，
     * 从而在离线环境下验证「网络异常可跳过」这条关键路径。
     */
    private final Function<Duration, HttpFetcher> fetcherFactory;

    /**
     * 主地址失败后依次尝试的备用清单地址。
     *
     * <p>做成实例字段而非直接读常量，是为了让测试能传空列表把网络完全隔离——
     * 否则「主地址不可达」的用例会在能联网的 CI 上真的连到备用地址，
     * 测出来是 {@code DONE} 而不是期望的 {@code NETWORK_ERROR}。
     */
    private final List<String> fallbackManifests;

    private volatile Status status = Status.IDLE;
    private volatile String message = "";
    private volatile List<Candidate> candidates = List.of();
    private volatile int scannedPacks;
    private volatile boolean skipped;

    /** 使用 {@link StarterConfig#FALLBACK_MANIFESTS} 作为备用地址。 */
    public UpdateChecker(StarterConfig config, Path taczRoot) {
        this(config, taczRoot, HttpFetcher::new, StarterConfig.FALLBACK_MANIFESTS);
    }

    public UpdateChecker(StarterConfig config, Path taczRoot, Function<Duration, HttpFetcher> fetcherFactory) {
        this(config, taczRoot, fetcherFactory, StarterConfig.FALLBACK_MANIFESTS);
    }

    /**
     * 完整构造。
     *
     * @param fallbackManifests 备用清单地址；传空列表表示只用主地址（测试用）
     */
    public UpdateChecker(StarterConfig config,
                         Path taczRoot,
                         Function<Duration, HttpFetcher> fetcherFactory,
                         List<String> fallbackManifests) {
        this.config = config;
        this.taczRoot = taczRoot;
        this.fetcherFactory = fetcherFactory;
        this.fallbackManifests = fallbackManifests == null ? List.of() : List.copyOf(fallbackManifests);
    }

    /**
     * 在后台线程启动检查。
     *
     * @param onComplete 检查结束时（含失败）在主线程之外的回调，可为 {@code null}。
     *                   界面不需要它也能靠轮询工作，它只是让需要即时反应的实现更省事。
     */
    public void startAsync(@Nullable Runnable onComplete) {
        Thread worker = new Thread(() -> {
            try {
                run();
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "autotacz-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    /** 同步执行检查。<b>会阻塞当前线程</b>，只应在后台线程调用。 */
    public void run() {
        try {
            status = Status.SCANNING;
            message = "正在扫描本地枪包…";

            GunpackIndex index = scanLocal();
            scannedPacks = index.size();

            List<InstalledPack> targets = selectTargets(index);
            if (targets.isEmpty()) {
                status = Status.DONE;
                message = "未找到匹配 " + String.join(" / ", config.targetPatterns()) + " 的本地枪包";
                return;
            }

            status = Status.FETCHING;
            message = "正在检查 " + targets.size() + " 个枪包的远程版本…";

            List<Candidate> found = fetchUpdates(targets);
            candidates = List.copyOf(found);
            status = Status.DONE;
            message = found.isEmpty() ? "所有枪包均为最新" : "发现 " + found.size() + " 个可更新枪包";
            LOGGER.info("更新检查完成: 扫描 {} 个枪包，匹配 {} 个，可更新 {} 个",
                    scannedPacks, targets.size(), found.size());
            for (Candidate candidate : found) {
                LOGGER.info("  可更新: {}", candidate.displayLine());
            }
        } catch (NetworkFailure e) {
            status = Status.NETWORK_ERROR;
            message = e.getMessage();
            LOGGER.warn("更新检查网络异常（可跳过）: {}", e.getMessage());
        } catch (IOException e) {
            // 清单解析失败也归入网络/源不可用，界面同样允许跳过
            status = Status.NETWORK_ERROR;
            message = "远程清单不可用：" + describe(e);
            LOGGER.warn("更新检查失败: {}", describe(e));
        } catch (RuntimeException e) {
            status = Status.FAILED;
            message = "检查过程出错：" + describe(e);
            LOGGER.error("更新检查异常", e);
        }
    }

    private GunpackIndex scanLocal() {
        // 启动阶段不计算哈希：只用于匹配 namespace，算哈希纯属浪费启动时间
        GunpackScanner.ScanResult result = new GunpackScanner(taczRoot, false).scan();
        if (result.hasFailures()) {
            LOGGER.warn("本地扫描有 {} 条失败", result.failures().size());
        }
        return GunpackIndex.from(result);
    }

    /** 按配置的模式筛选需要检查的本地枪包。 */
    private List<InstalledPack> selectTargets(GunpackIndex index) {
        if (matchesAll()) {
            return index.all();
        }
        List<InstalledPack> targets = new ArrayList<>();
        for (InstalledPack pack : index.all()) {
            if (matchesAnyPattern(pack)) {
                targets.add(pack);
            }
        }
        return targets;
    }

    /** 模式里出现 {@code *} 表示检查全部。 */
    private boolean matchesAll() {
        for (String pattern : config.targetPatterns()) {
            if ("*".equals(pattern.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyPattern(InstalledPack pack) {
        String namespace = pack.namespace().toLowerCase(Locale.ROOT);
        String name = pack.pack().displayName().toLowerCase(Locale.ROOT);
        String fileName = pack.pack().fileName().toLowerCase(Locale.ROOT);
        for (String raw : config.targetPatterns()) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty()) {
                continue;
            }
            if (namespace.contains(pattern) || name.contains(pattern) || fileName.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private List<Candidate> fetchUpdates(List<InstalledPack> targets) throws IOException {
        List<RemotePack> remotePacks = loadManifestWithFallback();

        List<Candidate> candidates = new ArrayList<>();
        for (InstalledPack local : targets) {
            RemotePack remote = findRemote(remotePacks, local.namespace());
            if (remote == null) {
                continue;
            }
            if (remote.isNewerThan(local.pack().version())) {
                candidates.add(new Candidate(local, remote));
            }
        }
        return candidates;
    }

    /**
     * 按「主地址 → 备用地址」依次尝试加载清单。
     *
     * <p>单点地址不可达在国内网络下很常见（CDN 被墙、IP 被阻断），
     * 多试一个地址就能避免把「换个域名就能好」的情况误报成网络异常。
     * 全部失败才抛 {@link NetworkFailure}，让界面提供跳过选项。
     *
     * <p>备用地址来自构造参数 {@code fallbackManifests}，
     * 不在方法内硬编码——否则测试无法隔离网络，会把「真连上了备用地址」
     * 误判成逻辑错误。
     */
    private List<RemotePack> loadManifestWithFallback() throws IOException {
        List<String> urls = new ArrayList<>();
        urls.add(config.manifestUrl());
        for (String fallback : fallbackManifests) {
            if (!urls.contains(fallback)) {
                urls.add(fallback);
            }
        }

        HttpFetcher fetcher = fetcherFactory.apply(Duration.ofSeconds(config.timeoutSeconds()));
        IOException lastError = null;

        for (String url : urls) {
            if (!isHttpUrl(url)) {
                lastError = new NetworkFailure("清单地址无效：" + url);
                continue;
            }
            try {
                JsonManifestSource source = new JsonManifestSource("starter", URI.create(url), fetcher);
                List<RemotePack> packs = source.load();
                if (urls.indexOf(url) > 0) {
                    LOGGER.info("主清单不可用，已使用备用地址: {}", url);
                }
                return packs;
            } catch (IOException e) {
                lastError = e;
                LOGGER.debug("清单地址不可用，尝试下一个: {} ({})", url, describe(e));
            }
        }
        throw new NetworkFailure("无法连接远程清单（"
                + (lastError == null ? "没有可用的清单地址" : describe(lastError)) + "）");
    }

    @Nullable
    private static RemotePack findRemote(List<RemotePack> remotePacks, String namespace) {
        String target = namespace.toLowerCase(Locale.ROOT);
        for (RemotePack remote : remotePacks) {
            if (remote.namespace().toLowerCase(Locale.ROOT).equals(target)) {
                return remote;
            }
        }
        return null;
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    // ------------------------------------------------------------ 状态读取

    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    public int scannedPacks() {
        return scannedPacks;
    }

    /** 界面是否应该放行进入游戏（无需用户再操作）。 */
    public boolean canProceedAutomatically() {
        return status == Status.DONE && (candidates.isEmpty() && config.autoContinue());
    }

    /** 是否处于「可以让用户选择跳过」的状态。 */
    public boolean canSkip() {
        return status == Status.NETWORK_ERROR || status == Status.FAILED;
    }

    /** 是否已结束（无论成功失败）。 */
    public boolean finished() {
        return status == Status.DONE || status == Status.NETWORK_ERROR || status == Status.FAILED;
    }

    public void markSkipped() {
        this.skipped = true;
    }

    public boolean skipped() {
        return skipped;
    }

    public StarterConfig config() {
        return config;
    }

    /** 供界面一次性取走状态，避免多次 volatile 读出现不一致。 */
    public Snapshot snapshot() {
        return new Snapshot(status, message, candidates, scannedPacks);
    }

    private static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder(error.getClass().getSimpleName());
        Throwable cursor = error;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && !msg.isBlank()) {
                return sb.append(": ").append(msg).toString();
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    /** 检查阶段。 */
    public enum Status {
        /** 尚未开始 */
        IDLE,
        /** 正在扫描本地枪包 */
        SCANNING,
        /** 正在请求远程清单 */
        FETCHING,
        /** 完成（可能有也可能没有更新） */
        DONE,
        /** 网络异常，允许跳过 */
        NETWORK_ERROR,
        /** 其他错误，允许跳过 */
        FAILED
    }

    /**
     * 一条可更新记录。
     *
     * @param local  本地条目
     * @param remote 远程记录
     */
    public record Candidate(InstalledPack local, RemotePack remote) {

        public String displayLine() {
            return local.pack().displayName()
                    + "  " + local.pack().version() + " → " + remote.version();
        }
    }

    /** 界面同一帧读取的一致状态。 */
    public record Snapshot(Status status, String message, List<Candidate> candidates, int scannedPacks) {
    }

    /**
     * 网络相关的可跳过异常。
     *
     * <p>单独建类型是为了把「没网，可以跳过」与「代码有 bug」区分开：
     * 前者是正常场景，不应打 error 日志。
     */
    public static final class NetworkFailure extends IOException {
        public NetworkFailure(String message) {
            super(message);
        }
    }
}