/*
 * Vaultix — app:ui · unlock
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.unlock

import androidx.annotation.StringRes
import io.vaultix.vaultix.R

/**
 * 「本地快速解锁失败」给用户看的那句话，**只在这里定义一次**。
 *
 * ## 为什么需要它（审计 §3.4，2026-10-21）
 *
 * 改前同一批文案在**两处**各存了一份：
 *
 * | 位置 | 形态 | 问题 |
 * |---|---|---|
 * | `LocalUnlockFanout` 的 `private const val` | 中文字面量 | 非 Composable ⇒ 以为拿不到资源，于是"镜像一份" |
 * | `UnlockViewModel.localUnlockFailureText` | 中文字面量 | 另一处同样的字面量 |
 *
 * 两份的注释都写着"两处措辞必须一致，靠 review 守"—— 那就是**靠人守的双源**，
 * 迟早分叉。而且它们都不在 `strings.xml` 里，既翻不了，也进不了孤儿串检查。
 *
 * ## 现在的形态
 *
 * 本枚举只带**资源 id**（纯数据，测试可直接断言），解析交给调用方：
 * - `UnlockViewModel`（有 `context`）→ [resolve] 拿一句话
 * - `AutofillActivity`（有 `context`）→ 同样 [resolve]
 * - `LocalUnlockFanout`（**没有** context）→ 只产出本枚举，不再碰字符串
 *
 * ⚠️ 为什么不用 `sealed interface` + 参数：这一批文案全是**无参整句**，
 * 而带参的那批（剩余次数、位数）在 `QuickUnlockText` 里。硬合并会把两套参数
 * 处理塞进一个类型，反而更难读。判据：**要填数字的走 `QuickUnlockText`，
 * 报"哪种局面"的走这里。**
 */
internal enum class LocalUnlockMessage(@param:StringRes val id: Int) {
    /** 门锁解不开，且没有可做善后的仓储（行为与加善后之前逐字相同）。 */
    LockFailed(R.string.local_unlock_lock_failed),

    /** 开门态：钥匙还在手上，只是要等下次认证补写 —— 别说「已失效」（那是假话）。 */
    RearmPending(R.string.local_unlock_rearm_pending),

    /** 关门态且连带清了房间信封：必须说清「要重新启用」，否则用户会以为是 bug。 */
    DegradedRoomsRemoved(R.string.local_unlock_degraded_rooms_removed),

    /** 关门态但还有 PIN 锁在，用户的库没受影响。 */
    DegradedPinRemains(R.string.local_unlock_degraded_pin_remains),

    /** 关门态且再无其它门锁。 */
    Degraded(R.string.local_unlock_degraded),

    /** 单库开不出来（KDBX）：多半是主密码已在别处改过。 */
    VaultOpenFailedKdbx(R.string.local_unlock_vault_open_failed_kdbx),

    /** 单库开不出来（其它）：本地解锁不可用，回退主密码。 */
    VaultOpenFailed(R.string.local_unlock_vault_open_failed),
    ;

    /** 取一个不带格式参数的整句。 */
    fun resolve(context: android.content.Context): String = context.getString(id)
}
