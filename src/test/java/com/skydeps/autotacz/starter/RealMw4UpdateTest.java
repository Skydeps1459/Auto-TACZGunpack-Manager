package com.skydeps.autotacz.starter;

import com.skydeps.autotacz.config.StarterConfig;
import com.skydeps.autotacz.scan.GunpackScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用<b>真实 MW4 枪包</b>验证完整更新链路。
 *
 * <p>与 {@link UpdateFlowIntegrationTest} 的区别：那边用 693 字节的假包验证逻辑，
 * 这边用 86MB 的真实包验证「TACZ 实际格式」能被正确解析、哈希能对上、
 * 替换后游戏侧确实认出新版本。
 *
 * <p>依赖本地服务提供真实枪包（见 README「测试用的本地清单服务」）；
 * 未就绪时自动跳过，不影响常规构建。
 */
class RealMw4UpdateTest {

    private static final String BASE = "http://127.0.0.1:8977";
    private static final String REAL_VERSION = "1.13.0-Release";

    private static boolean realPackAvailable() {
        try {
            var conn = (java.net.HttpURLConnection)
                    java.net.URI.create(BASE + "/packs-mw4.json").toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            boolean ok = conn.getResponseCode() == 200;
            conn.disconnect();
            return ok && Files.isRegularFile(Path.of("testserver", "mw4-real.zip"));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("真实包：1.12.0 更新到 1.13.0-Release，哈希校验通过")
    void updatesRealMw4Pack(@TempDir Path gameDir) throws IOException {
        assumeTrue(realPackAvailable(), "真实 MW4 枪包或本地服务未就绪，跳过");

        Path tacz = gameDir.resolve("tacz");
        // 造一个「旧版 MW4」：namespace 与真实包一致，版本更低
        Path old = tacz.resolve("mw4-old");
        Files.createDirectories(old.resolve("assets").resolve("mw4"));
        Files.writeString(old.resolve("gunpack.meta.json"),
                "{ \"namespace\": \"mw4\" }", StandardCharsets.UTF_8);
        Files.writeString(old.resolve("assets").resolve("mw4").resolve("gunpack_info.json"),
                "{\"name\": \"Modern Warfare 4\", \"version\": \"1.12.0-Release\"}",
                StandardCharsets.UTF_8);

        StarterConfig config = new StarterConfig(
                true, BASE + "/packs-mw4.json", List.of("mw4"), 30, true);
        UpdateChecker checker = new UpdateChecker(config, tacz);
        checker.run();

        assertEquals(UpdateChecker.Status.DONE, checker.status(), "检查应完成: " + checker.message());
        assertEquals(1, checker.candidates().size(), "应发现 1 个更新");
        assertEquals(REAL_VERSION, checker.candidates().get(0).remote().version());

        var results = new UpdateApplier(tacz).applyAll(checker.candidates(), null);
        assertTrue(results.get(0).ok(), "真实包更新应成功: " + results.get(0).error());
        assertEquals(REAL_VERSION, results.get(0).version());

        // 重新扫描：应从真实包的 gunpack_info.json 读出 1.13.0-Release 与真实作者
        var rescan = new GunpackScanner(tacz, true).scan();
        var updated = rescan.packs().stream()
                .filter(p -> p.namespace().equals("mw4"))
                .findFirst()
                .orElseThrow();

        assertEquals(REAL_VERSION, updated.pack().version(), "版本应为真实包内声明的值");
        assertEquals("Modern Warfare 4", updated.pack().displayName());
        assertTrue(updated.pack().authors().contains("MoreFun"), "作者应来自真实包");
        assertEquals(17, updated.pack().content().guns(), "枪械数量应解析正确");
        assertEquals(12, updated.pack().content().ammo());
        assertEquals(5, updated.pack().content().attachments());
        assertTrue(updated.pack().source().hasHash(), "应记录 SHA-256");
    }

    @Test
    @DisplayName("真实包：同版本不报更新，避免每次启动都提示")
    void sameVersionIsNotAnUpdate(@TempDir Path gameDir) throws IOException {
        assumeTrue(realPackAvailable(), "真实 MW4 枪包或本地服务未就绪，跳过");

        Path tacz = gameDir.resolve("tacz");
        Path current = tacz.resolve("mw4-current");
        Files.createDirectories(current.resolve("assets").resolve("mw4"));
        Files.writeString(current.resolve("gunpack.meta.json"),
                "{ \"namespace\": \"mw4\" }", StandardCharsets.UTF_8);
        Files.writeString(current.resolve("assets").resolve("mw4").resolve("gunpack_info.json"),
                "{\"name\": \"Modern Warfare 4\", \"version\": \"" + REAL_VERSION + "\"}",
                StandardCharsets.UTF_8);

        StarterConfig config = new StarterConfig(
                true, BASE + "/packs-mw4.json", List.of("mw4"), 30, true);
        UpdateChecker checker = new UpdateChecker(config, tacz);
        checker.run();

        assertEquals(UpdateChecker.Status.DONE, checker.status());
        assertTrue(checker.candidates().isEmpty(),
                "本地已是 " + REAL_VERSION + "，不应再提示更新");
        assertTrue(checker.canProceedAutomatically(), "无更新时应可直接进游戏");
    }
}