package com.skydeps.autotacz.model;

/**
 * 枪包状态。
 *
 * <p>「启用 / 禁用」由枪包所在的物理目录决定：
 * TACZ 只扫描 {@code tacz/} 根目录，因此把包移入 {@code tacz/.disabled/}
 * 即可实现禁用，且不会破坏压缩包本身。
 */
public enum PackState {
    /** 位于 {@code tacz/} 根目录，TACZ 会加载 */
    ENABLED,
    /** 位于 {@code tacz/.disabled/}，TACZ 不会加载 */
    DISABLED
}