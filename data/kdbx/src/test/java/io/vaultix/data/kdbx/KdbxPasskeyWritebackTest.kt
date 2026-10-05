/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * 通行密钥**写回**（W2 · JSON⇄KDBX 无损互转）单元测试。
 * 既有 `KdbxPasskeyCodecTest` 只覆盖读方向（阶段 A），本文件钉死写方向四条：
 *   ① 字段集 10 键（与 bw2keepass 并集）
 *   ② 多凭证 `_n` 后缀（此前只读得出第一条 = 丢凭证）
 *   ③ credentialId 的 base64url ↔ 标准 base64 往返
 *   ④ 私钥 PEM **受保护**写入（写明文 = KDBX 被别人打开即泄露私钥）
 *
 * 纯 JVM（kotpass 在 JVM 上可跑），不需要 Android 运行时。
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import org.junit.Test

class KdbxPasskeyWritebackTest {

    @Test
    fun `fromCredential emits all ten keys`() {
        // 字段集 = 与 bw2keepass 的并集（施工单 §1.3）。
        assertThat(KdbxPasskeyCodec.fromCredential(credential(), 0).keys).containsExactly(
            "KPEX_PASSKEY_USERNAME",
            "KPEX_PASSKEY_PRIVATE_KEY_PEM",
            "KPEX_PASSKEY_CREDENTIAL_ID",
            "KPEX_PASSKEY_USER_HANDLE",
            "KPEX_PASSKEY_RELYING_PARTY",
            "KPEX_PASSKEY_RP_NAME",
            "KPEX_PASSKEY_USER_DISPLAY_NAME",
            "KPEX_PASSKEY_CREATION_DATE",
            "KPEX_PASSKEY_FLAG_BE",
            "KPEX_PASSKEY_FLAG_BS",
        )
    }

    @Test
    fun `second credential keys carry index suffix`() {
        // 第 0 条不带后缀（兼容既有单凭证文件），第 n 条带 `_n`（对齐 bw2keepass）。
        assertThat(KdbxPasskeyCodec.fromCredential(credential(), 1).keys).containsExactly(
            "KPEX_PASSKEY_USERNAME_1",
            "KPEX_PASSKEY_PRIVATE_KEY_PEM_1",
            "KPEX_PASSKEY_CREDENTIAL_ID_1",
            "KPEX_PASSKEY_USER_HANDLE_1",
            "KPEX_PASSKEY_RELYING_PARTY_1",
            "KPEX_PASSKEY_RP_NAME_1",
            "KPEX_PASSKEY_USER_DISPLAY_NAME_1",
            "KPEX_PASSKEY_CREATION_DATE_1",
            "KPEX_PASSKEY_FLAG_BE_1",
            "KPEX_PASSKEY_FLAG_BS_1",
        )
    }

    @Test
    fun `suffixed names still count as passkey family`() {
        // 否则第 2 条凭证会被当成普通自定义字段 ⇒ 详情页明文展示私钥 PEM。
        assertThat(KdbxPasskeyCodec.isPasskeyFieldName("KPEX_PASSKEY_USERNAME_1")).isTrue()
        assertThat(KdbxPasskeyCodec.isPasskeyFieldName("KPEX_PASSKEY_PRIVATE_KEY_PEM_2")).isTrue()
        assertThat(KdbxPasskeyCodec.isPasskeyFieldName("MyNote")).isFalse()
    }

    @Test
    fun `only key material fields are protected`() {
        assertThat(KdbxPasskeyCodec.isProtectedField("KPEX_PASSKEY_PRIVATE_KEY_PEM")).isTrue()
        assertThat(KdbxPasskeyCodec.isProtectedField("KPEX_PASSKEY_CREDENTIAL_ID")).isTrue()
        assertThat(KdbxPasskeyCodec.isProtectedField("KPEX_PASSKEY_USER_HANDLE_1")).isTrue()
        assertThat(KdbxPasskeyCodec.isProtectedField("KPEX_PASSKEY_USERNAME")).isFalse()
    }

