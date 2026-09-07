package com.bastion.app.ui.scanner

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 JVM 单测，覆盖 B 批改动：
 *  1. [RotatedLuminanceSource] 的 0/90/180/270 旋转正确性
 *  2. [decodeSourceZxing] 的 2D/1D 拆分
 */
class QrCameraScanSessionDecodeTest {

    /** 一个自包含的 [LuminanceSource]，不依赖 Android，getMatrix 返回真正的 width*height。 */
    private class TestLuminanceSource(
        w: Int,
        h: Int,
        private val data: ByteArray
    ) : LuminanceSource(w, h) {
        init { require(data.size == width * height) }
        override fun getRow(y: Int, row: ByteArray): ByteArray {
            require(y in 0 until height)
            System.arraycopy(data, y * width, row, 0, width)
            return row
        }
        override fun getMatrix(): ByteArray = data.copyOf()
        override fun isCropSupported(): Boolean = false
        override fun isRotateSupported(): Boolean = true
    }

    private fun rampSource(width: Int, height: Int): LuminanceSource {
        // Y 平面写一个 0..255 递增的灰度（按行 0..255 循环），便于看出旋转是否对齐
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = ((x + y) and 0xFF).toByte()
            }
        }
        return TestLuminanceSource(width, height, data)
    }

    @Test
    fun rotatedSourceZeroMatchesOriginal() {
        val base = rampSource(8, 4)
        val rot = RotatedLuminanceSource(base, 0)
        assertEquals(base.width, rot.width)
        assertEquals(base.height, rot.height)
        assertArrayEquals(base.matrix, rot.matrix)
    }

    @Test
    fun rotatedSource180InvertsBothAxes() {
        val base = rampSource(4, 2)
        val rot = RotatedLuminanceSource(base, 180)
        assertEquals(base.width, rot.width)
        assertEquals(base.height, rot.height)
        val src = base.matrix
        val dst = rot.matrix
        for (i in src.indices) {
            assertEquals("index $i", src[i].toInt() and 0xFF, dst[dst.size - 1 - i].toInt() and 0xFF)
        }
    }

    @Test
    fun rotatedSource90ProducesCorrectLayout() {
        // 4×2 ramp → 90° CW 应得 2×4（width=2 height=4）
        val base = rampSource(4, 2)
        val rot = RotatedLuminanceSource(base, 90)
        assertEquals(2, rot.width)
        assertEquals(4, rot.height)

        val src = base.matrix
        val dst = rot.matrix
        // 90° CW 映射：new(x, y) = orig(y, H-1-x) where H=delegate.height
        // 即 mat[(H-1-x)*W + y]
        for (y in 0 until rot.height) {
            for (x in 0 until rot.width) {
                val expected = src[(2 - 1 - x) * 4 + y].toInt() and 0xFF
                val actual = dst[y * rot.width + x].toInt() and 0xFF
                assertEquals("at (x=$x, y=$y)", expected, actual)
            }
        }
    }

    @Test
    fun rotatedSource270Cancels90() {
        val base = rampSource(6, 3)
        val r90 = RotatedLuminanceSource(base, 90)
        // 把 r90 当成 delegate，包装一个 270°，应回到原图
        val r270 = RotatedLuminanceSource(r90, 270)
        assertEquals(base.width, r270.width)
        assertEquals(base.height, r270.height)
        assertArrayEquals(base.matrix, r270.matrix)
    }

    @Test
    fun rotatedSourceMetadataReportsRotateSupport() {
        val base = rampSource(4, 4)
        val rot = RotatedLuminanceSource(base, 90)
        assertEquals(false, rot.isCropSupported)
        assertEquals(true, rot.isRotateSupported)
    }

    @Test
    fun rotatedSource180DimensionsStayTheSame() {
        val base = rampSource(8, 5)
        val rot = RotatedLuminanceSource(base, 180)
        assertEquals(8, rot.width)
        assertEquals(5, rot.height)
    }

    @Test
    fun decodeSourceReturnsEmptyForNoBarcode() {
        // 全 0 不会包含任何条码
        val data = ByteArray(64 * 48)
        val src: LuminanceSource = TestLuminanceSource(64, 48, data)
        val result = com.bastion.app.ui.scanner.decodeSourceZxing(src, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun decodeSourceZxingFailsFastOnUnknownFormats() {
        // 没有任何格式列表时直接返回空
        val data = ByteArray(32 * 24) { 0x55.toByte() }
        val src: LuminanceSource = TestLuminanceSource(32, 24, data)
        val result = com.bastion.app.ui.scanner.decodeSourceZxing(src, emptyList())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun hybridBinarizerAcceptsRotatedSource() {
        // 仅验证 ZXing 的 HybridBinarizer 能用 RotatedLuminanceSource 构造 BinaryBitmap
        // 而不抛与"旋转本身"相关的错误。ramp 内容不会包含任何码，
        // 因此 blackMatrix / decode 抛 NotFoundException 是预期行为。
        val base = rampSource(32, 24)
        val rot = RotatedLuminanceSource(base, 180)
        val binarizer = HybridBinarizer(rot)
        val binary = BinaryBitmap(binarizer)
        // 至少 width/height 已通过 getRow 正确暴露给 ZXing（这里用 getRow 验证）
        val row0 = ByteArray(rot.width)
        rot.getRow(0, row0)
        // 第一次 buildBlackMatrix 抛 NotFoundException 是正常的（无条码），
        // 但若旋转数学有误，会先抛 ArrayIndexOutOfBoundsException。
        try {
            binary.blackMatrix
        } catch (e: com.google.zxing.NotFoundException) {
            // expected
        }
    }
}
