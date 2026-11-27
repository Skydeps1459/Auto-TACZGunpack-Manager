package com.skydeps.autotacz.remote;

import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代理探测的单元测试。
 *
 * <p>关注的是「玩家环境里真实出现的写法能不能被认出来」，
 * 以及「本地回环绝不能被塞进代理」这条硬规则。
 */
class ProxySupportTest {

    private static Function<String, String> env(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map::get;
    }

    @Test
    void parsesPlainHostPort() {
        Proxy proxy = ProxySupport.parseProxy("127.0.0.1:2080");
        assertNotNull(proxy);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertTrue(proxy.address().toString().contains("127.0.0.1"));
        assertTrue(proxy.address().toString().endsWith(":2080"));
    }

    @Test
    void parsesHttpScheme() {
        Proxy proxy = ProxySupport.parseProxy("http://127.0.0.1:7890");
        assertNotNull(proxy);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertTrue(proxy.address().toString().contains("7890"));
    }

    @Test
    void parsesSocks5Scheme() {
        Proxy proxy = ProxySupport.parseProxy("socks5://127.0.0.1:1080");
        assertNotNull(proxy);
        assertEquals(Proxy.Type.SOCKS, proxy.type());
        // SOCKS 在 Proxy 里保存为未解析地址，用 toString 断言即可
        assertTrue(proxy.address().toString().toLowerCase().contains("127.0.0.1"));
    }

    @Test
    void parsesSocks5hWithoutSlashes() {
        Proxy proxy = ProxySupport.parseProxy("socks5h:localhost:1080");
        assertNotNull(proxy);
        assertEquals(Proxy.Type.SOCKS, proxy.type());
    }

    @Test
    void stripsCredentialsAndTrailingSlash() {
        Proxy proxy = ProxySupport.parseProxy("http://user:p%40ss@10.0.0.2:3128/");
        assertNotNull(proxy);
        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertTrue(proxy.address().toString().contains("10.0.0.2:3128"));
        assertFalse(proxy.address().toString().contains("user"));
    }

    @Test
    void appliesDefaultPorts() {
        Proxy http = ProxySupport.parseProxy("http://proxy.lan");
        assertNotNull(http);
        assertTrue(http.address().toString().endsWith(":80"));

        Proxy https = ProxySupport.parseProxy("https://proxy.lan");
        assertNotNull(https);
        assertTrue(https.address().toString().endsWith(":443"));

        Proxy socks = ProxySupport.parseProxy("socks5://proxy.lan");
        assertNotNull(socks);
        assertTrue(socks.address().toString().endsWith(":1080"));
    }

    @Test
    void rejectsUnusableValues() {
        assertNull(ProxySupport.parseProxy(null));
        assertNull(ProxySupport.parseProxy("   "));
        // PAC 文件无法被 HttpClient 使用，应视为「没配代理」而不是崩溃
        assertNull(ProxySupport.parseProxy("pac+http://example.com/proxy.pac"));
    }

    @Test
    void readsUppercaseEnvFirst() {
        ProxySelector selector = ProxySupport.fromEnvironment(
                env("HTTPS_PROXY", "http://1.2.3.4:8080", "HTTP_PROXY", "http://9.9.9.9:9999"));
        assertNotNull(selector);
        List<Proxy> proxies = selector.select(URI.create("https://cdn.jsdelivr.net/x.json"));
        assertEquals(1, proxies.size());
        assertTrue(proxies.get(0).address().toString().contains("1.2.3.4:8080"));
    }

    @Test
    void readsLowercaseEnvWhenUppercaseMissing() {
        ProxySelector selector = ProxySupport.fromEnvironment(env("https_proxy", "http://1.2.3.4:8080"));
        assertNotNull(selector);
        List<Proxy> proxies = selector.select(URI.create("https://example.com/"));
        assertTrue(proxies.get(0).address().toString().contains("1.2.3.4:8080"));
    }

    @Test
    void returnsNullWhenNoEnvConfigured() {
        assertNull(ProxySupport.fromEnvironment(env()));
    }

    @Test
    void localhostAlwaysBypassesProxy() {
        ProxySelector selector = ProxySupport.fromEnvironment(env("HTTPS_PROXY", "http://1.2.3.4:8080"));
        assertNotNull(selector);
        // 这条是集成测试的生命线：本地 127.0.0.1 服务不能被代理拦走
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://127.0.0.1:8977/packs.json")).get(0));
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://localhost:8977/packs.json")).get(0));
        assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://[::1]:8977/packs.json")).get(0));
    }

    @Test
    void honorsNoProxyList() {
        ProxySelector selector = ProxySupport.fromEnvironment(
                env("HTTPS_PROXY", "http://1.2.3.4:8080", "NO_PROXY", "example.com,.internal.lan,10.0.0.0/8"));
        assertNotNull(selector);

        assertEquals(Proxy.NO_PROXY,
                selector.select(URI.create("https://example.com/a")).get(0));
        assertEquals(Proxy.NO_PROXY,
                selector.select(URI.create("https://api.example.com/a")).get(0));
        assertEquals(Proxy.NO_PROXY,
                selector.select(URI.create("https://box.internal.lan/a")).get(0));

        assertFalse(Proxy.NO_PROXY.equals(
                selector.select(URI.create("https://cdn.jsdelivr.net/a")).get(0)));
    }

    @Test
    void noProxyWildcardDisablesProxy() {
        assertTrue(ProxySupport.isBypassed("anything.com", "*"));
    }

    @Test
    void bypassMatchesSuffixNotSubstring() {
        // 防止把 "notexample.com" 误判为命中 "example.com"
        assertFalse(ProxySupport.isBypassed("notexample.com", "example.com"));
        assertTrue(ProxySupport.isBypassed("cdn.example.com", "example.com"));
    }
}