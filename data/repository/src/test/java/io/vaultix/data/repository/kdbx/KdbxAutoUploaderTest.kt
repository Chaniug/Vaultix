package io.vaultix.data.repository.kdbx

import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.model.KdbxCloudSyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `KdbxAutoUploader` 的调度纪律（施工单 S1 + S6 的验收项）。
 *
 * ## 为什么这几条值得单测
 *
 * "保存后自动上传"听起来是一行 `launch { sync() }`，但它有四个**看不见**
 * 却会咬人的性质：该不该触发（本地库不该）、会不会交叠（同一库要串行）、
 * 失败后会不会空转（不该重试）、以及不同库会不会被同一把锁堵住。
 * 这四条在真机上都要"碰巧遇到"才看得出来（离线 / 手快连点两下 / 改完立刻再改），
 * 单测把它们钉死在这里。
 *
 * ## ⚠️ 弱断言与反证（本项目付过代价的纪律）
 *
 * 「本地库不触发」与「同一库串行」都是**断言"没发生"** ——
 * 它们可能因为"整条路径根本没跑"而通过（mock 配错了、参数没匹配上，
 * 于是 `sync` 一次都没被调用，断言照样绿）。
 * ⇒ 每一条都配了它的**反证**：
 * - 「本地库不触发」的反证 = `网盘库_保存后确实触发了上传`（同一套装置下必须调到 1 次）；
 * - 「同一库串行」的反证 = `不同库_互不阻塞`（同一套装置必须观测到并发 = 2）。
 * 少了反证，这两条断言在装置失效时会**静默变绿**。
 */
class KdbxAutoUploaderTest {

    @Test
    fun `网盘库_保存后确实触发了上传`() = runTest {
        // 上传成功后状态回到 IN_SYNC（真实编排器就是这么记的）⇒ 只跑一轮就收敛。
        val status = statusOf(KdbxCloudSyncStatus.PENDING_UPLOAD)
        val dao = daoFor(CLOUD_ORIGIN, status)
        val coordinator = mockk<KdbxCloudSyncCoordinator> {
            every { hasCloudSource(CLOUD_ORIGIN) } returns true
            coEvery { sync(VAULT_ID, true) } coAnswers {
                status.set(KdbxCloudSyncStatus.IN_SYNC.name)
                KdbxSyncResult.Uploaded("v2")
            }
        }

        KdbxAutoUploader(dao, coordinator).enqueue(VAULT_ID).join()

        coVerify(exactly = 1) { coordinator.sync(VAULT_ID, true) }
    }

    @Test
    fun `本地SAF库_不触发上传`() = runTest {
        val status = statusOf(KdbxCloudSyncStatus.PENDING_UPLOAD)
        val dao = daoFor(SAF_ORIGIN, status)
        val coordinator = mockk<KdbxCloudSyncCoordinator> {
            every { hasCloudSource(SAF_ORIGIN) } returns false
            coEvery { sync(any(), any()) } returns KdbxSyncResult.Uploaded("v2")
        }

        KdbxAutoUploader(dao, coordinator).enqueue(VAULT_ID).join()

        // 「本地没有云端」⇒ 一次都不该调（调了就是给一个不存在远端的库假装同步）。
        coVerify(exactly = 0) { coordinator.sync(any(), any()) }
        // 顺带证明状态没被自动上传动过：本地库不该出现「待上传」这种状态迁移。
        assertEquals(KdbxCloudSyncStatus.PENDING_UPLOAD.name, status.get())
    }

    @Test
    fun `上传失败_不重试`() = runTest {
        val status = statusOf(KdbxCloudSyncStatus.PENDING_UPLOAD)
        val dao = daoFor(CLOUD_ORIGIN, status)
        val coordinator = mockk<KdbxCloudSyncCoordinator> {
            every { hasCloudSource(CLOUD_ORIGIN) } returns true
            // 离线：每一轮都失败，且状态**保持** PENDING ⇒ 若实现会重试，就会跑满 MAX_ROUNDS 次。
            coEvery { sync(VAULT_ID, true) } returns KdbxSyncResult.Failed("离线")
        }

        KdbxAutoUploader(dao, coordinator).enqueue(VAULT_ID).join()

        // ⚠️ 期望 1 次而不是 3 次：离线时重试没有意义（只会白烧电量与请求配额），
        //    状态留在 FAILED 上、用户看得见就够了（详见 KdbxAutoUploader 要点 4）。
        coVerify(exactly = 1) { coordinator.sync(VAULT_ID, true) }
    }

