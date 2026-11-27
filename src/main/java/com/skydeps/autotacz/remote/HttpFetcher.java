package com.skydeps.autotacz.remote;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 极薄的 HTTP 客户端封装。
 *
 * <p>存在的理由：把所有网络细节（超时、UA、重定向、大小上限、代理）
 * 集中在一处，让 {@link GunpackDownloader} 和各个源实现保持干净。
 * 换用其他 HTTP 栈时只改本类。
 *
 * <h2>为什么必须开 {@link HttpClient.Redirect#NORMAL}</h2>
 * <p>GitHub Release 的下载地址会返回 <b>302</b>，跳到
 * {@code release-assets.githubusercontent.com}（跨域）。不跟随重定向就永远
 * 拿不到资产。这里用 {@code NORMAL} 而非 {@code ALWAYS} 是刻意的：
 * {@code ALWAYS} 会跟随 https→http 的降级跳转，等于允许中间人把下载内容换掉。
 *
 * <h2>两种超时不是同一回事</h2>
 * <ul>
 *   <li>{@code connectTimeout = 15s} —— 建立 TCP 连接的上限，全局固定</li>
 *   <li>{@code requestTimeout} —— 单次请求的上限，元数据用 20 秒</li>
 * </ul>
 * <p>下载走 {@link #openStream} 单独设 10 分钟：86 MB 的枪包在慢速网络上
 * 远超 20 秒，用元数据的超时会必然失败。
 *
 * <p>代理探测见 {@link ProxySupport}：Java 的 {@code HttpClient} 缺省既不读系统
 * 代理也不读 {@code HTTP_PROXY}，不补这一层会让使用加速器的玩家一律报「网络异常」。
 */
public final class HttpFetcher {

    /**
     * 默认 UA：部分站点会拒绝没有 UA 的请求。
     *
     * <p>带上项目地址是 GitHub API 的礼貌要求——它们靠 UA 识别客户端，
     * 出问题时也能反查到是谁在请求。
     */
    private static final String USER_AGENT =
            "AutoTACZGunpackManager (+https://github.com/Skydeps1459/Auto-TACZGunpack-Manager)";

    /**
     * 元数据请求的大小上限，防止恶意源返回超大响应撑爆内存。
     *
     * <p>8 MiB 对 JSON 清单来说极其宽松（真实清单只有 1 KB 级），
     * 但足以拦住"返回一个 10 GB 流"这种攻击。
     */
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;

    private final HttpClient client;
    private final Duration requestTimeout;

    /** 使用默认超时（20 秒）与自动探测到的代理。 */
    public HttpFetcher() {
        this(Duration.ofSeconds(20));
    }

    /**
     * @param requestTimeout 单次请求（元数据）超时；下载不受此限制
     */
    public HttpFetcher(Duration requestTimeout) {
        this(requestTimeout, ProxySupport.resolve());
    }

    /**
     * 显式指定代理选择器的构造函数。
     *
     * @param requestTimeout 单次请求超时
     * @param proxySelector  代理选择器；{@code null} 表示直连
     */
    public HttpFetcher(Duration requestTimeout, ProxySelector proxySelector) {
        this.requestTimeout = requestTimeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.client = builder.build();
    }

    /**
     * 抓取文本内容（JSON / 纯文本）。
     *
     * <p>返回值语义是「拿到了」还是「没拿到」，不区分具体原因：
     * 非 2xx（404、403 限流、500）一律返回 {@link Optional#empty()}。
     * 区分这些对调用方没有意义——清单拉不到时上层只会切备用地址或报网络异常。
     *
     * <p>但「响应体过大」是例外，会抛 {@link IOException}：这不是"清单不存在"，
     * 而是有人在喂异常数据，应该显式失败而不是静默当成空清单。
     *
     * @param uri 目标地址
     * @return 响应体；HTTP 状态非 2xx 时返回 {@link Optional#empty()}
     * @throws IOException 网络错误或响应体超过 8 MiB
     */
    public Optional<String> fetchText(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .timeout(requestTimeout)
                .GET()
                .build();

        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() / 100 != 2) {
            return Optional.empty();
        }
        byte[] body = response.body();
        if (body == null) {
            return Optional.empty();
        }
        if (body.length > MAX_TEXT_BYTES) {
            throw new IOException("响应过大: " + body.length + " 字节 (" + uri + ")");
        }
        return Optional.of(new String(body, StandardCharsets.UTF_8));
    }

    /** 统一发送并转译中断异常。 */
    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + request.uri(), e);
        }
    }

    /**
     * 打开二进制流，供下载器边读边写。
     *
     * <p>不使用 {@code BodyHandlers.ofFile} 是因为下载过程需要
     * 计算哈希并支持进度回调。
     *
     * <p><b>调用方负责关闭返回的流</b>——包括状态码非 2xx 的情况，
     * 否则连接会泄漏。{@link GunpackDownloader#download} 里用
     * {@code try-with-resources} 与 {@code closeQuietly} 两种方式覆盖了这两条路径。
     *
     * <p>超时固定 10 分钟，不跟随 {@code requestTimeout}：见类注释关于两种超时的说明。
     */
    public HttpResponse<java.io.InputStream> openStream(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断: " + uri, e);
        }
    }
}