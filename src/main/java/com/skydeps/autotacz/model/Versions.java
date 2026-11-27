package com.skydeps.autotacz.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 简单版本号比较。
 *
 * <p>TACZ 枪包的版本号是自由文本，真实形态多样：
 * {@code 1.0.0}、{@code v1.2}、{@code 1.13.0-Release}、{@code 1.0.0-beta}。
 * 目的仅是为「远程版本是否更新」提供判断，不需要完整的语义化版本实现。
 *
 * <p>解析规则：
 * <ol>
 *   <li>去掉 {@code v} 前缀</li>
 *   <li>取开头的连续数字段作为<b>主版本</b>（{@code 1.13.0-Release} → {@code [1,13,0]}）</li>
 *   <li>剩余文本用于判定是否<b>预发布</b>（{@code beta}/{@code rc} 等）</li>
 * </ol>
 *
 * <p>为什么 {@code -Release} 这类后缀「不参与比较」，而不是「比数字大」：
 * MW4 枪包真实使用 {@code 1.13.0-Release} 作为版本号，而其他包可能写 {@code 1.13.0}。
 * 若按「文本段 > 数字段」一刀切，两者会被判为不同版本，
 * 导致用户每次启动都被提示更新——这是最糟糕的体验。
 * 因此只有明确表示预发布的词才降级，其余后缀一律忽略。
 */
public final class Versions {

    /** 表示预发布的关键词，命中则视为比同版本号的正式版更旧。 */
    private static final Set<String> PRERELEASE_WORDS = Set.of(
            "alpha", "a", "beta", "b", "rc", "pre", "preview",
            "snapshot", "snap", "dev", "test", "nightly", "canary", "unstable");

    private Versions() {
    }

    /**
     * 比较两个版本号。
     *
     * @return 负数表示 {@code a} 更旧，0 表示等价，正数表示 {@code a} 更新
     */
    public static int compare(String a, String b) {
        Parsed left = parse(a);
        Parsed right = parse(b);

        int length = Math.max(left.numbers().size(), right.numbers().size());
        for (int i = 0; i < length; i++) {
            long l = i < left.numbers().size() ? left.numbers().get(i) : 0L;
            long r = i < right.numbers().size() ? right.numbers().get(i) : 0L;
            int result = Long.compare(l, r);
            if (result != 0) {
                return result;
            }
        }

        // 主版本相同：预发布版本更旧（rank 越小越正式）
        return Integer.compare(right.prereleaseRank(), left.prereleaseRank());
    }

    /** {@code candidate} 是否比 {@code current} 更新。 */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /** 解析结果：主版本数字序列 + 预发布等级（0 = 正式版，1 = 预发布）。 */
    private record Parsed(List<Long> numbers, int prereleaseRank) {
    }

    private static Parsed parse(String version) {
        String trimmed = version == null ? "" : version.trim();
        if (trimmed.isEmpty()) {
            return new Parsed(List.of(), 0);
        }
        // 去掉常见的 v / V 前缀
        if (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V') {
            trimmed = trimmed.substring(1);
        }

        String[] segments = trimmed.split("[^0-9A-Za-z]+");
        List<Long> numbers = new ArrayList<>();
        int prereleaseRank = 0;
        boolean numbersEnded = false;

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (!numbersEnded) {
                Long value = parseLong(segment);
                if (value != null) {
                    numbers.add(value);
                    continue;
                }
                // 遇到第一个非数字段：主版本结束，后续只用于判定预发布
                numbersEnded = true;
            }
            if (PRERELEASE_WORDS.contains(segment.toLowerCase(Locale.ROOT))) {
                prereleaseRank = 1;
            }
        }
        return new Parsed(List.copyOf(numbers), prereleaseRank);
    }

    private static Long parseLong(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            // 超长数字段（例如时间戳）按「不参与数字比较」处理
            return null;
        }
    }
}