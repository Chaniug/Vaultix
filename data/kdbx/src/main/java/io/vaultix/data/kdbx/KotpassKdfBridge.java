/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * ---------------------------------------------------------------------------
 * kotpass 的 `BaseKdfProvider` 是 Kotlin `internal object`：
 * JVM 层面它是 `public final class` + `public static final INSTANCE`，
 * 但 Kotlin 编译器会按 metadata 拦住源码引用（INVISIBLE_REFERENCE）。
 *
 * Java 编译器只读 .class 的 JVM 可见性、不读 Kotlin metadata ⇒ 这里能正常引用。
 * 用**编译期绑定**而不是反射：R8 混淆时会连同引用一起重写，
 * release 包也不会像 `Class.forName("…")` 那样静默失效。
 *
 * ⚠️ 这是整个项目里唯一一处需要绕开 kotpass internal 的地方，
 *    收在一个文件里，升级 kotpass 时只要验这一处。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx;

import app.keemobile.kotpass.cryptography.format.BaseKdfProvider;
import app.keemobile.kotpass.cryptography.format.KdfProvider;

/** 取 kotpass `decode` 默认使用的那个 KDF 实现，供 {@link KdbxTransformKeyCache} 包装。 */
public final class KotpassKdfBridge {

    private KotpassKdfBridge() {
    }

    public static KdfProvider defaultProvider() {
        return BaseKdfProvider.INSTANCE;
    }
}
