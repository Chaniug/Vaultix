package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import org.junit.Test

/**
 * 表单快照组装（[buildSnapshot]）与字段值列表往返的单测。
 *
 * 这些是纯函数（不含 Compose），所以能直接测——这也是把它们从
 * ItemFormDialog 抽到 FormValues.kt 的主要目的。
 */
class FormValuesTest {

    private fun values(
        name: String = "条目",
        cardValues: List<String> = emptyList(),
        identityValues: List<String> = emptyList(),
    ) = FormValues(
        name = name,
        username = "user",
        password = "pass",
        notes = "备注",
        totp = "",
        uris = emptyList(),
        cardValues = cardValues,
        identityValues = identityValues,
    )

    @Test
    fun buildSnapshotTrimsTitleAndNotes() {
        val snapshot = buildSnapshot(
            initial = VaultItem(id = "1", title = "旧名"),
            type = VaultItemType.Login,
            values = values(name = "  新名  "),
        )
        assertThat(snapshot.title).isEqualTo("新名")
        assertThat(snapshot.notes).isEqualTo("备注")
    }

    @Test
    fun buildSnapshotUsesSelectedTypeNotInitialType() {
        // 关键回归点：新建时用户选了「银行卡」，若沿用 initial.type 会存成登录条目
        val snapshot = buildSnapshot(
            initial = VaultItem(id = "", title = "我的卡", type = VaultItemType.Login),
            type = VaultItemType.Card,
            values = values(cardValues = listOf("张三", "Visa", "4111", "12", "2029", "123")),
        )
        assertThat(snapshot.type).isEqualTo(VaultItemType.Card)
        assertThat(snapshot.card?.cardholderName).isEqualTo("张三")
        assertThat(snapshot.card?.brand).isEqualTo("Visa")
        assertThat(snapshot.card?.number).isEqualTo("4111")
        assertThat(snapshot.card?.expMonth).isEqualTo("12")
        assertThat(snapshot.card?.expYear).isEqualTo("2029")
        assertThat(snapshot.card?.code).isEqualTo("123")
    }

    @Test
    fun buildSnapshotCarriesMeta() {
        val snapshot = buildSnapshot(
            initial = VaultItem(id = "1", title = "t"),
            type = VaultItemType.Login,
            values = values(),
            meta = ItemMeta(
                folderId = "folder-1",
                favorite = true,
                reprompt = VaultReprompt.Password,
                customFields = listOf(
                    VaultCustomField(name = "k", value = "v", type = CustomFieldType.Hidden),
                ),
            ),
        )
        assertThat(snapshot.folderId).isEqualTo("folder-1")
        assertThat(snapshot.favorite).isTrue()
        assertThat(snapshot.reprompt).isEqualTo(VaultReprompt.Password)
        assertThat(snapshot.customFields.single().type).isEqualTo(CustomFieldType.Hidden)
    }

    @Test
    fun buildSnapshotKeepsIdAndUntouchedSegments() {
        val initial = VaultItem(
            id = "keep-me",
            title = "旧",
            fido2Credentials = emptyList(),
            customFields = listOf(VaultCustomField(name = "k", value = "v")),
        )
        val snapshot = buildSnapshot(
            initial = initial,
            type = VaultItemType.Login,
            values = values(),
        )
        // id 不能被覆盖（否则更新会变成新建）
        assertThat(snapshot.id).isEqualTo("keep-me")
    }

    @Test
    fun cardValuesRoundTrip() {
        val filled = cardValuesOf(
            VaultCard(
                cardholderName = "张三",
                brand = "Visa",
                number = "4111",
                expMonth = "12",
                expYear = "2029",
                code = "123",
            ),
        )
        val card = buildCard(filled)
        assertThat(card.cardholderName).isEqualTo("张三")
        assertThat(card.brand).isEqualTo("Visa")
        assertThat(card.number).isEqualTo("4111")
        assertThat(card.expYear).isEqualTo("2029")
        assertThat(card.code).isEqualTo("123")
    }

    @Test
    fun cardValuesOfNullGivesEmptyStrings() {
        val card = buildCard(cardValuesOf(null))
        assertThat(card.cardholderName).isEmpty()
        assertThat(card.number).isEmpty()
        assertThat(card.code).isEmpty()
    }

    @Test
    fun identityValuesRoundTripKeepsFieldOrder() {
        val raw = identityValuesOf(
            VaultIdentity(
                firstName = "名",
                lastName = "姓",
                city = "北京",
                passportNumber = "E123",
                licenseNumber = "L9",
            ),
        )
        val identity = buildIdentity(raw)
        assertThat(identity.firstName).isEqualTo("名")
        assertThat(identity.lastName).isEqualTo("姓")
        assertThat(identity.city).isEqualTo("北京")
        assertThat(identity.passportNumber).isEqualTo("E123")
        assertThat(identity.licenseNumber).isEqualTo("L9")
        // 未填字段应为空白而非残留
        assertThat(identity.company).isEmpty()
    }

    @Test
    fun shortValueListIsPaddedInsteadOfCrashing() {
        // 防御：值列表长度不足时按空串补齐，而不是越界崩溃
        val card = buildCard(listOf("张三"))
        assertThat(card.cardholderName).isEqualTo("张三")
        assertThat(card.number).isEmpty()
        assertThat(card.code).isEmpty()
    }
}
