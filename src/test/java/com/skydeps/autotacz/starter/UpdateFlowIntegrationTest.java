package com.skydeps.autotacz.starter;

import com.skydeps.autotacz.config.StarterConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对着真实 HTTP 服务跑的端到端测试：发现更新 → 下载 → 校验 → 替换。
 *
 * <p>带 {@code @EnabledIf} 的本地服务不可用时自动跳过，避免在无服务环境下误报失败。
 * 这是唯一能证明「下载并替换」链路真实可用的测试，因此值得保留在仓库里。
 */
class UpdateFlowIntegrationTest {

    private static final String BASE = "http://127.0.0.1:8977";

    private static boolean serverUp() {
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) java.net.URI.create(BASE + "/packs.json").toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("端到端：发现 mw4 更新并成功替换本地枪包")
    void fullUpdateFlow(@TempDir Path gameDir) throws IOException {
        assumeTrue(serverUp(), "本地测试服务未启动，跳过");

        Path tacz = gameDir.resolve("tacz");
        Path pack = tacz.resolve("mw4-core");
        writePack(pack, "mw4_core", "1.0.0", "old");

        StarterConfig config = new StarterConfig(true, BASE + "/packs.json", List.of("mw4"), 5, true);
        UpdateChecker checker = new UpdateChecker(config, tacz);
        checker.run();

        assertEquals(UpdateChecker.Status.DONE, checker.status(), "检查应正常完成");
        assertEquals(1, checker.candidates().size(), "应发现 1 个更新");
        assertEquals("2.0.0", checker.candidates().get(0).remote().version());

        UpdateApplier applier = new UpdateApplier(tacz);
        List<UpdateApplier.ApplyResult> results = applier.applyAll(checker.candidates(), null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).ok(), "更新应成功: " + results.get(0).error());
        assertEquals("2.0.0", results.get(0).version());

        // 新包应是 zip 形态，且旧的目录形态已被移走
        Path newZip = tacz.resolve("mw4_core.zip");
        assertTrue(Files.isRegularFile(newZip), "应生成 zip 形态的新包");

        // 重新扫描确认版本已变
        var rescan = new com.skydeps.autotacz.scan.GunpackScanner(tacz, false).scan();
        var updated = rescan.packs().stream()
                .filter(p -> p.namespace().equals("mw4_core"))
                .findFirst()
                .orElseThrow();
        assertEquals("2.0.0", updated.pack().version());
        assertNotEquals("1.0.0", updated.pack().version());
    }

    @Test
    @DisplayName("清单声明 sha256 与下载内容不符时拒绝安装")
    void checksumMismatchRejectsInstall(@TempDir Path gameDir) throws IOException {
        assumeTrue(serverUp(), "本地测试服务未启动，跳过");

        Path tacz = gameDir.resolve("tacz");
        writePack(tacz.resolve("mw4-core"), "mw4_core", "1.0.0", "old");

        // 直接构造指向真实 zip 但声明错误哈希的候选
        var remote = new com.skydeps.autotacz.remote.RemotePack(
                "mw4_core", "MW4 Core", "2.0.0",
                BASE + "/mw4-core.zip", "", -1,
                "0000000000000000000000000000000000000000000000000000000000000000",
                "test");

        var local = new com.skydeps.autotacz.scan.GunpackScanner(tacz, false)
                .scan().packs().stream()
                .filter(p -> p.namespace().equals("mw4_core"))
                .findFirst()
                .orElseThrow();

        UpdateApplier applier = new UpdateApplier(tacz);
        var result = applier.apply(new UpdateChecker.Candidate(local, remote));

        assertTrue(!result.ok(), "哈希不符必须失败");
        assertTrue(result.error() != null && result.error().contains("校验"),
                "错误信息应说明是校验失败，实际: " + result.error());

        // 旧包必须还在（回滚生效），且没有留下 .part 残file
        var rescan = new com.skydeps.autotacz.scan.GunpackScanner(tacz, false).scan();
        assertEquals(1, rescan.packs().size(), "旧包应被回滚保留");
        assertEquals("1.0.0", rescan.packs().get(0).pack().version());
        try (var files = Files.list(tacz)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                    "不应留下临时文件");
        }
    }

    @Test
    @DisplayName("回归：旧包为目录形态时也能成功更新（曾因 DirectoryNotEmptyException 失败）")
    void updatesDirectoryPack(@TempDir Path gameDir) throws IOException {
        assumeTrue(serverUp(), "本地测试服务未启动，跳过");

        Path tacz = gameDir.resolve("tacz");
        // 目录形态，且内部有多层文件 —— 这正是之前删不掉 .bak 的场景
        Path pack = tacz.resolve("mw4-core");
        writePack(pack, "mw4_core", "1.0.0", "old");

        StarterConfig config = new StarterConfig(true, BASE + "/packs.json", List.of("mw4"), 5, true);
        UpdateChecker checker = new UpdateChecker(config, tacz);
        checker.run();
        assertEquals(1, checker.candidates().size());

        var results = new UpdateApplier(tacz).applyAll(checker.candidates(), null);
        assertTrue(results.get(0).ok(), "目录形态更新应成功: " + results.get(0).error());

        // 旧目录应被清理，新 zip 就位
        assertTrue(!Files.exists(pack), "旧目录应已移除");
        assertTrue(Files.isRegularFile(tacz.resolve("mw4_core.zip")), "新 zip 应就位");

        // 不应残留 .bak
        try (var files = Files.list(tacz)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".bak")),
                    "成功更新后不应残留备份");
        }
    }

    private static void writePack(Path root, String namespace, String version, String desc)
            throws IOException {
        Files.createDirectories(root.resolve("assets").resolve(namespace));
        Files.writeString(root.resolve("gunpack.meta.json"),
                "{ \"namespace\": \"" + namespace + "\" }", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("assets").resolve(namespace).resolve("gunpack_info.json"),
                "{\"name\": \"MW4 Core\", \"version\": \"" + version + "\", \"desc\": \"" + desc + "\"}",
                StandardCharsets.UTF_8);
    }
}