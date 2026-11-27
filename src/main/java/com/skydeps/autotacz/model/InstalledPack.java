package com.skydeps.autotacz.model;

import java.util.Objects;

/**
 * 「已安装的枪包」= 元数据 + 启用状态。
 *
 * <p>拆成独立类型而不是把状态塞进 {@link Gunpack}，是因为
 * 启用/禁用是<b>安装态</b>而非枪支包自身的属性：
 * 同一个枪包被移动到 {@code .disabled} 目录后，元数据并没有变化。
 *
 * @param pack  枪包元数据
 * @param state 启用状态
 */
public record InstalledPack(Gunpack pack, PackState state) {

    public InstalledPack {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(state, "state");
    }

    public String namespace() {
        return pack.namespace();
    }

    public boolean enabled() {
        return state == PackState.ENABLED;
    }

    /** 返回状态变更后的副本，用于启用/禁用操作。 */
    public InstalledPack withState(PackState newState) {
        return state == newState ? this : new InstalledPack(pack, newState);
    }
}