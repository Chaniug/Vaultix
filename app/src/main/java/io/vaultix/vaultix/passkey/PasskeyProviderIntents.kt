/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider 内部 Intent 契约：VaultixCredentialProviderService 构造 PendingIntent，
 * PasskeyGetActivity / PasskeyCreateActivity 消费。集中于此避免两侧 key 漂移。
 *
 * 安全约定：私钥材料（keyValue）**绝不**经 Intent 传递——Activity 按
 * (vaultId, itemId, credentialId) 从已解锁的内存仓储中重新取出，避免敏感数据走 Binder / 落日志。
 */
package io.vaultix.vaultix.passkey

import android.content.Context
import android.content.Intent

object PasskeyProviderIntents {

    const val EXTRA_ACTION = "vaultix.passkey.action"
    const val ACTION_GET = 0
    const val ACTION_CREATE = 1

    /** get：依赖方原始请求 JSON（含 challenge）。 */
    const val EXTRA_REQUEST_JSON = "vaultix.passkey.request_json"
    /** get：浏览器流程下系统算好的 clientDataHash（原生流程为 null）。 */
    const val EXTRA_CLIENT_DATA_HASH = "vaultix.passkey.client_data_hash"
    const val EXTRA_RP_ID = "vaultix.passkey.rp_id"
    /** get：定位具体凭证（经已解锁仓储取私钥，不传 keyValue）。 */
    const val EXTRA_VAULT_ID = "vaultix.passkey.vault_id"
    const val EXTRA_ITEM_ID = "vaultix.passkey.item_id"
    const val EXTRA_CREDENTIAL_ID = "vaultix.passkey.credential_id"

    /** create：依赖方信息。 */
    const val EXTRA_RP_NAME = "vaultix.passkey.rp_name"
    const val EXTRA_USER_NAME = "vaultix.passkey.user_name"
    const val EXTRA_USER_DISPLAY_NAME = "vaultix.passkey.user_display_name"

    fun getIntent(
        context: Context,
        requestJson: String,
        vaultId: String,
        itemId: String,
        credentialId: String,
        rpId: String,
        clientDataHash: ByteArray? = null,
    ): Intent = Intent(context, PasskeyGetActivity::class.java)
        .putExtra(EXTRA_ACTION, ACTION_GET)
        .putExtra(EXTRA_REQUEST_JSON, requestJson)
        .putExtra(EXTRA_CLIENT_DATA_HASH, clientDataHash)
        .putExtra(EXTRA_VAULT_ID, vaultId)
        .putExtra(EXTRA_ITEM_ID, itemId)
        .putExtra(EXTRA_CREDENTIAL_ID, credentialId)
        .putExtra(EXTRA_RP_ID, rpId)

    fun createIntent(
        context: Context,
        requestJson: String,
        rpId: String,
        rpName: String,
        userName: String,
        userDisplayName: String,
    ): Intent = Intent(context, PasskeyCreateActivity::class.java)
        .putExtra(EXTRA_ACTION, ACTION_CREATE)
        .putExtra(EXTRA_REQUEST_JSON, requestJson)
        .putExtra(EXTRA_RP_ID, rpId)
        .putExtra(EXTRA_RP_NAME, rpName)
        .putExtra(EXTRA_USER_NAME, userName)
        .putExtra(EXTRA_USER_DISPLAY_NAME, userDisplayName)
}
