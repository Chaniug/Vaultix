package io.vaultix.vaultix.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.SecureCredentialStore
import org.junit.Test

/**
 * [WebDavCredentialStore] 的单测。
 *
 * 覆盖的是**几处写错了不报错、只会静默出错**的地方：
 *
 * - credentialId 由「服务器 + 用户名」算出 ⇒ 等价的输入必须命中同一格（否则留孤儿密文）；
 * - 用户名可以含 `:`（域账号）⇒ 分隔符选错会静默截断密码；
 * - 账号索引是裸文本 ⇒ 解析失败必须跳过坏行而不是让整个清单作废；
 * - 索引字段里的控制字符会让该账号静默消失 ⇒ 清洗必须与 id 推导同源。
 *
 * [SecureCredentialStore] 依赖 Android Keystore，这里用内存 map 顶替 ——
 * 被测的是本类的**序列化与索引逻辑**，与底层怎么加密无关。
 */
class WebDavCredentialStoreTest {

    private val backing = mutableMapOf<String, String>()

    private val store = WebDavCredentialStore(
        mockk<SecureCredentialStore>().apply {
            every { putString(any(), any()) } answers { backing[firstArg()] = secondArg() }
            every { getString(any()) } answers { backing[firstArg()] }
            every { remove(any()) } answers { backing.remove(firstArg()); Unit }
        },
    )

    @Test
    fun equivalentServerUrlsShareOneCredentialSlot() {
        val first = store.save("https://nas.local/dav", "user", "old")
        val second = store.save("https://nas.local/dav/", "user", "new")
        val third = store.save("  HTTPS://NAS.LOCAL/dav  ", "user", "newest")

        assertThat(second).isEqualTo(first)
        assertThat(third).isEqualTo(first)
        // 同一格 ⇒ 后写覆盖前写，不留第二份密文。
        assertThat(backing.keys.count { it.endsWith(first) }).isEqualTo(1)
        assertThat(store.read(first)?.password).isEqualTo("newest")
    }

    @Test
    fun differentAccountsGetDifferentSlots() {
        val a = store.save(SERVER, "alice", "p")
        val b = store.save(SERVER, "bob", "p")

        assertThat(a).isNotEqualTo(b)
        assertThat(store.read(a)?.password).isEqualTo("p")
        assertThat(store.read(b)?.password).isEqualTo("p")
    }

    @Test
    fun usernameContainingColonAndBackslashSurvives() {
        // `:` 是 WebDAV 域账号（`DOMAIN\user`）里合法且常见的字符 ——
        // 拿它当分隔符会把密码静默截断，这里钉死"不影响往返"。
        val id = store.save(SERVER, "DOMAIN\\user:sub", "pa:ss/word")

        val credentials = store.read(id)
        assertThat(credentials?.username).isEqualTo("DOMAIN\\user:sub")
        assertThat(credentials?.password).isEqualTo("pa:ss/word")
    }

    @Test
    fun readReturnsNullForBrokenPayload() {
        val id = store.save(SERVER, "user", "pass")
        backing[storageKeyOf(id)] = "没有分隔符的裸串"

        // 调用方不分原因，一律按"请重新填写"处理 ⇒ 返回 null 即可。
        assertThat(store.read(id)).isNull()
    }

    @Test
    fun saveThenListThenRemoveKeepsIndexInSync() {
        val alice = store.save(SERVER, "alice", "SECRET-alice")
        val bob = store.save(SERVER, "bob", "SECRET-bob")

        val listed = store.listConfigured()
        assertThat(listed.map { it.credentialId }).containsExactly(alice, bob)
        assertThat(listed.map { it.username }).containsExactly("alice", "bob")
        // ★ 索引里绝不能出现密码（它只是一份"哪些 id 存在"的目录）。
        assertThat(backing[INDEX_KEY]).doesNotContain("SECRET")

        store.remove(alice)
        assertThat(store.read(alice)).isNull()
        assertThat(store.listConfigured().map { it.credentialId }).containsExactly(bob)
    }

    @Test
    fun overwritingSameAccountDoesNotDuplicateIndexEntry() {
        store.save(SERVER, "alice", "p1")
        store.save(SERVER, "alice", "p2")
        store.save(SERVER, "alice", "p3")

        assertThat(store.listConfigured()).hasSize(1)
    }

    @Test
    fun malformedIndexLinesAreSkippedNotFatal() {
        store.save(SERVER, "alice", "p")
        store.save(SERVER, "bob", "p")

        // 坏行必须只让自己消失，不能让剩下两个账号一起列不出来。
        backing[INDEX_KEY] = "这一行没有字段分隔符\n" + backing[INDEX_KEY].orEmpty()

        assertThat(store.listConfigured().map { it.username }).containsExactly("alice", "bob")
    }

    @Test
    fun controlCharactersInServerUrlDoNotHideTheAccount() {
        val id = store.save("https://nas.local/da\tv\nx", "alice", "p")

        // 清洗必须与 id 推导同源，否则这里列出的 id 会与 save 返回的 id 不一样。
        assertThat(store.listConfigured().map { it.credentialId }).containsExactly(id)
    }

    /** 取回某 credentialId 实际落盘用的键（前缀是本类的私有实现细节，反查即可）。 */
    private fun storageKeyOf(credentialId: String): String =
        backing.keys.first { it.endsWith(credentialId) }

    private companion object {
        const val SERVER = "https://nas.local/dav"
        const val INDEX_KEY = "webdav_account_index"
    }
}
