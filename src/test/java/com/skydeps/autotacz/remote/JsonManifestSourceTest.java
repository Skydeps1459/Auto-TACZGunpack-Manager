package com.skydeps.autotacz.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 清单解析的容错边界。
 *
 * <p>清单可能由第三方维护，因此必须能安全处理：
 * 缺字段、类型不对、带注释、整体不是预期结构。
 */
class JsonManifestSourceTest {

    @Test
    @DisplayName("解析标准清单，字段完整映射")
    void parsesStandardManifest() throws IOException {
        String json = """
                {
                  "packs": [
                    {
                      "namespace": "tacz_default",
                      "name": "默认枪包",
                      "version": "1.1.0",
                      "url": "https://example.com/a.zip",
                      "page": "https://example.com/a",
                      "size": 2048,
                      "sha256": "abc123"
                    }
                  ]
                }
                """;
        List<RemotePack> packs = JsonManifestSource.parse(json, "test");
        assertEquals(1, packs.size());

        RemotePack pack = packs.get(0);
        assertEquals("tacz_default", pack.namespace());
        assertEquals("默认枪包", pack.displayName());
        assertEquals("1.1.0", pack.version());
        assertEquals("https://example.com/a.zip", pack.downloadUrl());
        assertEquals(2048, pack.sizeBytes());
        assertTrue(pack.hasChecksum());
        assertEquals("test", pack.sourceName());
    }

    @Test
    @DisplayName("顶层直接是数组也能解析")
    void acceptsTopLevelArray() throws IOException {
        String json = """
                [ { "namespace": "a", "url": "https://example.com/a.zip" } ]
                """;
        assertEquals(1, JsonManifestSource.parse(json, "t").size());
    }

    @Test
    @DisplayName("清单允许 // 行注释")
    void toleratesLineComments() throws IOException {
        String json = """
                {
                  // 维护者备注
                  "packs": [
                    { "namespace": "a", "url": "https://example.com/a.zip" }
                  ]
                }
                """;
        assertEquals(1, JsonManifestSource.parse(json, "t").size());
    }

    @Test
    @DisplayName("缺少必需字段的条目被跳过而不是让整份清单失败")
    void skipsIncompleteEntries() throws IOException {
        String json = """
                {
                  "packs": [
                    { "namespace": "good", "url": "https://example.com/good.zip" },
                    { "namespace": "no-url" },
                    { "url": "https://example.com/no-namespace.zip" },
                    { "namespace": "", "url": "https://example.com/blank.zip" }
                  ]
                }
                """;
        List<RemotePack> packs = JsonManifestSource.parse(json, "t");
        assertEquals(1, packs.size(), "只有一条完整条目应保留");
        assertEquals("good", packs.get(0).namespace());
    }

    @Test
    @DisplayName("缺少 packs 数组时报错")
    void rejectsMissingPacksArray() {
        assertThrows(IOException.class,
                () -> JsonManifestSource.parse("{ \"items\": [] }", "t"));
    }

    @Test
    @DisplayName("非法 JSON 转为 IOException 而非运行时异常")
    void invalidJsonBecomesIoException() {
        assertThrows(IOException.class,
                () -> JsonManifestSource.parse("not json at all", "t"));
    }

    @Test
    @DisplayName("空清单返回空列表")
    void emptyListIsValid() throws IOException {
        List<RemotePack> packs = JsonManifestSource.parse("{ \"packs\": [] }", "t");
        assertTrue(packs.isEmpty());
    }

    @Test
    @DisplayName("未提供 sha256 时 hasChecksum 为 false")
    void missingChecksumIsDetectable() throws IOException {
        List<RemotePack> packs = JsonManifestSource.parse(
                "[{ \"namespace\": \"a\", \"url\": \"https://e.com/a.zip\" }]", "t");
        assertFalse(packs.get(0).hasChecksum());
    }

    @Test
    @DisplayName("RemotePack 判断更新时不做虚假提示")
    void newerComparisonIsSane() {
        RemotePack pack = new RemotePack("ns", "n", "2.0.0",
                "https://e.com/a.zip", "", -1, null, "t");
        assertTrue(pack.isNewerThan("1.0.0"));
        assertFalse(pack.isNewerThan("2.0.0"));
        assertFalse(pack.isNewerThan("3.0.0"));
    }

    /**
     * 校验仓库根目录真正在用的 {@code packs.json}。
     *
     * <p>这份文件是所有玩家的更新源，改坏了影响面很大：字段拼错、JSON 语法错误、
     * 或者 namespace 与实际枪包不一致，都会让更新检查静默失效。
     * 放到测试里，CI 与本地 {@code ./gradlew test} 都能拦住。
     *
     * <p>只做离线校验（解析 + 字段自洽），不发网络请求——
     * 网络测试在 CI 里不可靠，可达性由集成测试覆盖。
     */
    @Test
    @DisplayName("仓库内的 packs.json 可被正常解析且字段自洽")
    void realManifestIsValid() throws IOException {
        Path manifest = Path.of("packs.json");
        assumeTrue(Files.isRegularFile(manifest), "packs.json 不在工作目录，跳过");

        List<RemotePack> packs = JsonManifestSource.parse(
                Files.readString(manifest, StandardCharsets.UTF_8), "repo");
        assertFalse(packs.isEmpty(), "清单不应为空，否则更新检查形同虚设");

        Set<String> seen = new HashSet<>();
        for (RemotePack pack : packs) {
            assertFalse(pack.namespace().isBlank(), "namespace 不能为空");
            assertTrue(seen.add(pack.namespace().toLowerCase(Locale.ROOT)),
                    "namespace 重复: " + pack.namespace() + "（findByNamespace 只认第一条）");

            assertTrue(pack.downloadUrl().startsWith("https://"),
                    "下载地址应为 https: " + pack.downloadUrl());
            assertTrue(pack.downloadUrl().endsWith(".zip"),
                    "下载地址应指向 .zip: " + pack.downloadUrl());

            assertFalse(pack.version().isBlank(),
                    pack.namespace() + " 缺少 version，会导致永不提示更新");

            // 有 size 就该有 sha256：只声明大小不声明摘要，等于校验了个寂寞
            if (pack.sizeBytes() > 0) {
                assertTrue(pack.hasChecksum(),
                        pack.namespace() + " 声明了 size 却没给 sha256，无法校验完整性");
            }
            if (pack.hasChecksum()) {
                assertEquals(64, pack.sha256().length(),
                        pack.namespace() + " 的 sha256 长度应为 64（小写十六进制）");
                assertTrue(pack.sha256().equals(pack.sha256().toLowerCase(Locale.ROOT)),
                        pack.namespace() + " 的 sha256 应使用小写");
            }
        }
    }
}