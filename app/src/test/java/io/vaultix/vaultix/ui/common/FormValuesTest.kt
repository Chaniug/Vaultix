package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.SshFingerprint
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSshKey
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

    // ------------------------------------------------------------------
    // SSH 段（S22）
    // ------------------------------------------------------------------

    @Test
    fun sshValuesRoundTrip() {
        val raw = sshValuesOf(
            VaultSshKey(privateKey = "priv", publicKey = "pub", keyFingerprint = "fp"),
        )
        assertThat(raw).hasSize(3)
        val ssh = buildSshKey(raw)
        assertThat(ssh.privateKey).isEqualTo("priv")
        assertThat(ssh.publicKey).isEqualTo("pub")
        assertThat(ssh.keyFingerprint).isEqualTo("fp")
    }

    @Test
    fun sshValuesOfNullGivesEmptyStrings() {
        assertThat(sshValuesOf(null)).containsExactly("", "", "").inOrder()
    }

    @Test
    fun buildSshKeyTrimsWhitespace() {
        // 从终端复制的密钥常带首尾空白/换行，留着会污染详情页与复制结果
        val ssh = buildSshKey(listOf(" \n priv \n ", "pub\n", "\tfp "))
        assertThat(ssh.privateKey).isEqualTo("priv")
        assertThat(ssh.publicKey).isEqualTo("pub")
        assertThat(ssh.keyFingerprint).isEqualTo("fp")
    }

    @Test
    fun buildSnapshotWritesSshKeyForSshType() {
        // 回归点：此前 SshKey 走的是 `-> Unit`，新建 SSH 条目只能存成空壳
        val snapshot = buildSnapshot(
            initial = VaultItem(id = "", title = "我的密钥", type = VaultItemType.SshKey),
            type = VaultItemType.SshKey,
            values = values(),
            ssh = listOf("priv", "pub", "fp"),
        )
        assertThat(snapshot.sshKey?.privateKey).isEqualTo("priv")
        assertThat(snapshot.sshKey?.publicKey).isEqualTo("pub")
        assertThat(snapshot.sshKey?.keyFingerprint).isEqualTo("fp")
    }

    @Test
    fun buildSnapshotKeepsSshKeyWhenEditingOtherType() {
        // 类型守恒：编辑登录/银行卡等条目时绝不能把原有 SSH 段冲掉
        val snapshot = buildSnapshot(
            initial = VaultItem(
                id = "1",
                title = "t",
                sshKey = VaultSshKey(privateKey = "keep", publicKey = "keep-pub"),
            ),
            type = VaultItemType.Login,
            values = values(),
            ssh = emptyList(),
        )
        assertThat(snapshot.sshKey?.privateKey).isEqualTo("keep")
        assertThat(snapshot.sshKey?.publicKey).isEqualTo("keep-pub")
    }

    @Test
    fun sshFingerprintIsDerivedFromPastedPublicKey() {
        // 模拟用户把公钥粘进空表单：指纹应自动出现
        val fields = mutableListOf("priv", "", "")
        applyPublicKeyChange(fields, sshPublicKey)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isEqualTo(ed25519Fingerprint)
    }

    @Test
    fun sshFingerprintFollowsPublicKeyChange() {
        val fields = mutableListOf("priv", sshPublicKey, ed25519Fingerprint)
        applyPublicKeyChange(fields, otherPublicKey)
        assertThat(fields[SSH_PUBLIC_KEY_INDEX]).isEqualTo(otherPublicKey)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isNotEqualTo(ed25519Fingerprint)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).startsWith("SHA256:")
    }

    @Test
    fun manualFingerprintSurvivesPublicKeyChange() {
        // 用户手填的值必须保住：那是他的显式意图
        val manual = "SHA256:someoneElsesValue"
        val fields = mutableListOf("priv", sshPublicKey, manual)
        applyPublicKeyChange(fields, otherPublicKey)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isEqualTo(manual)
    }

    @Test
    fun followStateSelfHealsWhenManualValueIsRestored() {
        // 手改后不再跟随；但改回推导值时，应重新跟随（否则会永久卡在「手动」）
        val fields = mutableListOf("priv", sshPublicKey, "手改的值")
        applyPublicKeyChange(fields, sshPublicKey)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isEqualTo("手改的值")
        fields[SSH_FINGERPRINT_INDEX] = ed25519Fingerprint
        applyPublicKeyChange(fields, otherPublicKey)
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isNotEqualTo("手改的值")
        assertThat(fields[SSH_FINGERPRINT_INDEX]).startsWith("SHA256:")
    }

    @Test
    fun fingerprintIsClearedWhenPublicKeyBecomesUnparsable() {
        // 宁可空着，也不能留一个与当前公钥不符的指纹 —— 那是会骗人的错信息
        val fields = mutableListOf("priv", sshPublicKey, ed25519Fingerprint)
        applyPublicKeyChange(fields, "ssh-ed25519 这不是合法公钥")
        assertThat(fields[SSH_PUBLIC_KEY_INDEX]).isEqualTo("ssh-ed25519 这不是合法公钥")
        assertThat(fields[SSH_FINGERPRINT_INDEX]).isEmpty()
    }

    @Test
    fun applyPublicKeyChangeToleratesShortList() {
        // 防御：列表短于预期时不应越界崩溃
        val fields = mutableListOf("仅一项")
        applyPublicKeyChange(fields, sshPublicKey)
        assertThat(fields).hasSize(1)
    }

    /**
     * 一把格式合法的 ed25519 公钥（一次性生成的测试密钥）。
     *
     * [ed25519Fingerprint] 是 `ssh-keygen -lf` 给出的**权威指纹**，
     * 不是用本实现算出来再回填的 —— 否则测不出算法错误。
     */
    private val sshPublicKey =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINjWdAG2egXV4ToTYHJa0mZQdLMehOxakRzm" +
            "694r4Fyn"

    /**
     * 另一把**合法**公钥：由 [sshPublicKey] 改写末位字符得来。
     * 长度与算法名字段不变 ⇒ 仍能通过解析；密钥材料变了 ⇒ 指纹必然不同。
     * 这样就不必再抄一长串字面量（抄写本身才是出错来源）。
     */
    private val otherPublicKey = sshPublicKey.dropLast(1) + "Z"

    private val ed25519Fingerprint = "SHA256:8gQgmsoPYszLtQT8qR165HjWs5prluGD9UzZXNNAjco"
}
