package io.vaultix.data.kdbx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `KdbxCustomIconStore` —— 自定义图标字节的存取。
 *
 * ## 为什么要单独一条
 *
 * 它是"条目只带 UUID、字节集中存一份"的落点。这里的失效**不会报错**：
 * 图标安静地消失、退回品牌图标或首字母 —— 用户只会觉得"我设的图标没了"，
 * 没有任何日志会指向这里。所以两个最容易出错的行为要钉住：
 *
 * 1. **空字节必须忽略**（否则会存进一个"有 id 但解不出图"的坑）；
 * 2. **clear 之后必须真的取不到**（`lockAll` 依赖它）。
 */
class KdbxCustomIconStoreTest {

    @Test
    fun `写入后可读回同一份字节`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        KdbxCustomIconStore.put(ID, png)

        assertArrayEquals(png, KdbxCustomIconStore.get(ID))
    }

    @Test
    fun `空字节不写入`() {
        KdbxCustomIconStore.put("empty-icon", ByteArray(0))

        assertNull(KdbxCustomIconStore.get("empty-icon"))
    }

    @Test
    fun `未知 id 与 null 都返回 null`() {
        assertNull(KdbxCustomIconStore.get("nope"))
        assertNull(KdbxCustomIconStore.get(null))
    }

    @Test
    fun `clear 之后取不到`() {
        KdbxCustomIconStore.put(ID, byteArrayOf(1, 2, 3))
        KdbxCustomIconStore.clear()

        assertNull("lockAll 之后本机不该再留着库内容", KdbxCustomIconStore.get(ID))
    }

    private companion object {
        const val ID = "6f1c2d3e-0000-0000-0000-000000000001"
    }
}
