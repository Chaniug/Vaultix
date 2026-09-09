/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 自动填充认证回灌（authentication）的 Intent 契约：Service 构造 PendingIntent，
 * AutofillActivity 消费并在认证后回灌 Dataset。集中于此避免两侧 key 漂移。
 */
package io.vaultix.vaultix.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.autofill.AutofillId

/** Service ↔ AutofillActivity 的 Intent 约定。 */
object AutofillIntents {

    /** 模式：引导解锁（库全部锁定）。 */
    const val MODE_UNLOCK = 0

    /** 模式：主密码二次验证后回填（cipher.reprompt = Password）。 */
    const val MODE_REPROMPT = 1

    /** 模式：无匹配项，跳到 Vaultix 搜索。 */
    const val MODE_SEARCH = 2

    /**
     * 模式：回填 Dataset 后**自动复制验证码**（对齐 Bitwarden「填充后自动复制 TOTP」）。
     *
     * 框架直填（setValue）时服务层无从感知用户是否点选，只有走 dataset 认证回调
     * 这条 API 26 就有的路径，才能保证「复制」发生在真正填充之后
     * （Bastion 同款取舍：不用 API 33 的 FillEventHistory）。
     */
    const val MODE_COPY_TOTP = 3

    private const val EXTRA_MODE = "vaultix.autofill.mode"
    private const val EXTRA_TOTP = "vaultix.autofill.totp"
    private const val EXTRA_TITLE = "vaultix.autofill.title"
    private const val EXTRA_SUBTITLE = "vaultix.autofill.subtitle"
    private const val EXTRA_DATASET_ID = "vaultix.autofill.dataset_id"
    private const val EXTRA_IDS = "vaultix.autofill.ids"
    private const val EXTRA_VALUES = "vaultix.autofill.values"

    /**
     * MainActivity 由此开启时：解锁完成即自动 finish 返回原 App（AutofillActivity 的
     * MODE_UNLOCK 解锁桥在用）。用户回到浏览器再点一次即秒填，不再被晾在 Vaultix 主界面。
     */
    const val EXTRA_MAIN_UNLOCK_EXIT = "vaultix.autofill.main_unlock_exit"

    /** 构造认证回灌用的显式 Intent。 */
    fun create(
        context: Context,
        mode: Int,
        title: String,
        subtitle: String,
        datasetId: String? = null,
        entries: List<Pair<AutofillId, String>> = emptyList(),
        totpSecret: String? = null,
    ): Intent = Intent(context, AutofillActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_MODE, mode)
        .putExtra(EXTRA_TOTP, totpSecret)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_SUBTITLE, subtitle)
        .putExtra(EXTRA_DATASET_ID, datasetId)
        .putParcelableArrayListExtra(EXTRA_IDS, ArrayList(entries.map { it.first }))
        .putStringArrayListExtra(EXTRA_VALUES, ArrayList(entries.map { it.second }))

    /** 包装为 PendingIntent（系统用它拉起认证界面，必须 IMMUTABLE）。 */
    fun pending(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun modeOf(intent: Intent): Int = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
    fun titleOf(intent: Intent): String = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    fun subtitleOf(intent: Intent): String = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
    fun datasetIdOf(intent: Intent): String? = intent.getStringExtra(EXTRA_DATASET_ID)

    /** 待自动复制的 TOTP 密钥（仅 [MODE_COPY_TOTP] 携带）。 */
    fun totpSecretOf(intent: Intent): String? = intent.getStringExtra(EXTRA_TOTP)

    /** 待回填的「目标字段 → 值」（与 [AutofillIntents.create] 入参顺序一致）。 */
    fun entriesOf(intent: Intent): List<Pair<AutofillId, String>> {
        val ids = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_IDS).orEmpty()
        val values = intent.getStringArrayListExtra(EXTRA_VALUES).orEmpty()
        val size = minOf(ids.size, values.size)
        return (0 until size).map { index -> ids[index] to values[index] }
    }
}
