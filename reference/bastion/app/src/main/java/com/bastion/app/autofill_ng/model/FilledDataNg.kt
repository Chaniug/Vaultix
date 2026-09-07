package com.bastion.app.autofill_ng.model

import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.inline.InlinePresentationSpec

data class FilledData(
    val filledPartitions: List<FilledPartition>,
    val ignoreAutofillIds: List<AutofillId>,
    val originalPartition: AutofillPartition,
    val uri: String?,
    val vaultItemInlinePresentationSpec: InlinePresentationSpec?,
    val isVaultLocked: Boolean,
)

data class FilledPartition(
    val autofillCipher: AutofillCipher.Login,
    val filledItems: List<FilledItem>,
    val inlinePresentationSpec: InlinePresentationSpec?,
    val requiresAuthentication: Boolean = false,
    /**
     * WebView 场景（对齐 Bitwarden）：挂 setAuthentication 让框架走"用户点选→回调→回填"
     * 路径（该路径对系统 WebView 密码框虚拟节点回填更可靠），但 vault 解锁态不触发指纹
     *（与 [requiresAuthentication] 不同，后者会触发生物识别）。filledItems 保留真实值，
     * 回调直接回填，不重新按 hint 映射，避免 Edge 账户名失配。
     */
    val forceDatasetAuth: Boolean = false,
)

data class FilledItem(
    val autofillId: AutofillId,
    val value: AutofillValue?,
)
