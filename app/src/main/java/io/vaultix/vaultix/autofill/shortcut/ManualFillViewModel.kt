/*
 * Vaultix — app:autofill · shortcut
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.shortcut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpGenerator
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * [ManualFillActivity] 的状态源：已解锁库里的登录条目 + 搜索过滤 + 复制接力。
 *
 * 只读内存中的已解锁明文（与 [io.vaultix.vaultix.autofill.VaultixAutofillService] 同口径），
 * 未解锁时列表为空且不触碰任何密文。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManualFillViewModel @Inject constructor(
    vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val notifier: SmartCopyNotifier,
    private val activeVaultStore: ActiveVaultStore,
) : ViewModel() {

    /** 一行候选（跨库聚合后的扁平结构，UI 只关心这几个字段）。 */
    data class Row(
        val vaultId: String,
        val itemId: String,
        val title: String,
        val username: String,
        val password: String,
        val totp: String = "",
    )

    private val query = MutableStateFlow("")

    /** 全部库都锁着 → 界面提示先解锁。 */
    val locked: StateFlow<Boolean> = vaultRepository.observeUnlockedVaultIds()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** ★ 候选只取**活跃库**（与 autofill / CP 同口径，见迁移文档阶段 2）。 */
    private val credentials: StateFlow<List<Row>> = activeVaultStore.activeVaultId
        .flatMapLatest { id -> credentialFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val rows: StateFlow<List<Row>> = combine(credentials, query) { list, text -> filterRows(list, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /**
     * 选中条目：复制密码，并在通知里留下「复制用户名」/「复制验证码」的接力动作
     * （条目有 TOTP 时才有验证码动作）。
     */
    fun onPick(row: Row) {
        viewModelScope.launch {
            notifier.copyPasswordThenOfferUsername(
                title = row.title,
                username = row.username,
                password = row.password,
                totpCode = totpCodeOf(row.totp),
            )
        }
    }

    private fun totpCodeOf(raw: String): String? {
        if (raw.isBlank()) return null
        return runCatching {
            OtpUriParser.parse(raw)?.let { TotpGenerator.generate(it) }
        }.getOrNull()
    }

    /**
     * 单库候选流（跨库聚合已废弃 —— 会重现「同一站点两条候选」）。
     *
     * [vaultId] 为 null 表示活跃库首帧未到（冷启动）：返回空而不是退化成遍历所有已解锁库；
     * `activeVaultId` 是 `StateFlow`，首帧到达后会立即重新发射并填充列表。
     */
    private fun credentialFlow(vaultId: String?): Flow<List<Row>> {
        if (vaultId.isNullOrBlank()) return flowOf(emptyList())
        return itemRepository.observeItems(vaultId).map { items ->
            items.filter { AutofillCredentialMapper.isLoginCandidate(it) }.map { toRow(vaultId, it) }
        }
    }

    private fun toRow(vaultId: String, item: VaultItem) = Row(
        vaultId = vaultId,
        itemId = item.id,
        title = item.title,
        username = item.username,
        password = item.password,
        totp = item.totp.orEmpty(),
    )

    private fun filterRows(rows: List<Row>, text: String): List<Row> {
        val q = text.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return rows.sortedBy { it.title.lowercase(Locale.ROOT) }
        return rows.filter {
            it.title.lowercase(Locale.ROOT).contains(q) || it.username.lowercase(Locale.ROOT).contains(q)
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