    @Test
    fun `同一库_两次上传串行`() = runTest {
        val status = statusOf(KdbxCloudSyncStatus.PENDING_UPLOAD)
        val dao = daoFor(CLOUD_ORIGIN, status)
        val running = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val coordinator = mockk<KdbxCloudSyncCoordinator> {
            every { hasCloudSource(CLOUD_ORIGIN) } returns true
            // ⚠️ 刻意**不改状态**：让它跑满 MAX_ROUNDS，制造最长的交叠窗口。
            coEvery { sync(VAULT_ID, true) } coAnswers {
                val now = running.incrementAndGet()
                maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(SYNC_DURATION_MS)
                running.decrementAndGet()
                KdbxSyncResult.Uploaded("v")
            }
        }
        val uploader = KdbxAutoUploader(dao, coordinator)

        val a = launch { uploader.drain(VAULT_ID) }
        val b = launch { uploader.drain(VAULT_ID) }
        a.join()
        b.join()

        // 同一个库 ⇒ 任何时刻最多只有一次上传在飞（否则第二次会拿着过期令牌条件写）。
        assertEquals(1, maxConcurrent.get())
    }

    /**
     * 「同一库串行」的**反证**：证明上面那套装置**能观测到并发**。
     *
     * ⚠️ 少了这条，`同一库_两次上传串行` 的 `maxConcurrent == 1` 可能只是
     * "装置压根没制造出交叠"（比如 mock 的 delay 没生效、两次 drain 事实上顺序执行）。
     * 不同库**不受同一把锁约束** ⇒ 这里必须看到 2，否则说明装置本身是坏的。
     */
    @Test
    fun `不同库_互不阻塞`() = runTest {
        val status = statusOf(KdbxCloudSyncStatus.PENDING_UPLOAD)
        val dao = mockk<VaultDao> {
            coEvery { get(any()) } coAnswers { row(CLOUD_ORIGIN, status.get(), firstArg()) }
        }
        val running = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val coordinator = mockk<KdbxCloudSyncCoordinator> {
            every { hasCloudSource(CLOUD_ORIGIN) } returns true
            coEvery { sync(any(), true) } coAnswers {
                val now = running.incrementAndGet()
                maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(SYNC_DURATION_MS)
                running.decrementAndGet()
                KdbxSyncResult.Uploaded("v")
            }
        }
        val uploader = KdbxAutoUploader(dao, coordinator)

        val a = launch { uploader.drain("vault-a") }
        val b = launch { uploader.drain("vault-b") }
        a.join()
        b.join()

        assertEquals(2, maxConcurrent.get())
    }

    // ---- 装置 ----

    /** 会变的 `syncStatus`（模拟编排器写回状态）。 */
    private fun statusOf(initial: KdbxCloudSyncStatus): AtomicReference<String?> =
        AtomicReference(initial.name)

    private fun daoFor(origin: String, status: AtomicReference<String?>): VaultDao = mockk {
        coEvery { get(VAULT_ID) } coAnswers { row(origin, status.get(), VAULT_ID) }
    }

    private fun row(origin: String, status: String?, id: String): VaultEntity = VaultEntity(
        id = id,
        kind = "KDBX",
        displayName = "库 $id",
        origin = origin,
        createdAt = 0L,
        syncStatus = status,
    )

    private companion object {
        const val VAULT_ID = "vault-1"
        const val CLOUD_ORIGIN = "onedrive:acc:/vault.kdbx"
        const val SAF_ORIGIN = "content://com.android.providers.downloads.documents/document/1"
        const val SYNC_DURATION_MS = 50L
    }
}
