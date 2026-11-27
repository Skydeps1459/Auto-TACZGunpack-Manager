package com.skydeps.autotacz.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 枪包的物理来源信息。
 *
 * <p>与 {@link Gunpack} 分离，是为了让「逻辑元数据」和「存放在哪、多大、什么哈希」
 * 这两类关注点独立演化。例如未来支持从远程缓存直接读取元数据时，
 * 可以构造一个没有真实路径的 PackSource。
 *
 * @param path        文件或目录路径
 * @param kind        形态
 * @param sizeBytes   大小（目录为 0，未知为 -1）
 * @param sha256      SHA-256 摘要；未计算时为 {@link #UNKNOWN_HASH}
 * @param lastModified 最后修改时间（毫秒时间戳）；未知为 0
 */
public record PackSource(
        Path path,
        Kind kind,
        long sizeBytes,
        String sha256,
        long lastModified
) {

    public static final String UNKNOWN_HASH = "";

    public PackSource {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        sha256 = sha256 == null ? UNKNOWN_HASH : sha256;
    }

    public static PackSource ofDirectory(Path dir, long lastModified) {
        return new PackSource(dir, Kind.DIRECTORY, 0L, UNKNOWN_HASH, lastModified);
    }

    public static PackSource ofArchive(Path zip, long sizeBytes, String sha256, long lastModified) {
        return new PackSource(zip, Kind.ARCHIVE, sizeBytes, sha256, lastModified);
    }

    public String fileName() {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    public boolean hasHash() {
        return !sha256.isBlank();
    }

    /** 物理形态。 */
    public enum Kind {
        /** 未压缩目录 */
        DIRECTORY,
        /** .zip 压缩包 */
        ARCHIVE
    }
}