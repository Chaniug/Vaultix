package io.vaultix.vaultix.ui.addvault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [suggestedKdbxFileName] 的单测。
 *
 * 起因（真机反馈）：新建本地 KDBX 时文件名恒为 `vaultix.kdbx`，与用户填写的库名无关
 * ⇒ 建多个库之后，磁盘上全是同名文件（第二个起被系统改成 `vaultix(1).kdbx`），
 * 在文件管理器里根本分不清哪个是哪个。
 *
 * 这里钉住的是**清洗规则**，不是某个具体界面 —— 所以是纯函数测试，不需要 Compose。
 *
 * ⚠️ 断言全部用 `isEqualTo` 写死精确值。这类函数的失败方式是"悄悄放过一个非法字符"
 * 或"多拼了一个后缀"，松断言（`endsWith(".kdbx")`）照样绿，精确断言立刻红。
 */
class KdbxFileNameTest {

    // ── 主路径 ────────────────────────────────────────────────────────────────

    @Test
    fun `uses vault name as file name`() {
        assertThat(suggestedKdbxFileName("unit")).isEqualTo("unit.kdbx")
    }

    @Test
    fun `keeps chinese name as is`() {
        assertThat(suggestedKdbxFileName("我的密码库")).isEqualTo("我的密码库.kdbx")
    }

    @Test
    fun `keeps inner spaces`() {
        assertThat(suggestedKdbxFileName("Work Vault")).isEqualTo("Work Vault.kdbx")
    }

    // ── 后缀不重复 ────────────────────────────────────────────────────────────

    @Test
    fun `does not duplicate kdbx suffix`() {
        assertThat(suggestedKdbxFileName("unit.kdbx")).isEqualTo("unit.kdbx")
    }

    @Test
    fun `normalizes uppercase suffix`() {
        // 用户输入 `MyVault.KDBX`：不该变成 `MyVault.KDBX.kdbx`
        assertThat(suggestedKdbxFileName("MyVault.KDBX")).isEqualTo("MyVault.kdbx")
    }

    @Test
    fun `keeps inner kdbx word`() {
        // 只去掉**结尾**的后缀，中间的不动
        assertThat(suggestedKdbxFileName("kdbx.notes")).isEqualTo("kdbx.notes.kdbx")
    }

    // ── 非法字符 ──────────────────────────────────────────────────────────────

    @Test
    fun `replaces path separators`() {
        assertThat(suggestedKdbxFileName("我的/库")).isEqualTo("我的_库.kdbx")
        assertThat(suggestedKdbxFileName("a\\b")).isEqualTo("a_b.kdbx")
    }

    @Test
    fun `replaces every windows illegal char`() {
        assertThat(suggestedKdbxFileName("""a\b/c:d*e?f"g<h>i|j"""))
            .isEqualTo("a_b_c_d_e_f_g_h_i_j.kdbx")
    }

    @Test
    fun `replaces control chars`() {
        assertThat(suggestedKdbxFileName("a\tb\nc")).isEqualTo("a_b_c.kdbx")
    }

    // ── 首尾空白与末尾的点 ────────────────────────────────────────────────────

    @Test
    fun `trims surrounding whitespace`() {
        assertThat(suggestedKdbxFileName("  unit  ")).isEqualTo("unit.kdbx")
    }

    @Test
    fun `trims full width space`() {
        // U+3000 全角空格：中文输入法下很容易混进来
        assertThat(suggestedKdbxFileName("\u3000unit\u3000")).isEqualTo("unit.kdbx")
    }

    @Test
    fun `drops trailing dot`() {
        // Windows 会静默去掉文件名末尾的点 ⇒ 不清洗会让"面板显示的名字"与"落盘的名字"不一致
        assertThat(suggestedKdbxFileName("unit.")).isEqualTo("unit.kdbx")
    }

    @Test
    fun `drops trailing dots and spaces together`() {
        assertThat(suggestedKdbxFileName("unit . . ")).isEqualTo("unit.kdbx")
    }

    // ── 空与兜底 ──────────────────────────────────────────────────────────────

    @Test
    fun `falls back when name is blank`() {
        assertThat(suggestedKdbxFileName("")).isEqualTo(DEFAULT_KDBX_FILE_NAME)
        assertThat(suggestedKdbxFileName("   ")).isEqualTo(DEFAULT_KDBX_FILE_NAME)
    }

    @Test
    fun `falls back when only suffix is given`() {
        // 只填 `.kdbx` ⇒ 去后缀后为空，不能用空名 + 后缀凑出 `.kdbx`（隐藏文件）
        assertThat(suggestedKdbxFileName(".kdbx")).isEqualTo(DEFAULT_KDBX_FILE_NAME)
    }

    @Test
    fun `falls back when everything is illegal`() {
        // 全是 `_` 也算有内容，不该兜底；这里测的是**全被 trim 掉**的情况
        assertThat(suggestedKdbxFileName(" . ")).isEqualTo(DEFAULT_KDBX_FILE_NAME)
    }

    @Test
    fun `all illegal chars still yields a name`() {
        // `///` → `___`，不是空串 ⇒ 保留，用户还能看出自己填过东西
        assertThat(suggestedKdbxFileName("///")).isEqualTo("___.kdbx")
    }

    // ── 长度 ──────────────────────────────────────────────────────────────────

    @Test
    fun `truncates overly long name`() {
        val long = "库".repeat(200)
        val result = suggestedKdbxFileName(long)

        assertThat(result.endsWith(".kdbx")).isTrue()
        // 64 个码点 + `.kdbx`（5 个）
        assertThat(result).hasLength(64 + ".kdbx".length)
    }

    @Test
    fun `does not split surrogate pair when truncating`() {
        // emoji 是代理对（2 个 Char / 1 个码点）。按 Char 截断会产出孤立代理，
        // 落盘后变 `?` —— 这是最容易在"看起来能用"的截断实现里翻车的点。
        val emoji = "🔐"          // U+1F510
        val long = emoji.repeat(100)
        val result = suggestedKdbxFileName(long)

        val base = result.removeSuffix(".kdbx")
        assertThat(base).isEqualTo(emoji.repeat(64))
        // 再确认没有残留孤立代理：往返编码后应当等长
        assertThat(base.toByteArray(Charsets.UTF_8)).hasLength(64 * 4)
    }

    @Test
    fun `keeps name exactly at limit`() {
        val exact = "a".repeat(64)
        assertThat(suggestedKdbxFileName(exact)).isEqualTo("$exact.kdbx")
    }
}
