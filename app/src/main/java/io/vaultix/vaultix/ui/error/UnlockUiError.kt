package io.vaultix.vaultix.ui.error

import io.vaultix.domain.UnlockResult

/**
 * 解锁 / 添加库失败的 UI 错误分类（由 domain [UnlockResult] 映射而来，
 * 与数据层解耦：domain 不懂 UI 文案，这里负责翻译）。
 *
 * 注意：[UnlockResult.TwoFactorRequired] 是**流程步骤**（转入验证码输入页），
 * 不是错误，不映射到这里，由 ViewModel 单独处理。
 */
sealed interface UnlockUiError {
    data object FieldsMissing : UnlockUiError
    data object InvalidServer : UnlockUiError
    data object InvalidCredentials : UnlockUiError
    data object AccountNotFound : UnlockUiError

    /** 验证码错误 / 已过期（发生在 2FA 步骤内）。 */
    data object TwoFactorInvalid : UnlockUiError
    data object Network : UnlockUiError
    data object KeyUnavailable : UnlockUiError
    data object VaultMissing : UnlockUiError
    data class Unknown(val detail: String?) : UnlockUiError

    /**
     * **本地表单校验**失败（不外发、不涉及凭据是否正确的判断）。
     *
     * ## 为什么要单独一个类型，而不是用 [Detail] 塞一句中文
     *
     * [Detail] 的契约是"**已经翻译好**的一句话，来自数据层 / 来源层"——
     * 它绕过资源系统（`unlockErrorText` 原样返回），因此**不可翻译、也不进 strings.xml**。
     * 用它装 ViewModel 侧的校验文案，会产生两个后果：
     * ① 文案散在 Kotlin 代码里，与 `strings.xml` 里那批同类文案**分家**，
     *    将来改措辞要记得找两个地方；② 那种字符串在孤儿串检查里是**隐形的**
     *    （它不在 strings.xml，检查器看不见）—— 于是"文案该不该翻译"这件事
     *    在评审时也没有任何提示。
     *
     * ⇒ 校验类错误一律走这里，由 `ErrorText.kt` 做 `R.string` 映射。
     * 分辨标准很简单：**这句话是不是我们（UI 层）自己写的**？是 ⇒ 用本类型。
     */
    enum class Validation : UnlockUiError {
        /** 新建库：库名为空。 */
        VaultNameEmpty,

        /** 新建库：主密码为空。 */
        PasswordEmpty,

        /** 新建库：两次输入的主密码不一致。 */
        PasswordMismatch,

        /** 打开库：还没选文件。 */
        FileNotPicked,

        /**
         * 新建库：拿不到目标位置的持久读写授权。
         *
         * ⚠️ 与"用户取消面板"**不是一回事**：取消是正常操作，不该报错；
         * 而这个意味着库即使建好了也活不过一次重启 —— 必须在**建之前**就拦住。
         */
        SaveLocationUnavailable,
    }

    /**
     * **已经是一句人话**的消息，原样展示（不加"出错了："这类前缀）。
     *
     * ## 与 [Unknown] 的分工（别混）
     *
     * [Unknown] 的语义是"我们也不知道这是什么错"，所以那句话需要前缀交代
     * "这不太正常"。而本类型的消息来自**已经翻译过的来源** —— 例如
     * `WebDavKdbxFileSource` 给的是"WebDAV 账号或密码不正确（HTTP 401）"、
     * `OneDriveAuthManager` 给的是"请关闭系统电池优化…"。
     * 那些句子本身已经完整，再套一层前缀只会把它切碎成
     * 「出错了：WebDAV 账号或密码不正确（HTTP 401）」。
     *
     * ⚠️ 所以在**网络/来源类**失败上一律用它；[Unknown] 留给真正的兜底。
     * ⚠️ 但**不要**用它装我们自己的表单校验文案 —— 见 [Validation] 的说明。
     */
    data class Detail(val message: String) : UnlockUiError
}

fun UnlockResult.toUnlockUiError(): UnlockUiError = when (this) {
    UnlockResult.Success -> error("Success 无需映射为 UI 错误；调用方应先处理 Success 分支")
    UnlockResult.InvalidCredentials -> UnlockUiError.InvalidCredentials
    UnlockResult.AccountNotFound -> UnlockUiError.AccountNotFound
    is UnlockResult.TwoFactorRequired ->
        error("TwoFactorRequired 是流程步骤，应由 ViewModel 转入验证码步骤而非错误提示")
    UnlockResult.TwoFactorInvalid -> UnlockUiError.TwoFactorInvalid
    UnlockResult.Network -> UnlockUiError.Network
    UnlockResult.KeyUnavailable -> UnlockUiError.KeyUnavailable
    UnlockResult.VaultMissing -> UnlockUiError.VaultMissing
    is UnlockResult.Unknown -> UnlockUiError.Unknown(detail)
}
