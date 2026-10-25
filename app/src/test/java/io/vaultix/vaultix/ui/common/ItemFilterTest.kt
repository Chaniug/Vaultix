package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import org.junit.Test

/**
 * 条目搜索过滤（[ItemFilter]）单测。
 *
 * 抽成纯函数的原因：条目列表 / 验证码列表 / 通行密钥列表三处都要搜索，
 * 共用同一套匹配语义可避免行为漂移。
 */
class ItemFilterTest {

    private val items = listOf(
        VaultItem(
            id = "1",
            title = "GitHub",
            username = "alice",
            uris = listOf(VaultUri("https://github.com")),
        ),
        VaultItem(id = "2", title = "Gmail", username = "bob@example.com"),
        VaultItem(
            id = "3",
            title = "招商银行",
            uris = listOf(VaultUri("https://bank.example.cn")),
        ),
    )

    @Test
    fun emptyQueryReturnsAll() {
        assertThat(ItemFilter.filter(items, "")).containsExactlyElementsIn(items).inOrder()
        assertThat(ItemFilter.filter(items, "   ")).containsExactlyElementsIn(items).inOrder()
    }

    @Test
    fun matchesTitleIgnoringCase() {
        assertThat(ItemFilter.filter(items, "git").map { it.id }).containsExactly("1")
        assertThat(ItemFilter.filter(items, "GIT").map { it.id }).containsExactly("1")
    }

    @Test
    fun matchesUsername() {
        assertThat(ItemFilter.filter(items, "bob").map { it.id }).containsExactly("2")
        assertThat(ItemFilter.filter(items, "example.com").map { it.id }).containsExactly("2")
    }

    @Test
    fun matchesUri() {
        assertThat(ItemFilter.filter(items, "bank.example.cn").map { it.id }).containsExactly("3")
    }

    @Test
    fun matchesChineseTitle() {
        assertThat(ItemFilter.filter(items, "招商").map { it.id }).containsExactly("3")
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertThat(ItemFilter.filter(items, "不存在的关键词")).isEmpty()
    }

    @Test
    fun matchesNotes() {
        // #191：识别信息常写在备注里（「建行 工资卡」），搜不到等于搜索坏了。
        val card = VaultItem(id = "n", title = "银行卡", notes = "建行 工资卡，柜台办的")
        assertThat(ItemFilter.matches(card, "工资")).isTrue()
        assertThat(ItemFilter.matches(card, "工资卡")).isTrue()
        assertThat(ItemFilter.matches(card, "中行")).isFalse()
    }

    @Test
    fun matchesCustomFieldNameAndValue() {
        val item = VaultItem(
            id = "cf",
            title = "公司 VPN",
            customFields = listOf(
                VaultCustomField(name = "工号", value = "A10293", type = CustomFieldType.Text),
            ),
        )
        assertThat(ItemFilter.matches(item, "工号")).isTrue()
        assertThat(ItemFilter.matches(item, "a10293")).isTrue()
        assertThat(ItemFilter.matches(item, "A102")).isTrue()
    }

    @Test
    fun passwordIsNotSearchable() {
        // 有意为之：搜密码会让"输错一个字母"也命中，且结果截图会泄露密码。
        val item = VaultItem(id = "p", title = "站点", password = "Tr0ub4dor")
        assertThat(ItemFilter.matches(item, "Tr0ub4dor")).isFalse()
    }

    @Test
    fun nonLoginItemIsSearchableByTitle() {
        // 银行卡 / 身份等条目没有 username，仍应能按标题搜到
        val card = VaultItem(id = "c", title = "白金卡", type = VaultItemType.Card)
        assertThat(ItemFilter.matches(card, "白金")).isTrue()
        assertThat(ItemFilter.matches(card, "xxx")).isFalse()
    }
}
