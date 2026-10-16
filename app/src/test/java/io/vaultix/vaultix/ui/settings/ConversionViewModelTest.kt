/*
 * Vaultix — app:ui:settings（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [ConversionViewModel] 门禁（施工单 W5 · JSON⇄KDBX 入口 UI 的状态机）。
 *
 * ## 钉死的三件事
 *
 * 1. **密码即弃**：预览一出清文件密码、转换一成功清输出密码 —— 明文密码在
 *    UiState 里过夜是本项目最不能犯的一类错（Docs/09）；
 * 2. **错误归类**：每种领域异常都要落到对应的 ErrorKind（UI 文案的可执行性
 *    全靠这一层：密码错引导用户改密码，自检失败引导用户报 Bug）；
 * 3. **按钮可用性**：预览/转换的前置条件（选了文件、密码达长度下限、两次一致）
 *    缺一不可亮 —— 灰着比报错友好。
 *
 * ## 调度说明
 *
 * ViewModel 内部对 repos 的调用包了 `withContext(Dispatchers.Default)`
 * （600k PBKDF2 不能上主线程）。那是**真实线程**，虚拟时间等不到它 ——
 * 所以无事件可等的状态断言走 [awaitUntil] 轮询；有事件的断言走 turbine 的
 * `awaitItem()`（挂起等待本身兼容真实异步）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.vaultix.domain.ConversionOutput
import io.vaultix.domain.ConversionStats
import io.vaultix.domain.JsonImportPreview
import io.vaultix.domain.VaultConversionException
import io.vaultix.domain.VaultConverterRepository
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ConversionViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 预览（JSON → KDBX 的知情环节）----

    @Test
    fun `预览成功_带上计数并清空文件密码`() = runTest {
        val viewModel = conversionViewModel(preview = samplePreview())

        viewModel.onJsonFilePicked("vault.json")
        viewModel.onJsonPasswordChange("file-pw-1234")
        viewModel.previewJsonImport("{}")

        awaitUntil { viewModel.state.value.jsonPreview != null }

        val state = viewModel.state.value
        assertThat(state.jsonPreview?.stats?.itemCount).isEqualTo(3)
        assertThat(state.jsonPreview?.stats?.totpCount).isEqualTo(1)
        assertThat(state.jsonPreview?.stats?.passkeyCount).isEqualTo(2)
        assertThat(state.jsonPreview?.stats?.skippedCount).isEqualTo(1)
        assertThat(state.jsonPreview?.stats?.typeDegradedCount).isEqualTo(1)
        // 密码即弃：预览已出，后续转换用不到文件密码。
        assertThat(state.jsonPassword).isEmpty()
        assertThat(state.error).isNull()
    }

    @Test
    fun `预览失败_密码错与格式非法各有归因`() = runTest {
        val wrongPw = conversionViewModel(previewThrows = VaultConversionException.WrongPassword())
        wrongPw.onJsonFilePicked("vault.json")
        wrongPw.onJsonPasswordChange("bad")
        wrongPw.previewJsonImport("{}")
        awaitUntil { wrongPw.state.value.error != null }
        assertThat(wrongPw.state.value.error)
            .isEqualTo(ConversionViewModel.ErrorKind.WRONG_PASSWORD)

        val malformed = conversionViewModel(previewThrows = VaultConversionException.MalformedJson())
        malformed.onJsonFilePicked("vault.json")
        malformed.onJsonPasswordChange("bad")
        malformed.previewJsonImport("{}")
        awaitUntil { malformed.state.value.error != null }
        assertThat(malformed.state.value.error)
            .isEqualTo(ConversionViewModel.ErrorKind.MALFORMED_JSON)
        // 失败不得留下半成品预览。
        assertThat(malformed.state.value.jsonPreview).isNull()
    }

    @Test
    fun `未选文件或空密码时预览按钮不可用`() {
        val viewModel = ConversionViewModel(mockk())

        assertThat(viewModel.state.value.canPreviewJson).isFalse()

        viewModel.onJsonFilePicked("vault.json")
        assertThat(viewModel.state.value.canPreviewJson).isFalse() // 密码还空着

        viewModel.onJsonPasswordChange("pw")
        assertThat(viewModel.state.value.canPreviewJson).isTrue()
    }

    // ---- 转换（JSON → KDBX）----

    @Test
    fun `转换KDBX成功_输出密码即弃且发出保存事件`() = runTest {
        val bytes = byteArrayOf(0x4B, 0x44, 0x42, 0x58)
        val viewModel = conversionViewModel(
            preview = samplePreview(),
            output = ConversionOutput(bytes, ConversionStats(3, 1, 2, 1, 1)),
        )
        viewModel.onJsonFilePicked("vault.json")
        viewModel.onJsonPasswordChange("file-pw-1234")
        viewModel.previewJsonImport("{}")
        awaitUntil { viewModel.state.value.jsonPreview != null }

        viewModel.onOutputPasswordChange("out-pw-12345")
        viewModel.onOutputPasswordConfirmChange("out-pw-12345")

        viewModel.events.test {
            viewModel.convertJsonToKdbx()
            val event = awaitItem()
            assertThat(event).isInstanceOf(ConversionViewModel.Event.SaveKdbxFile::class.java)
            event as ConversionViewModel.Event.SaveKdbxFile
            // 产物必须原样到达 UI（字节被谁动过都算事故）。
            assertThat(event.bytes).isEqualTo(bytes)
            assertThat(event.itemCount).isEqualTo(3)
            // 文件名自带 .kdbx：KDBX 无注册 MIME，SAF 不会替我们补后缀。
            assertThat(event.fileName.endsWith(".kdbx")).isTrue()
            cancelAndIgnoreRemainingEvents()
        }

        // 输出密码即弃（两个框都清）。
        assertThat(viewModel.state.value.outputPassword).isEmpty()
        assertThat(viewModel.state.value.outputPasswordConfirm).isEmpty()
    }

    @Test
    fun `输出密码太短或两次不一致时转换按钮不可用`() = runTest {
        val viewModel = conversionViewModel(preview = samplePreview())
        viewModel.onJsonFilePicked("vault.json")
        viewModel.onJsonPasswordChange("file-pw-1234")
        viewModel.previewJsonImport("{}")
        awaitUntil { viewModel.state.value.jsonPreview != null }

        // 没填输出密码。
        assertThat(viewModel.state.value.canConvertToKdbx).isFalse()

        // 太短（< 8）。
        viewModel.onOutputPasswordChange("short")
        viewModel.onOutputPasswordConfirmChange("short")
        assertThat(viewModel.state.value.canConvertToKdbx).isFalse()

        // 两次不一致。
        viewModel.onOutputPasswordChange("long-enough-1")
        viewModel.onOutputPasswordConfirmChange("long-enough-2")
        assertThat(viewModel.state.value.canConvertToKdbx).isFalse()

        // 齐了。
        viewModel.onOutputPasswordConfirmChange("long-enough-1")
        assertThat(viewModel.state.value.canConvertToKdbx).isTrue()
    }

    @Test
    fun `转换失败_写自检不过单独归因`() = runTest {
        val viewModel = conversionViewModel(
            preview = samplePreview(),
            convertThrows = VaultConversionException.WriteCheckFailed("条目数不符"),
        )
        viewModel.onJsonFilePicked("vault.json")
        viewModel.onJsonPasswordChange("file-pw-1234")
        viewModel.previewJsonImport("{}")
        awaitUntil { viewModel.state.value.jsonPreview != null }
        viewModel.onOutputPasswordChange("out-pw-12345")
        viewModel.onOutputPasswordConfirmChange("out-pw-12345")

        viewModel.convertJsonToKdbx()
        awaitUntil { viewModel.state.value.error != null }

        assertThat(viewModel.state.value.error)
            .isEqualTo(ConversionViewModel.ErrorKind.WRITE_CHECK_FAILED)
    }

    // ---- 转换（KDBX → JSON）----

    @Test
    fun `转换JSON成功_两套密码即弃且发出保存事件`() = runTest {
        val jsonText = "{\"encrypted\":true}"
        val viewModel = conversionViewModel(
            jsonOutput = ConversionOutput(
                jsonText.toByteArray(Charsets.UTF_8),
                ConversionStats(2, 0, 0, 0, 0),
            ),
        )
        viewModel.onKdbxFilePicked("my.kdbx")
        viewModel.onKdbxPasswordChange("kdbx-pw-1234")
        viewModel.onExportPasswordChange("export-pw-12345")
        viewModel.onExportPasswordConfirmChange("export-pw-12345")

        viewModel.events.test {
            viewModel.convertKdbxToJson(byteArrayOf(9, 9))
            val event = awaitItem()
            assertThat(event).isInstanceOf(ConversionViewModel.Event.SaveJsonFile::class.java)
            event as ConversionViewModel.Event.SaveJsonFile
            assertThat(event.content).isEqualTo(jsonText)
            assertThat(event.itemCount).isEqualTo(2)
            assertThat(event.fileName.endsWith(".json")).isTrue()
            cancelAndIgnoreRemainingEvents()
        }

        // 两套密码都即弃。
        assertThat(viewModel.state.value.kdbxPassword).isEmpty()
        assertThat(viewModel.state.value.exportPassword).isEmpty()
        assertThat(viewModel.state.value.exportPasswordConfirm).isEmpty()
    }

    @Test
    fun `转换JSON失败_密码错与非KDBX各有归因`() = runTest {
        val wrongPw = conversionViewModel(
            kdbxConvertThrows = VaultConversionException.WrongPassword(),
        )
        wrongPw.onKdbxFilePicked("my.kdbx")
        wrongPw.onKdbxPasswordChange("bad")
        wrongPw.onExportPasswordChange("export-pw-12345")
        wrongPw.onExportPasswordConfirmChange("export-pw-12345")
        wrongPw.convertKdbxToJson(byteArrayOf(1))
        awaitUntil { wrongPw.state.value.error != null }
        assertThat(wrongPw.state.value.error)
            .isEqualTo(ConversionViewModel.ErrorKind.WRONG_PASSWORD)

        val notKdbx = conversionViewModel(kdbxConvertThrows = VaultConversionException.NotKdbxFile())
        notKdbx.onKdbxFilePicked("photo.jpg")
        notKdbx.onKdbxPasswordChange("whatever-1")
        notKdbx.onExportPasswordChange("export-pw-12345")
        notKdbx.onExportPasswordConfirmChange("export-pw-12345")
        notKdbx.convertKdbxToJson(byteArrayOf(1))
        awaitUntil { notKdbx.state.value.error != null }
        assertThat(notKdbx.state.value.error)
            .isEqualTo(ConversionViewModel.ErrorKind.NOT_KDBX)
    }

    @Test
    fun `未选文件或导出密码不齐时转换按钮不可用`() {
        val viewModel = ConversionViewModel(mockk())

        assertThat(viewModel.state.value.canConvertToJson).isFalse()

        viewModel.onKdbxFilePicked("my.kdbx")
        assertThat(viewModel.state.value.canConvertToJson).isFalse() // 库密码空

        viewModel.onKdbxPasswordChange("kdbx-pw-1234")
        assertThat(viewModel.state.value.canConvertToJson).isFalse() // 导出密码空

        viewModel.onExportPasswordChange("export-pw-12345")
        viewModel.onExportPasswordConfirmChange("export-pw-12345")
        assertThat(viewModel.state.value.canConvertToJson).isTrue()
    }

    // ---- 换文件 ----

    @Test
    fun `换文件即清预览与旧密码`() = runTest {
        val viewModel = conversionViewModel(preview = samplePreview())
        viewModel.onJsonFilePicked("vault.json")
        viewModel.onJsonPasswordChange("file-pw-1234")
        viewModel.previewJsonImport("{}")
        awaitUntil { viewModel.state.value.jsonPreview != null }

        viewModel.onJsonFilePicked("another.json")

        val state = viewModel.state.value
        assertThat(state.jsonPreview).isNull()
        assertThat(state.jsonPassword).isEmpty()
        assertThat(state.jsonFileName).isEqualTo("another.json")
    }

    @Test
    fun `默认库名非空_不用用户从零想名字`() {
        val viewModel = ConversionViewModel(mockk())
        assertThat(viewModel.state.value.outputName).isNotEmpty()
    }

    // ---- helpers ----

    /**
     * 造一个接了 mockk 仓储的 ViewModel。
     *
     * 三个方向（预览 / 转 KDBX / 转 JSON）各自可配「返回什么 / 抛什么」：
     * 失败用例只给对应方向的抛错，其余方向保持可用，避免一个 fake 的行为
     * 依赖另一个方向的配置（那样加用例时极易配错而不自知）。
     */
    private fun conversionViewModel(
        preview: JsonImportPreview? = null,
        output: ConversionOutput? = null,
        jsonOutput: ConversionOutput? = null,
        previewThrows: VaultConversionException? = null,
        convertThrows: VaultConversionException? = null,
        kdbxConvertThrows: VaultConversionException? = null,
    ): ConversionViewModel {
        val repository = mockk<VaultConverterRepository>()
        coEvery { repository.previewJsonImport(any(), any()) } returns (preview ?: samplePreview())
        previewThrows?.let { coEvery { repository.previewJsonImport(any(), any()) } throws it }
        coEvery { repository.convertJsonToKdbx(any(), any(), any()) } returns
            (output ?: ConversionOutput(byteArrayOf(1), ConversionStats(1, 0, 0, 0, 0)))
        convertThrows?.let { coEvery { repository.convertJsonToKdbx(any(), any(), any()) } throws it }
        coEvery { repository.convertKdbxToJson(any(), any(), any()) } returns
            (jsonOutput ?: ConversionOutput(byteArrayOf(2), ConversionStats(1, 0, 0, 0, 0)))
        kdbxConvertThrows?.let { coEvery { repository.convertKdbxToJson(any(), any(), any()) } throws it }
        return ConversionViewModel(repository)
    }

    private fun samplePreview(): JsonImportPreview = JsonImportPreview(
        folders = listOf(VaultFolder(id = UUID.randomUUID().toString(), name = "工作")),
        items = listOf(
            VaultItem(
                id = UUID.randomUUID().toString(),
                title = "GitHub",
                username = "alice",
                type = VaultItemType.Login,
            ),
        ),
        stats = ConversionStats(
            itemCount = 3,
            totpCount = 1,
            passkeyCount = 2,
            skippedCount = 1,
            typeDegradedCount = 1,
        ),
    )

    /**
     * 轮询等状态落地。
     *
     * ⚠️ 必须切到 `Dispatchers.Default` 再 `withTimeout`：ViewModel 内部
     * `withContext(Dispatchers.Default)` 跑的是**真实线程**，而 runTest 的
     * 虚拟时间不为真实线程停留 —— 在虚拟作用域里 withTimeout 会在真实工作
     * 完成前就把虚拟时钟烧穿（TimeoutCancellationException）。
     * ⚠️ 也不要图省事用 `Dispatchers.Default.limitedParallelism(1)`：实测
     * 它与 UnconfinedTestDispatcher 混用会把等待协程饿死（真实 10s 超时），
     * 而裸 Default 一次轮询即可等到（同文件 debug 实验坐实过）。
     *
     * 有事件可等的场景优先用 turbine 的 awaitItem（天然兼容真实异步）。
     */
    private suspend fun TestScope.awaitUntil(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) {
                while (!condition()) delay(20)
            }
        }
    }
}
