package com.skydeps.autotacz.scan;

import com.skydeps.autotacz.model.PackState;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 枪包检索条件（不可变）。
 *
 * <p>把「查什么」与「怎么查」分离：{@link GunpackIndex} 只负责执行，
 * 调用方（命令、GUI、脚本）只负责构造条件。新增筛选维度时
 * 只需在这里加字段并更新 {@link #matches}，索引与入口都不用改。
 *
 * <p>所有文本条件均为「忽略大小写的子串匹配」；多个条件之间是 <b>AND</b> 关系。
 *
 * @param text       全文关键字，匹配名称 / namespace / 简介 / 作者
 * @param author     作者关键字
 * @param weaponType 武器类型（如 {@code rifle}）
 * @param state      安装状态；{@code null} 表示不限
 * @param sort       排序方式
 * @param ascending  是否升序
 */
public record PackQuery(
        @Nullable String text,
        @Nullable String author,
        @Nullable String weaponType,
        @Nullable PackState state,
        Sort sort,
        boolean ascending
) {

    public static final PackQuery ALL = new PackQuery(null, null, null, null, Sort.NAME, true);

    public PackQuery {
        text = normalize(text);
        author = normalize(author);
        weaponType = normalize(weaponType);
        sort = sort == null ? Sort.NAME : sort;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public boolean isEmpty() {
        return text == null && author == null && weaponType == null && state == null;
    }

    /** 用于日志与界面回显。 */
    public String describe() {
        if (isEmpty()) {
            return "全部枪包";
        }
        StringBuilder sb = new StringBuilder();
        if (text != null) {
            sb.append("关键字=").append(text);
        }
        if (author != null) {
            appendPart(sb, "作者=" + author);
        }
        if (weaponType != null) {
            appendPart(sb, "类型=" + weaponType);
        }
        if (state != null) {
            appendPart(sb, "状态=" + (state == PackState.ENABLED ? "启用" : "禁用"));
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }

    /** 排序维度。 */
    public enum Sort {
        /** 按名称 */
        NAME,
        /** 按版本号 */
        VERSION,
        /** 按内容条目数（枪 + 弹 + 配件） */
        CONTENT,
        /** 按文件时间（新→旧更直观） */
        TIME
    }
}