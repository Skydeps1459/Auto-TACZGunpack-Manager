package com.skydeps.autotacz.scan;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 枪包内容的只读视图。
 *
 * <p>这个抽象是整个扫描模块可维护性的关键：目录形态与 zip 形态的枪包
 * 内部结构完全一致，差异仅在「如何按名字取到流」。
 * 把这一点抽象掉之后，{@link GunpackMetadataReader} 只需要写一份解析逻辑。
 */
public interface PackContent extends Closeable {

    /**
     * 按包内相对路径读取内容。
     *
     * @param relativePath 使用 {@code /} 分隔的相对路径，例如 {@code gunpack.meta.json}
     * @return 数据流；不存在时返回 {@code null}
     */
    InputStream open(String relativePath) throws IOException;

    /** 判断包内是否存在指定路径的文件。 */
    boolean exists(String relativePath);

    /**
     * 列出指定前缀下所有以 {@code .json} 结尾的条目数。
     *
     * <p>用于统计 {@code data/<ns>/index/guns/} 等目录下的条目数量。
     * 采用「计数」而非「列举全路径」，避免为统计创建大量临时对象。
     *
     * @param prefix 以 {@code /} 结尾的路径前缀
     * @return 匹配的条目数
     */
    int countJsonUnder(String prefix);

    /** 列出指定前缀下所有子条目名（不含路径），用于诊断。 */
    List<String> listJsonNamesUnder(String prefix);
}