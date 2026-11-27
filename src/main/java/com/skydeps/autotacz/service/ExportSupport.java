package com.skydeps.autotacz.service;

import java.net.URI;

/**
 * 运行时共享常量。
 */
public final class ExportSupport {

    private ExportSupport() {
    }

    /**
     * 默认远程清单地址。
     *
     * <p>放在仓库的 {@code main} 分支上，任何人可以 fork 后改成自己的地址。
     * 格式见 {@link com.skydeps.autotacz.remote.JsonManifestSource}。
     */
    public static final URI DEFAULT_MANIFEST = URI.create(
            "https://raw.githubusercontent.com/Skydeps1459/Auto-TACZGunpack-Manager/main/packs.json");
}