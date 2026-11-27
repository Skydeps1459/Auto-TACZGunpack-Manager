package com.skydeps.autotacz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 枪包形态转换：目录 ⇄ zip。
 *
 * <p>TACZ 同时接受未压缩目录与 zip 两种枪包形态，因此互相转换是有实际用途的
 * （例如把开发中的目录打包分发，或把 zip 解开便于手动改数值）。
 *
 * <p>安全要点：解压时逐条校验目标路径落在输出目录内，
 * 防止构造过的 zip 用 {@code ../} 逃逸写出（zip slip）。
 */
public final class PackConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackConverter.class);

    private PackConverter() {
    }

    /**
     * 把目录压缩为 zip。
     *
     * @param sourceDir 源目录（枪包根，内部应含 gunpack.meta.json）
     * @param targetZip 目标 zip 路径，父目录会自动创建
     * @return 生成的 zip 路径
     */
    public static Path directoryToZip(Path sourceDir, Path targetZip) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("源目录不存在: " + sourceDir);
        }
        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(targetZip);

        List<Path> files = listFilesRecursively(sourceDir);
        files.sort(Comparator.comparing(Path::toString));

        try (OutputStream fos = Files.newOutputStream(targetZip);
             ZipOutputStream zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[16 * 1024];
            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                // 用文件的修改时间，便于比较包内条目
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zos.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
        }
        LOGGER.info("已打包 {} 个文件 -> {}", files.size(), targetZip);
        return targetZip;
    }

    /**
     * 把 zip 解压为目录。
     *
     * @param sourceZip 源 zip
     * @param targetDir 目标目录，必须不存在或为空
     * @return 解压后的目录
     */
    public static Path zipToDirectory(Path sourceZip, Path targetDir) throws IOException {
        if (!Files.isRegularFile(sourceZip)) {
            throw new IOException("源 zip 不存在: " + sourceZip);
        }
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);

        int extracted = 0;
        try (ZipFile zip = new ZipFile(sourceZip.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path resolved = root.resolve(entry.getName()).normalize();

                // zip slip 防护：解析后的路径必须仍在目标目录内
                if (!resolved.startsWith(root)) {
                    LOGGER.warn("跳过越界条目: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }

                Path parent = resolved.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(resolved)) {
                    in.transferTo(out);
                }
                extracted++;
            }
        }
        LOGGER.info("已解压 {} 个文件 -> {}", extracted, root);
        return root;
    }

    /**
     * 递归列出目录下所有普通文件。
     *
     * <p>跳过符号链接，避免打包时意外把链接目标的内容带进来。
     */
    private static List<Path> listFilesRecursively(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}