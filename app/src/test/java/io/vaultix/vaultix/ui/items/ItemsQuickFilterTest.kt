package io.vaultix.vaultix.ui.items

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import org.junit.Test

/**
 * 快捷筛选谓词单测（顶栏点库名展开的那一排 chip）。
 *
 * 这些断言的价值在于**口径**：筛选结果必须与对应二级页（验证码页 / 通行密钥页）一致，
 * 否则用户会看到「筛出 3 条、点进去只有 2 条」这种自相矛盾的结果。
 */
class ItemsQuickFilterTest {

    private val login = VaultItem(id = "1", title = "登录", username = "u", password = "p")
    private val withTotp = login.copy(id = "2", title = "带验证码", totp = "JBSWY3DPEHPK3PXP")
    private val passkey = login.copy(
        id = "3",
        title = "带通行密钥",
        fido2Credentials = listOf(VaultFido2Credential(credentialId = "c", rpId = "x.com")),
    )
    private val ssh = VaultItem(id = "4", title = "服务器", type = VaultItemType.SshKey)
    private val note = VaultItem(id = "5", title = "备忘", type = VaultItemType.SecureNote)
    private val favorite = login.copy(id = "6", title = "常用", favorite = true)

    private val all = listOf(login, withTotp, passkey, ssh, note, favorite)

    @Test
    fun allReturnsEverythingUntouched() {
        // All 必须**原样返回**（不复制、不重排）：列表顺序由分组 / 仓库决定
        assertThat(all.applyQuickFilter(ItemsQuickFilter.All)).isSameInstanceAs(all)
    }

    @Test
    fun totpFilterMatchesNonBlankSecretOnly() {
        // 空白字符串不算「有验证码」（服务端可能给空串，口径与验证码页一致）
        val blankTotp = login.copy(id = "7", title = "空密钥", totp = "")
        val items = all + blankTotp
        val result = items.applyQuickFilter(ItemsQuickFilter.Totp)

        assertThat(result.map { it.id }).containsExactly("2")
    }

    @Test
    fun passkeyFilterMatchesAnyCredential() {
        val result = all.applyQuickFilter(ItemsQuickFilter.Passkey)

        assertThat(result.map { it.id }).containsExactly("3")
    }

    @Test
    fun typeFiltersMatchTheirOwnType() {
        assertThat(all.applyQuickFilter(ItemsQuickFilter.Ssh).map { it.id }).containsExactly("4")
        assertThat(all.applyQuickFilter(ItemsQuickFilter.Note).map { it.id }).containsExactly("5")
    }

    @Test
    fun favoriteFilterMatchesFavoriteFlag() {
        assertThat(all.applyQuickFilter(ItemsQuickFilter.Favorite).map { it.id })
            .containsExactly("6")
    }

    @Test
    fun filtersComposeAsIntersectionWithSearch() {
        // 先筛选再搜索是 AND 语义：筛选出「有验证码」的集合里再按标题搜
        val filtered = all.applyQuickFilter(ItemsQuickFilter.Totp)
        val searched = io.vaultix.vaultix.ui.common.ItemFilter.filter(filtered, "登录")

        // "带验证码" 的标题不含「登录」→ 交集为空（这正是 AND 的证据）
        assertThat(searched).isEmpty()
    }

    @Test
    fun storageKeyRoundTrip() {
        ItemsQuickFilter.entries.forEach { filter ->
            assertThat(ItemsQuickFilter.from(filter.storageKey)).isEqualTo(filter)
        }
        // 未知 / 历史值一律回落 All（绝不落到空筛选把条目藏起来）
        assertThat(ItemsQuickFilter.from("unknown")).isEqualTo(ItemsQuickFilter.All)
        assertThat(ItemsQuickFilter.from(null)).isEqualTo(ItemsQuickFilter.All)
    }
}
