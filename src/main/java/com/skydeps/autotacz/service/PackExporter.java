package com.skydeps.autotacz.service;

import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.scan.GunpackIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 枪包导出。
 *
 * <p>三种导出形态各有用途：
 * <ul>
 *   <li>{@link Format#COPY} —— 原样复制，最快的备份方式</li>
 *   <li>{@link Format#AS_ARCHIVE} —— 统一成 zip，便于分发给别人</li>
 *   <li>{@link Format#AS_DIRECTORY} —— 统一解开成目录，便于手工改数值</li>
 * </ul>
 *
 * <p>导出是只读操作：不移动、不修改源枪包。
 */
public final class PackExporter {

    private final GunpackIndex index;

    public PackExporter(GunpackIndex index) {
        this.index = index;
    }

    /**
     * 导出索引中的枪包。
     *
     * @param targets 要导出的条目
     * @param destDir 导出目录
     * @param format  导出形态
     * @return 逐条结果（成功与失败都包含，便于完整汇报）
     */
    public List<ExportOutcome> export(List<InstalledPack> targets, Path destDir, Format format)
            throws IOException {
        Files.createDirectories(destDir);
        List<ExportOutcome> outcomes = new ArrayList<>();

        for (InstalledPack pack : targets) {
            Path source = pack.pack().source().path();
            try {
                Path out = exportOne(pack, source, destDir, format);
                outcomes.add(ExportOutcome.success(pack.namespace(), out));
            } catch (IOException e) {
                outcomes.add(ExportOutcome.failure(pack.namespace(), e.getMessage()));
            }
        }
        return List.copyOf(outcomes);
    }

    private Path exportOne(InstalledPack pack, Path source, Path destDir, Format format)
            throws IOException {
        String baseName = safeBaseName(pack);

        return switch (format) {
            case COPY -> copyVerbatim(source, destDir, baseName);
            case AS_ARCHIVE -> toArchive(source, destDir, baseName);
            case AS_DIRECTORY -> toDirectory(source, destDir, baseName);
        };
    }

    private static Path copyVerbatim(Path source, Path destDir, String baseName) throws IOException {
        if (Files.isDirectory(source)) {
            Path target = destDir.resolve(baseName + "-dir");
            copyDirectory(source, target);
            return target;
        }
        Path target = destDir.resolve(baseName + ".zip");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static Path toArchive(Path source, Path destDir, String baseName) throws IOException {
        Path target = destDir.resolve(baseName + ".zip");
        if (Files.isDirectory(source)) {
            return PackConverter.directoryToZip(source, target);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static Path toDirectory(Path source, Path destDir, String baseName) throws IOException {
        Path target = destDir.resolve(baseName + "-dir");
        if (Files.isDirectory(source)) {
            copyDirectory(source, target);
        } else {
            PackConverter.zipToDirectory(source, target);
        }
        return target;
    }

    /**
     * 复制目录树。
     *
     * <p>不用 {@code Files.copy(..., REPLACE_EXISTING)} 递归，
     * 因为它在目标已存在时行为不符合直觉，逐文件复制更可控。
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent() == null ? target : dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 生成安全的文件名基名。
     *
     * <p>namespace 来自第三方文件，可能含 Windows 非法字符，
     * 直接用于文件名会抛异常，因此做白名单过滤。
     */
    private static String safeBaseName(InstalledPack pack) {
        String ns = pack.namespace();
        StringBuilder sb = new StringBuilder(ns.length());
        for (char c : ns.toCharArray()) {
            boolean legal = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            sb.append(legal ? c : '_');
        }
        String cleaned = sb.toString();
        return cleaned.isBlank() ? "gunpack" : cleaned;
    }

    /** 导出形态。 */
    public enum Format {
        /** 原样复制 */
        COPY,
        /** 统一为 zip */
        AS_ARCHIVE,
        /** 统一为目录 */
        AS_DIRECTORY
    }

    /**
     * 单条导出结果。
     *
     * @param namespace 枪包 namespace
     * @param path      输出路径；失败时为 {@code null}
     * @param error     错误信息；成功时为 {@code null}
     */
    public record ExportOutcome(String namespace, Path path, String error) {

        static ExportOutcome success(String namespace, Path path) {
            return new ExportOutcome(namespace, path, null);
        }

        static ExportOutcome failure(String namespace, String error) {
            return new ExportOutcome(namespace, null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }
}