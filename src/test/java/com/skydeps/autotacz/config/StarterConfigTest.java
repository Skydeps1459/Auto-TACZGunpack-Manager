package com.skydeps.autotacz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件读写与容错。
 *
 * <p>核心约束：<b>配置损坏绝不能阻止游戏启动</b>，因此任何读取异常都必须回退默认值。
 */
class StarterConfigTest {

    @Test
    @DisplayName("文件不存在时生成默认配置并落盘")
    void createsDefaultFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config").resolve("autotacz-starter.json");
        StarterConfig config = StarterConfig.load(file);

        assertTrue(config.enabled());
        assertEquals(StarterConfig.DEFAULT_MANIFEST, config.manifestUrl());
        assertEquals(StarterConfig.DEFAULT_PATTERNS, config.targetPatterns());
        assertTrue(Files.isRegularFile(file), "应自动写出默认配置文件");
    }

    @Test
    @DisplayName("写入后可原样读回")
    void roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.json");
        StarterConfig original = new StarterConfig(
                false, "https://example.com/list.json", java.util.List.of("mw4", "bocw"), 15, false);
        original.save(file);

        StarterConfig loaded = StarterConfig.load(file);
        assertEquals(original, loaded);
    }

    @Test
    @DisplayName("字段缺失时按默认值补齐，而非整体失效")
    void missingFieldsUseDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.json");
        Files.writeString(file, "{\"manifestUrl\": \"https://example.com/x.json\"}", StandardCharsets.UTF_8);

        StarterConfig config = StarterConfig.load(file);
        assertTrue(config.enabled(), "enabled 缺失应为 true");
        assertTrue(config.autoContinue(), "autoContinue 缺失应为 true");
        assertEquals("https://example.com/x.json", config.manifestUrl());
    }

    @Test
    @DisplayName("JSON 损坏时回退默认值而不抛异常")
    void corruptFileFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        StarterConfig config = StarterConfig.load(file);
        assertEquals(StarterConfig.defaults(), config);
    }

    @Test
    @DisplayName("超时被夹到合理区间")
    void timeoutIsClamped() {
        assertEquals(2, new StarterConfig(true, "u", java.util.List.of("*"), 0, true).timeoutSeconds(),
                "0 应被抬到下限 2");
        assertEquals(60, new StarterConfig(true, "u", java.util.List.of("*"), 9999, true).timeoutSeconds(),
                "9999 应被压到上限 60");
    }

    @Test
    @DisplayName("空白模式列表回退为默认 mw4")
    void blankPatternsFallBack() {
        StarterConfig config = new StarterConfig(true, "u", java.util.List.of(), 8, true);
        assertEquals(StarterConfig.DEFAULT_PATTERNS, config.targetPatterns());
    }

    @Test
    @DisplayName("withEnabled 只改开关")
    void withEnabledKeepsOtherFields() {
        StarterConfig config = StarterConfig.defaults().withEnabled(false);
        assertFalse(config.enabled());
        assertEquals(StarterConfig.defaults().manifestUrl(), config.manifestUrl());
    }
}