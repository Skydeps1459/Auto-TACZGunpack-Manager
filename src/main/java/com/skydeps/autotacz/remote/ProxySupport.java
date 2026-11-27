package com.skydeps.autotacz.remote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 代理自动发现。
 *
 * <p>存在的理由：Java 的 {@link java.net.http.HttpClient} 缺省<b>不</b>读取
 * 操作系统代理设置，也不读取 {@code HTTP_PROXY} 环境变量。而相当一部分玩家
 * （尤其是国内玩家）靠代理工具或游戏加速器上网，直连 GitHub 会超时。
 * 如果不做这层适配，这些玩家每次启动都会看到「网络异常」。
 *
 * <p>探测顺序（先命中先用）：
 * <ol>
 *   <li>环境变量 {@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code ALL_PROXY}
 *       —— 代理软件普遍会设置，且与 JVM 启动参数无关</li>
 *   <li>{@link ProxySelector#getDefault()} —— 覆盖「游戏内代理设置」与
 *       {@code -Dhttp.proxyHost} 启动参数</li>
 *   <li>都没有 → 返回 {@code null}，{@code HttpClient} 走直连</li>
 * </ol>
 *
 * <p>无论走哪条路径，{@code localhost} / {@code 127.0.0.1} / {@code ::1}
 * 以及 {@code NO_PROXY} 列表里的主机都强制直连。这一条不能用「顺手」来形容：
 * 本地回环流量一旦被塞进代理，集成测试和局域网资源都会莫名其妙地失败。
 */
public final class ProxySupport {

    /** 环境变量候选，按优先级排列。大小写两种写法都要认，Linux 下常见小写。 */
    private static final List<String> PROXY_ENV_KEYS = List.of(
            "HTTPS_PROXY", "https_proxy",
            "HTTP_PROXY", "http_proxy",
            "ALL_PROXY", "all_proxy");

    /** {@code NO_PROXY} 也要两种大小写都认。 */
    private static final List<String> NO_PROXY_ENV_KEYS = List.of("NO_PROXY", "no_proxy");

    /** 永远直连的主机。写死在代码里，不依赖 {NO_PROXY} 是否被正确设置。 */
    private static final List<String> LOCAL_HOSTS =
            List.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    private ProxySupport() {
    }

    /**
     * 探测当前环境应使用的代理。
     *
     * @return 可用的 {@link ProxySelector}；无需代理时返回 {@code null}
     */
    public static ProxySelector resolve() {
        return resolve(System::getenv);
    }

    /**
     * 可注入环境变量来源的版本，便于测试。
     *
     * @param env 环境变量读取函数
     * @return 可用的 {@link ProxySelector}；无需代理时返回 {@code null}
     */
    static ProxySelector resolve(Function<String, String> env) {
        ProxySelector fromEnv = fromEnvironment(env);
        if (fromEnv != null) {
            return fromEnv;
        }

        ProxySelector system = ProxySelector.getDefault();
        if (system == null || !usesProxy(system)) {
            return null;
        }
        return new BypassSelector(system, firstNonBlank(env, NO_PROXY_ENV_KEYS));
    }

    /**
     * 从环境变量构造代理选择器。
     *
     * @return 未配置或配置无法解析时返回 {@code null}
     */
    static ProxySelector fromEnvironment(Function<String, String> env) {
        for (String key : PROXY_ENV_KEYS) {
            String raw = env.apply(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Proxy proxy = parseProxy(raw);
            if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
                continue;
            }
            return new BypassSelector(fixed(proxy), firstNonBlank(env, NO_PROXY_ENV_KEYS));
        }
        return null;
    }

    /**
     * 解析 {@code scheme://[user:pass@]host[:port]} 形式的代理地址。
     *
     * <p>刻意宽松：玩家环境里这些字符串格式五花八门，解析失败就当没配代理，
     * 而不是抛异常——「代理地址写错了」不该让游戏启动失败。
     *
     * @return 解析成功返回 {@link Proxy}；失败返回 {@code null}
     */
    static Proxy parseProxy(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        Proxy.Type type = Proxy.Type.HTTP;
        String scheme = "";
        int schemeEnd = value.indexOf("://");
        if (schemeEnd > 0) {
            scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            value = value.substring(schemeEnd + 3);
        } else if (value.startsWith("socks5h:")) {
            scheme = "socks5";
            value = value.substring("socks5h:".length());
        }

        if (scheme.startsWith("socks")) {
            type = Proxy.Type.SOCKS;
        } else if (!scheme.isEmpty() && !scheme.equals("http") && !scheme.equals("https")) {
            // 未知协议（如 pac 文件、直接域名）无法用，交给调用方跳过
            return null;
        }

        // 去掉路径部分与凭据部分
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.isEmpty()) {
            return null;
        }

        String host;
        int port;
        if (value.startsWith("[")) {
            // IPv6 字面量：[::1]:8080
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = value.substring(0, close + 1);
            String rest = value.substring(close + 1);
            port = rest.startsWith(":") ? parsePort(rest.substring(1), -1) : -1;
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0) {
                host = value.substring(0, colon);
                port = parsePort(value.substring(colon + 1), -1);
            } else {
                host = value;
                port = -1;
            }
        }

        if (host.isEmpty()) {
            return null;
        }
        if (port <= 0) {
            port = type == Proxy.Type.SOCKS ? 1080 : ("https".equals(scheme) ? 443 : 80);
        }

        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /** 判断目标主机是否应绕过代理。 */
    static boolean isBypassed(String host, String noProxySpec) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        if (LOCAL_HOSTS.contains(candidate)) {
            return true;
        }
        if (noProxySpec == null || noProxySpec.isBlank()) {
            return false;
        }
        for (String rawRule : noProxySpec.split(",")) {
            String rule = rawRule.trim().toLowerCase(Locale.ROOT);
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.equals("*")) {
                return true;
            }
            // 去掉可能的端口后缀（IPv6 规则里的方括号不处理，属极端情况）
            int colon = rule.lastIndexOf(':');
            if (colon > 0 && rule.indexOf(']') < 0) {
                rule = rule.substring(0, colon);
            }
            while (rule.startsWith(".")) {
                rule = rule.substring(1);
            }
            if (rule.isEmpty()) {
                continue;
            }
            if (candidate.equals(rule) || candidate.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }

    /** 判断系统选择器是否真的会走代理（缺省全是 DIRECT）。 */
    private static boolean usesProxy(ProxySelector selector) {
        try {
            List<Proxy> proxies = selector.select(URI.create("https://example.com/"));
            if (proxies == null) {
                return false;
            }
            for (Proxy proxy : proxies) {
                if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // 系统选择器在无 GUI 环境可能抛异常，视为「没有代理」
            return false;
        }
    }

    private static ProxySelector fixed(Proxy proxy) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                // 单一固定代理没有可切换目标，忽略即可
            }
        };
    }

    private static String firstNonBlank(Function<String, String> env, List<String> keys) {
        for (String key : keys) {
            String value = env.apply(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int parsePort(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 包一层绕过规则，其余交回委托选择器。 */
    private static final class BypassSelector extends ProxySelector {

        private final ProxySelector delegate;
        private final String noProxySpec;

        BypassSelector(ProxySelector delegate, String noProxySpec) {
            this.delegate = delegate;
            this.noProxySpec = noProxySpec;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) {
                return List.of(Proxy.NO_PROXY);
            }
            if (isBypassed(uri.getHost(), noProxySpec)) {
                return List.of(Proxy.NO_PROXY);
            }
            List<Proxy> selected = delegate.select(uri);
            if (selected == null || selected.isEmpty()) {
                return List.of(Proxy.NO_PROXY);
            }
            return selected;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            try {
                delegate.connectFailed(uri, address, e);
            } catch (RuntimeException ignored) {
                // 委托方不认这个回调也没关系，调用方会自行降级为「网络异常」
            }
        }
    }
}