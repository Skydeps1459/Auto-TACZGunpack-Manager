package com.skydeps.autotacz.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 目录形态枪包的内容视图。
 */
final class DirectoryPackContent implements PackContent {

    private final Path root;

    DirectoryPackContent(Path root) {
        this.root = root;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        Path resolved = resolveSafely(relativePath);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return null;
        }
        return Files.newInputStream(resolved);
    }

    @Override
    public boolean exists(String relativePath) {
        Path resolved = resolveSafely(relativePath);
        return resolved != null && Files.isRegularFile(resolved);
    }

    @Override
    public int countJsonUnder(String prefix) {
        Path dir = resolveSafely(trimTrailingSlash(prefix));
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public List<String> listJsonNamesUnder(String prefix) {
        Path dir = resolveSafely(trimTrailingSlash(prefix));
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> names.add(dir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(names);
    }

    @Override
    public void close() {
        // 目录形态无需释放资源
    }

    /**
     * 解析路径并防止越界（{@code ../} 逃逸）。
     *
     * <p>枪包来源可能是第三方下载物，必须防御性校验。
     */
    private Path resolveSafely(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = root.resolve(relativePath).normalize();
        return resolved.startsWith(root.normalize()) ? resolved : null;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}