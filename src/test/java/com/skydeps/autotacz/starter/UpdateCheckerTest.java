package com.skydeps.autotacz.starter;

import com.skydeps.autotacz.config.StarterConfig;
import com.skydeps.autotacz.remote.HttpFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动检查的状态机行为。
 *
 * <p>最重要的两条约束：
 * <ol>
 *   <li>网络异常 → {@link UpdateChecker.Status#NETWORK_ERROR}，且 {@code canSkip()} 为 true
 *       （否则玩家会被卡在启动界面进不去游戏）</li>
 *   <li>只检查匹配模式（默认 mw4）的枪包，不把无关枪包也拉进来</li>
 * </ol>
 */
class UpdateCheckerTest {

    /**
     * 指向必然拒绝连接的地址。
     *
     * <p>用 127.0.0.1 上一个几乎不可能监听的端口，而不是依赖外网是否可达：
     * 这样测试在完全离线的机器上也能稳定复现「没网」场景。
     */
    private static final String DEAD_URL = "http://127.0.0.1:1/packs.json";

    /**
     * 构造一个<b>完全隔离网络</b>的检查器：只访问主地址，不走备用地址。
     *
     * <p>这一点必须显式指定。默认构造会带上
     * {@link com.skydeps.autotacz.config.StarterConfig#FALLBACK_MANIFESTS}
     * 里的真实 CDN 地址，在能联网的 CI 上，主地址失败后会真的连上备用地址并成功，
     * 于是测出来是 {@code DONE}——测试就失去了验证「网络异常」的能力。
     * 这个坑实际踩过：本地因为代理原因全部失败而通过，CI 上一片红。
     */
    private static UpdateChecker isolated(StarterConfig config, Path taczRoot) {
        return new UpdateChecker(config, taczRoot, HttpFetcher::new, List.of());
    }

    @Test
    @DisplayName("tacz 目录不存在时检查正常结束，不报异常")
    void missingTaczRootFinishesGracefully(@TempDir Path gameDir) {
        StarterConfig config = new StarterConfig(
                true, DEAD_URL, List.of("mw4"), 3, true);
        UpdateChecker checker = isolated(config, gameDir.resolve("tacz"));

        checker.run();

        // 没有任何本地枪包 -> 无需联网就已判定完成
        assertEquals(UpdateChecker.Status.DONE, checker.status());
        assertEquals(0, checker.scannedPacks());
        assertTrue(checker.candidates().isEmpty());
    }

    @Test
    @DisplayName("网络不可达时进入 NETWORK_ERROR 且可跳过")
    void networkFailureIsSkippable(@TempDir Path gameDir) throws IOException {
        writePack(gameDir.resolve("tacz").resolve("mw4-pack"), "mw4_core", "MW4 核心包", "1.0.0");

        StarterConfig config = new StarterConfig(true, DEAD_URL, List.of("mw4"), 2, true);
        UpdateChecker checker = isolated(config, gameDir.resolve("tacz"));

        checker.run();

        assertEquals(UpdateChecker.Status.NETWORK_ERROR, checker.status(),
                "连不上清单应归为网络异常");
        assertTrue(checker.canSkip(), "网络异常必须允许跳过，否则玩家进不了游戏");
        assertTrue(checker.finished(), "网络异常也算结束，界面不应一直转圈");
        assertFalse(checker.message().isBlank(), "应给出可读原因");
        assertTrue(checker.message().contains("ConnectException")
                        || checker.message().contains("无法连接"),
                "错误信息应能说明是连接问题，实际: " + checker.message());
    }

    @Test
    @DisplayName("清单地址非法时同样可跳过，而不是抛异常")
    void invalidManifestUrlIsSkippable(@TempDir Path gameDir) throws IOException {
        writePack(gameDir.resolve("tacz").resolve("mw4-pack"), "mw4_core", "MW4 核心包", "1.0.0");

        StarterConfig config = new StarterConfig(true, "not-a-url", List.of("mw4"), 2, true);
        UpdateChecker checker = isolated(config, gameDir.resolve("tacz"));

        checker.run();

        assertEquals(UpdateChecker.Status.NETWORK_ERROR, checker.status());
        assertTrue(checker.canSkip());
    }

    @Test
    @DisplayName("只挑出匹配 mw4 的枪包，其他包不参与检查")
    void onlyTargetPacksAreSelected(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        writePack(tacz.resolve("mw4-pack"), "mw4_core", "MW4 核心包", "1.0.0");
        writePack(tacz.resolve("other-pack"), "bocw_pack", "BOCW 包", "2.0.0");

        StarterConfig config = new StarterConfig(true, DEAD_URL, List.of("mw4"), 2, true);
        UpdateChecker checker = isolated(config, tacz);

        checker.run();

        assertEquals(2, checker.scannedPacks(), "两个包都应被扫描到");
        // 命中了 mw4，因此会尝试联网 -> 网络异常
        assertEquals(UpdateChecker.Status.NETWORK_ERROR, checker.status());
    }

    @Test
    @DisplayName("通配符 * 表示检查全部枪包")
    void wildcardMatchesEverything() {
        StarterConfig config = new StarterConfig(true, DEAD_URL, List.of("*"), 2, true);
        assertEquals(List.of("*"), config.targetPatterns());
    }

    @Test
    @DisplayName("跳过标记可被记录，供日志与统计使用")
    void markSkippedIsRecorded(@TempDir Path gameDir) {
        UpdateChecker checker = new UpdateChecker(StarterConfig.defaults(), gameDir.resolve("tacz"));
        assertFalse(checker.skipped());
        checker.markSkipped();
        assertTrue(checker.skipped());
    }

    @Test
    @DisplayName("把网络失败单独区分，不打成代码错误")
    void networkFailureHasDedicatedType() {
        UpdateChecker.NetworkFailure failure = new UpdateChecker.NetworkFailure("没网");
        assertTrue(failure instanceof IOException);
        assertEquals("没网", failure.getMessage());
    }

    /** 造一个最简枪包，只需要 gunpack.meta.json 能被扫描识别。 */
    private static void writePack(Path root, String namespace, String name, String version)
            throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("gunpack.meta.json"),
                "{ \"namespace\": \"" + namespace + "\" }", StandardCharsets.UTF_8);
        Path assets = root.resolve("assets").resolve(namespace);
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("gunpack_info.json"),
                "{\"name\": \"" + name + "\", \"version\": \"" + version + "\"}",
                StandardCharsets.UTF_8);
    }
}