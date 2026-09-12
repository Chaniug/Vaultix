package io.vaultix.data.repository

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * 快速解锁失败分类（Bastion 不变量移植）。
 *
 * 守卫的不变量：**「不可恢复的失败」必须被识别出来**，因为漏判会导致
 * 开关停留在 `enabled = true`、payload 残留 → 设置页显示「已启用」但每次点
 * 指纹都失败，且无法自愈。
 *
 * 覆盖三层：
 * 1. 三类失效异常**直接**抛出 → 必须识别；
 * 2. 被 `ProviderException` / `UnrecoverableKeyException` **包装**后抛出
 *    → 必须穿透异常链识别（这是最容易漏判的一层）；
 * 3. 无关异常（网络、NPE 等）→ **不得**误判，否则会把可用状态错误清掉。
 */
class LocalUnlockFailureTest {

    @Test
    fun keyPermanentlyInvalidated_isUnrecoverable() {
        // 用户新增/删除指纹后 KEK 被永久失效 —— 最常见的真实场景
        val error = KeyPermanentlyInvalidatedException()

        assertTrue(error.isLocalUnlockUnrecoverable())
    }

    @Test
    fun unrecoverableKey_isUnrecoverable() {
        assertTrue(UnrecoverableKeyException("stale handle").isLocalUnlockUnrecoverable())
    }

    /**
     * ⚠️ **回归锁（2026-09-12 行为反转）**：`UserNotAuthenticatedException`
     * 的语义是「**本次**没有拿到认证」，**不是**「密钥已废」——
     * `Docs/03-密码学与密钥管理.md` 对它的要求是「拉起 BiometricPrompt 重新认证」。
     *
     * 旧实现把它列入「不可恢复」，于是一次瞬时失败（重启后生物识别 HAL 未就绪、
     * 认证会话尚未建立…）就会触发调用方 `clearBrokenLocalUnlock`，
     * **把用户的快速解锁注册真删掉**——正是「覆盖安装/重启后指纹解锁被清除」的成因之一。
     */
    @Test
    fun userNotAuthenticated_isRecoverable() {
        assertFalse(
            "UserNotAuthenticatedException 只是本次未认证，重试即可，不得据此清掉用户注册",
            UserNotAuthenticatedException().isLocalUnlockUnrecoverable(),
        )
    }

    @Test
    fun aeadBadTag_isUnrecoverable() {
        // payload 与当前 KEK 不匹配 → 密文校验失败
        assertTrue(AEADBadTagException().isLocalUnlockUnrecoverable())
    }

    @Test
    fun wrappedInProviderException_isDetectedThroughCauseChain() {
        // ★ 关键用例：Keystore 常把失效异常裹进 ProviderException 再抛出。
        // 若只比对顶层类型会漏判 → 坏状态残留 → 静默死循环。
        val error = ProviderException(
            "Failed to init cipher",
            KeyPermanentlyInvalidatedException(),
        )

        assertTrue(
            "必须穿透 ProviderException 识别内层的 KeyPermanentlyInvalidatedException",
            error.isLocalUnlockUnrecoverable(),
        )
    }

    @Test
    fun deeplyWrapped_isDetectedAcrossMultipleLevels() {
        // 多层包装：ProviderException → UnrecoverableKeyException → KeyPermanentlyInvalidatedException
        val error = ProviderException(
            "outer",
            UnrecoverableKeyException("middle").apply {
                initCause(KeyPermanentlyInvalidatedException())
            },
        )

        assertTrue("多层包装也必须能穿透到底", error.isLocalUnlockUnrecoverable())
    }

    @Test
    fun unrelatedException_isNotUnrecoverable() {
        // 普通失败（如网络抖动）不应被误判 → 否则会错误清掉用户的可用配置
        assertFalse(IllegalStateException("boom").isLocalUnlockUnrecoverable())
        assertFalse(NullPointerException().isLocalUnlockUnrecoverable())
        assertFalse(RuntimeException("generic").isLocalUnlockUnrecoverable())
    }

    @Test
    fun wrappingUnrelatedException_isNotUnrecoverable() {
        // 包装层无关时同样不得误判
        val error = ProviderException("outer", IllegalStateException("inner"))

        assertFalse(error.isLocalUnlockUnrecoverable())
    }

    @Test
    fun causeChainDoesNotLoopForever() {
        // 防御性：极深的异常链不应导致无限循环（深度上限 8）
        var error: Throwable = IllegalStateException("leaf")
        repeat(50) { error = RuntimeException("level-$it", error) }

        assertFalse(
            "超长无关异常链应快速返回 false，而非挂死",
            error.isLocalUnlockUnrecoverable(),
        )
    }
}
