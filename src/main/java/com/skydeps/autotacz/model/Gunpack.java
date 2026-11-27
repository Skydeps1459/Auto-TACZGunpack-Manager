package com.skydeps.autotacz.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 枪包的不可变元数据快照。
 *
 * <p>这是领域层的核心模型：不依赖任何 Minecraft 或 Forge API，
 * 因此可以被单元测试直接构造，也可以在客户端/服务端/命令行复用。
 *
 * @param namespace   枪包命名空间（TACZ 的 {@code gunpack.meta.json} 中声明），唯一标识
 * @param displayName 展示名称，缺失时回退为 namespace
 * @param version     版本号
 * @param description 简介
 * @param authors     作者列表，永不为 {@code null}
 * @param license     许可证
 * @param content     内容统计（枪械 / 弹药 / 配件数量）
 * @param weaponTypes 包内出现过的武器类型（如 {@code rifle}、{@code pistol}），用于按类型检索
 * @param source      物理来源（文件路径、大小、哈希）
 */
public record Gunpack(
        String namespace,
        String displayName,
        String version,
        String description,
        List<String> authors,
        String license,
        ContentStats content,
        Set<String> weaponTypes,
        PackSource source
) {

    public Gunpack {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(source, "source");
        displayName = (displayName == null || displayName.isBlank()) ? namespace : displayName;
        version = version == null ? "" : version;
        description = description == null ? "" : description;
        authors = authors == null ? List.of() : List.copyOf(authors);
        license = license == null ? "" : license;
        content = content == null ? ContentStats.EMPTY : content;
        // 用 TreeSet 兜底排序，保证展示与比较顺序稳定（Set 的 equals 本身与顺序无关）
        weaponTypes = (weaponTypes == null || weaponTypes.isEmpty())
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(weaponTypes));
    }

    /** 文件名（含扩展名）。 */
    public String fileName() {
        return source.fileName();
    }

    /** 是否为目录形态的枪包（未压缩）。 */
    public boolean isDirectory() {
        return source.kind() == PackSource.Kind.DIRECTORY;
    }

    /** 是否包含指定武器类型（忽略大小写）。 */
    public boolean hasWeaponType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        for (String candidate : weaponTypes) {
            if (candidate.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    /** 作者是否包含指定名字（忽略大小写、子串匹配）。 */
    public boolean hasAuthor(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String needle = name.toLowerCase(java.util.Locale.ROOT);
        for (String author : authors) {
            if (author.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 内容统计。
     *
     * @param guns        枪械数量
     * @param ammo        弹药数量
     * @param attachments 配件数量
     */
    public record ContentStats(int guns, int ammo, int attachments) {
        public static final ContentStats EMPTY = new ContentStats(0, 0, 0);

        public int total() {
            return guns + ammo + attachments;
        }
    }
}