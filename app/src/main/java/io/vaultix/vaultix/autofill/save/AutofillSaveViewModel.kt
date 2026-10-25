/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.save

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 保存界面的状态机：拿到「待保存的账号密码 + 来源（网页/App）」→
 * 判断是**新建**还是**更新已有条目** → 落库。
 *
 * 只在**已解锁**的库上工作：库锁定时不保存明文（不落盘、不排队），只提示用户先解锁
 * （加密密钥只在内存会话里，锁定时无法写密文）。
 *
 * ## ⚠️ 明文从哪来（#187，2026-10-26）
 *
 * **不再**从 `SavedStateHandle` 取。原来的写法把账号密码当 Intent extra 传进来，
 * 而 `SavedStateHandle` 的内容会被 `onSaveInstanceState` 序列化进系统侧快照 ——
 * 明文密码因此进了 /data/system。现在改成从 [PendingSaveStore] 按**一次性票据**取，
 * 取到即从内存清空；`SavedStateHandle` 里只剩票据本身与非秘密的来源标记。
 *
 * 代价（有意接受）：进程被杀后暂存消失，界面会显示"信息已过期"而不是静默重放一份旧密码。
 */
@HiltViewModel
class AutofillSaveViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val activeVaultStore: ActiveVaultStore,
    pendingSaveStore: PendingSaveStore,
) : ViewModel() {

    private val pending: PendingSave? =
        pendingSaveStore.consume(savedStateHandle[AutofillSaveIntents.EXTRA_TOKEN])

    private val username: String = pending?.username.orEmpty()
    private val password: String = pending?.password.orEmpty()

    /** 票据取不到（过期 / 进程被杀 / 重复取）⇒ 明文已经不存在，界面必须让用户重来。 */
    private val expired: Boolean = pending == null

    private val packageName: String? =
        pending?.packageName ?: savedStateHandle[AutofillSaveIntents.EXTRA_PACKAGE]
    private val webDomain: String? =
        pending?.webDomain ?: savedStateHandle[AutofillSaveIntents.EXTRA_DOMAIN]

    private val uri = AutofillSaveMatcher.targetUri(webDomain, packageName)
    private val suggestedTitle = AutofillSaveMatcher.defaultTitle(
        webDomain = webDomain,
        packageName = packageName,
        appLabel = appLabelOf(context, packageName),
    )

    private val _state = MutableStateFlow(
        UiState(
            username = username,
            password = password,
            uri = uri,
            title = suggestedTitle,
            // 明文已过期时**不进主流程**：直接按「无法保存」渲染，避免出现一个
            // 账号密码都空着的表单让用户误点保存。
            loading = !expired,
            expired = expired,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { resolveTarget() }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value) }

    /** 新建条目（无同名同站条目时）。 */
    fun saveNew() {
        val vaultId = _state.value.vaultId ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val item = VaultItem(
                id = "",
                title = _state.value.title.ifBlank { suggestedTitle },
                type = VaultItemType.Login,
                username = username,
                password = password,
                uris = uri?.let { listOf(VaultUri(it)) }.orEmpty(),
            )
            val outcome = itemRepository.createItem(vaultId, item)
            _state.update {
                it.copy(
                    saving = false,
                    done = outcome.isSuccess,
                    error = outcome.exceptionOrNull()?.message,
                )
            }
        }
    }

    /**
     * 更新已有条目（同站点 + 同账号命中时）：改密码，并把本次的新网址**追加**进条目。
     *
     * 追加网址是 Bitwarden 保存流程的同款行为：同一账号常用于多个域名
     * （`example.com` / `example.cn`），不追加就会每换一个域名存一条重复条目。
     */
    fun updateExisting() {
        val current = _state.value
        val vaultId = current.vaultId ?: return
        val existing = current.existing ?: return
        val merged = mergeUri(existing)
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val outcome = itemRepository.updateItem(vaultId, merged)
            _state.update {
                it.copy(
                    saving = false,
                    done = outcome.isSuccess,
                    error = outcome.exceptionOrNull()?.message,
                )
            }
        }
    }

    /** 库未解锁时引导解锁：密钥只在内存，锁定状态下无法写密文。 */
    private suspend fun resolveTarget() {
        if (expired) return
        // ★ 保存目标 = **唯一活跃库**（迁移文档阶段 2）。历史行为是「任取一个已解锁库」
        // （`firstOrNull()`），多库并存时写进哪个全凭 Set 迭代顺序 → 用户感知为
        // 「保存重复 / 存了找不到」。活跃库解析为空时仍退化为单个已解锁库，不遍历全部。
        val vaultId = activeVaultStore.resolve()
            ?: vaultRepository.observeUnlockedVaultIds().first().minOrNull()
        if (vaultId == null) {
            _state.update { it.copy(locked = true, loading = false) }
            return
        }
        val vaultName = runCatching {
            vaultRepository.observeVaults().first().firstOrNull { it.id == vaultId }?.name
        }.getOrNull().orEmpty()
        val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
        val existing = AutofillSaveMatcher.findExisting(items, uri, username)
        _state.update {
            it.copy(
                vaultId = vaultId,
                vaultName = vaultName,
                existing = existing,
                title = existing?.title ?: suggestedTitle,
                loading = false,
            )
        }
    }

    /** 更新时把本次来源网址并入条目（已存在则不重复加）。 */
    private fun mergeUri(existing: VaultItem): VaultItem {
        if (uri == null || uri.isBlank()) return existing.copy(password = password)
        val already = existing.uris.any { it.uri == uri }
        val uris = if (already) existing.uris else existing.uris + VaultUri(uri)
        return existing.copy(password = password, uris = uris)
    }

    private fun appLabelOf(context: Context, packageName: String?): String? {
        val pkg = packageName?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    /** 保存界面状态。 */
    data class UiState(
        val username: String = "",
        val password: String = "",
        val uri: String? = null,
        val title: String = "",
        val existing: VaultItem? = null,
        val vaultId: String? = null,
        val vaultName: String = "",
        val locked: Boolean = false,
        val loading: Boolean = true,
        /** 一次性票据已失效（过期 / 进程被回收）⇒ 只能提示重来，不能落库。 */
        val expired: Boolean = false,
        val saving: Boolean = false,
        val done: Boolean = false,
        val error: String? = null,
    )
}
