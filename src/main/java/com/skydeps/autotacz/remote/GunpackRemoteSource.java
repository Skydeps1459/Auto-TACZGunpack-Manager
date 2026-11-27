package com.skydeps.autotacz.remote;

import java.io.IOException;
import java.util.List;

/**
 * 远程枪包源。
 *
 * <p>一个源就是「能按 namespace 或关键字找到 {@link RemotePack} 的东西」。
 * 实现可以是 GitHub API、静态索引文件、镜像站……
 * 上层的更新检查与下载服务只依赖本接口。
 *
 * <h2>新增一个源时要做的事</h2>
 * <ol>
 *   <li>实现本接口的两个方法，把结果归一化成 {@link RemotePack}</li>
 *   <li>缺失字段用 {@code RemotePack} 约定的「未知值」（见其类注释），不要自己定义一套</li>
 *   <li>在 {@code StarterConfig} 里加地址与回退顺序</li>
 *   <li>补 {@code JsonManifestSourceTest} 那样的纯解析测试（不联网）</li>
 * </ol>
 * <p>下载、校验、安装逻辑<b>完全不用动</b>——这正是把源抽成接口的目的。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>网络失败应抛 {@link IOException}，让调用方决定重试或降级</li>
 *   <li>不应在实现里做缓存，缓存属于调用方职责</li>
 *   <li>单条记录字段不全时应跳过该条，<b>不要</b>因此让整个调用失败</li>
 *   <li>返回的列表应为不可变（{@code List.copyOf}），防止调用方意外修改</li>
 * </ul>
 */
public interface GunpackRemoteSource {

    /** 源的可读名称，用于日志与界面。 */
    String name();

    /**
     * 按关键字检索。
     *
     * @param keyword 关键字；空串表示列出全部
     * @return 匹配的发布记录，永不为 {@code null}
     */
    List<RemotePack> search(String keyword) throws IOException;

    /**
     * 查找指定 namespace 的最新发布。
     *
     * @return 未找到时返回 {@code null}
     */
    RemotePack findByNamespace(String namespace) throws IOException;
}