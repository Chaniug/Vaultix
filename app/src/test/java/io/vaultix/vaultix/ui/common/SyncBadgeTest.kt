/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.KdbxCloudSyncStatus
import org.junit.Test

/**
 * 同步状态 → **界面角标**的映射（2026-10-01）。
 *
 * ## 为什么值得逐条钉
 *
 * 这条映射决定「用户会不会知道**云端还是旧的**」。用户实测原话：
 * 「修改了之后，它能正常顺利同步到……里面吗」—— 答案本来是"能，但要先手动同步"，
 * 而界面上**看不出这一点**。角标就是为这件事加的。
 *
 * 判据的取向是「**只在需要用户知道时出现**」：
 * 全给角标 ⇒ 常驻噪音，用户会学会忽略它（那比没有更糟）；
 * 给少了 ⇒ 又回到静默。所以两个方向都要有用例钉住。
 */
class SyncBadgeTest {

    @Test
    fun `待上传的两种状态都显示角标`() {
        // PENDING_UPLOAD_WITH_LOCAL_CHANGES 尤其不能漏：它是"上次上传成功了、
        // 但上传期间本地又改了" —— 还有一笔没推上去，用户必须知道。
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.PENDING_UPLOAD)).isEqualTo(SyncBadge.PENDING)
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES))
            .isEqualTo(SyncBadge.PENDING)
    }

    @Test
    fun `失败与冲突各自有角标（用户要做的事不同）`() {
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.FAILED)).isEqualTo(SyncBadge.FAILED)
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.CONFLICT)).isEqualTo(SyncBadge.CONFLICT)
        // 远端有更新：本地没改、云端变了 ⇒ 也需要用户动手（重新解锁后拉取）。
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.REMOTE_CHANGED)).isEqualTo(SyncBadge.REMOTE_CHANGED)
    }

    @Test
    fun `无需用户知道的状态一律不给角标`() {
        // IN_SYNC：两边一致，无事可说。
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.IN_SYNC)).isNull()
        // SYNCING：瞬时态。常驻显示会让它先闪一下再消失，看起来像故障。
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.SYNCING)).isNull()
        // LOCAL_ONLY：「这个库还没有网盘来源」是配置态，不该常驻提示。
        assertThat(SyncBadge.of(KdbxCloudSyncStatus.LOCAL_ONLY)).isNull()
        // null：**本地 SAF 库**（或从未同步）—— 它根本没有云端这回事。
        assertThat(SyncBadge.of(null)).isNull()
    }
}
