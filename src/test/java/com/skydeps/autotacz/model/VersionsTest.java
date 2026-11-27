package com.skydeps.autotacz.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本比较的行为约束。
 *
 * <p>这些用例直接对应「是否提示用户更新」的决策，
 * 判错方向会导致要么永远不提示更新、要么反复提示虚假更新。
 */
class VersionsTest {

    @Test
    @DisplayName("数字段按数值而非字典序比较")
    void numericSegmentsCompareNumerically() {
        assertTrue(Versions.compare("1.0.10", "1.0.9") > 0, "1.0.10 应大于 1.0.9");
        assertTrue(Versions.compare("2.0.0", "10.0.0") < 0, "2.0.0 应小于 10.0.0");
    }

    @Test
    @DisplayName("缺失的段按 0 补齐")
    void missingSegmentsTreatedAsZero() {
        assertEquals(0, Versions.compare("1.0", "1.0.0"));
        assertEquals(0, Versions.compare("1", "1.0.0.0"));
    }

    @Test
    @DisplayName("v 前缀被忽略")
    void stripsVPrefix() {
        assertEquals(0, Versions.compare("v1.2.3", "1.2.3"));
        assertEquals(0, Versions.compare("V1.2.3", "v1.2.3"));
    }

    @Test
    @DisplayName("正式版大于预发布版")
    void releaseBeatsPrerelease() {
        assertTrue(Versions.compare("1.0.0", "1.0.0-beta") > 0);
    }

    @Test
    @DisplayName("isNewer 只在真正更新时返回 true")
    void isNewerOnlyForActualUpgrades() {
        assertTrue(Versions.isNewer("1.2.0", "1.1.0"));
        assertFalse(Versions.isNewer("1.1.0", "1.1.0"));
        assertFalse(Versions.isNewer("1.0.0", "1.1.0"));
    }

    @Test
    @DisplayName("空版本号不产生虚假更新")
    void blankVersionsDoNotReportUpdate() {
        assertFalse(Versions.isNewer("", ""));
        assertFalse(Versions.isNewer("", "1.0.0"));
    }

    /**
     * MW4 枪包真实使用 {@code 1.13.0-Release} 这类后缀，
     * 必须保证它比 {@code 1.12.0-Release} 新、与自身相等、不会把旧版误判为新版。
     */
    @Test
    @DisplayName("真实场景：带 -Release 后缀的版本号正确比较")
    void handlesReleaseSuffix() {
        assertTrue(Versions.isNewer("1.13.0-Release", "1.12.0-Release"), "1.13 应新于 1.12");
        assertFalse(Versions.isNewer("1.12.0-Release", "1.13.0-Release"), "旧版不应被判为更新");
        assertEquals(0, Versions.compare("1.13.0-Release", "1.13.0-Release"), "同版本应相等");
    }

    @Test
    @DisplayName("真实场景：后缀不影响主版本号比较")
    void suffixDoesNotAffectMajorComparison() {
        // 1.10 与 1.9 的经典陷阱：字典序会认为 "1.9" > "1.10"
        assertTrue(Versions.compare("1.10.0-Release", "1.9.0-Release") > 0,
                "1.10 必须大于 1.9（不是字典序）");
        assertTrue(Versions.compare("1.8.2-Release", "1.8.1-Release") > 0);
    }

    @Test
    @DisplayName("带后缀与不带后缀视为同一版本")
    void suffixAndPlainAreEquivalent() {
        assertEquals(0, Versions.compare("1.13.0-Release", "1.13.0"));
    }
}