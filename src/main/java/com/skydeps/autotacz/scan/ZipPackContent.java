package com.skydeps.autotacz.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * zip 形态枪包的内容视图。
 *
 * <p>持有打开的 {@link ZipFile}，因此必须由调用方负责 {@link #close()}；
 * 推荐统一通过 try-with-resources 使用。
 */
final class ZipPackContent implements PackContent {

    private final ZipFile zip;

    ZipPackContent(Path archive) throws IOException {
        // 显式指定 UTF-8，避免中文文件名在 Windows 默认编码下乱码
        this.zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        ZipEntry entry = zip.getEntry(relativePath);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        return zip.getInputStream(entry);
    }

    @Override
    public boolean exists(String relativePath) {
        ZipEntry entry = zip.getEntry(relativePath);
        return entry != null && !entry.isDirectory();
    }

    @Override
    public int countJsonUnder(String prefix) {
        int count = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name.startsWith(prefix) && name.endsWith(".json")) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<String> listJsonNamesUnder(String prefix) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name.startsWith(prefix) && name.endsWith(".json")) {
                names.add(name.substring(prefix.length()));
            }
        }
        return List.copyOf(names);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}