    @Test
    fun `credentialId round trips through base64url`() {
        val written = KdbxPasskeyCodec.fromCredential(credential(), 0)
        // 标准 base64 的 "////" 落库时转成 base64url 的 "____"。
        assertThat(written["KPEX_PASSKEY_CREDENTIAL_ID"]).isEqualTo("____")
        val read = KdbxPasskeyCodec.toCredential(
            KdbxPasskeyCodec.fromFieldMap(written),
            title = "Example",
        )
        assertThat(read?.credentialId).isEqualTo(CREDENTIAL_ID_B64)
    }

    @Test
    fun `groupPasskeyFields splits multiple credentials`() {
        val raw = LinkedHashMap<String, String>()
        raw.putAll(KdbxPasskeyCodec.fromCredential(credential(), 0))
        raw.putAll(KdbxPasskeyCodec.fromCredential(secondCredential(), 1))
        val groups = KdbxPasskeyCodec.groupPasskeyFields(raw)
        assertThat(groups).hasSize(2)
        // 组内键名已还原成无后缀的规范名（下标只用于分组，不进字段集）。
        assertThat(groups[0]).containsKey(KdbxPasskeyCodec.FIELD_USERNAME)
        assertThat(groups[1]).containsKey(KdbxPasskeyCodec.FIELD_USERNAME)
        assertThat(groups[0][KdbxPasskeyCodec.FIELD_USERNAME]).isEqualTo("alice@example.com")
        assertThat(groups[1][KdbxPasskeyCodec.FIELD_USERNAME]).isEqualTo("bob@example.com")
    }

    @Test
    fun `applyPasskeys writes private key encrypted`() {
        val result = KdbxItemWriter.applyPasskeys(
            EntryFields.createDefault(),
            itemWith(listOf(credential())),
            null,
        )
        val pem = result[KdbxPasskeyCodec.FIELD_PRIVATE_KEY]
        assertThat(pem).isInstanceOf(EntryValue.Encrypted::class.java)
        assertThat(pem?.content).isEqualTo(PEM)
        // KeePassDX 的空占位标记：表示「这个条目是通行密钥条目」。
        assertThat(result[KdbxPasskeyCodec.FIELD_PASSKEY]?.content).isEqualTo("")
    }

    @Test
    fun `applyPasskeys writes every credential with its own suffix`() {
        val result = KdbxItemWriter.applyPasskeys(
            EntryFields.createDefault(),
            itemWith(listOf(credential(), secondCredential())),
            null,
        )
        assertThat(result["KPEX_PASSKEY_USERNAME"]?.content).isEqualTo("alice@example.com")
        assertThat(result["KPEX_PASSKEY_USERNAME_1"]?.content).isEqualTo("bob@example.com")
    }

    @Test
    fun `applyPasskeys removes a credential that disappeared`() {
        val before = itemWith(listOf(credential(), secondCredential()))
        val after = itemWith(listOf(credential()))
        val existing = KdbxItemWriter.applyPasskeys(EntryFields.createDefault(), before, null)
        val result = KdbxItemWriter.applyPasskeys(existing, after, before)
        assertThat(result["KPEX_PASSKEY_USERNAME"]).isNotNull()
        assertThat(result["KPEX_PASSKEY_USERNAME_1"]).isNull()
    }

    private fun itemWith(credentials: List<VaultFido2Credential>) = VaultItem(
        id = "kdbx-entry:00000000-0000-0000-0000-000000000001",
        title = "Example",
        fido2Credentials = credentials,
    )

    private fun credential() = VaultFido2Credential(
        credentialId = CREDENTIAL_ID_B64,
        rpId = "example.com",
        rpName = "Example",
        userName = "alice@example.com",
        userDisplayName = "Alice",
        userHandle = "dXNlci1oYW5kbGU=",
        keyValue = PEM,
        creationDate = "2026-10-06T00:00:00.000Z",
    )

    private fun secondCredential() = credential().copy(
        credentialId = "AAAA",
        userName = "bob@example.com",
    )

    private companion object {
        /** 标准 base64（含 `/`）⇒ 落库需转成 base64url，用于验证往返。 */
        const val CREDENTIAL_ID_B64 = "////"
        const val PEM = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMG\n-----END PRIVATE KEY-----"
    }
}
