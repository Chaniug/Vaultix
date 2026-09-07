package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.Log
import com.bastion.app.data.ItemType
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.model.TotpData
import com.bastion.app.security.SecurityManager

import com.bastion.app.util.TotpDataResolver
import com.bastion.app.util.TotpGenerator
import com.bastion.app.autofill_ng.service.AutofillOtpNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 统一 OTP 自动复制 / 通知副作用。
 *
 * 历史回归根因：OTP 自动复制仅挂在 [AutofillPickerActivityV2]（仅非认证 / 手动填充路径才会
 * 进入），而真实场景中 vault 锁定时填充走的是认证 dataset —— 框架会直接启动
 * [AutofillCipherCallbackActivity]，由它 `completeCipherAutofill()` 构建并返回 dataset 完成
 * 填充，根本不会经过 Picker Activity，于是 OTP 复制副作用从未触发。
 *
 * 这里把 OTP 解析 + 复制逻辑抽成共享函数，由两条填充路径共同调用，避免再次只挂在某一条上。
 *
 * 所有关键节点均使用统一 tag [TAG] 输出日志，便于下次用户日志定位链路走到哪一步。
 */
private const val TAG = "BastionOtpCopy"

/**
 * 密码填充完成后执行 OTP 自动复制 / 通知。
 *
 * @param context 建议传入 ApplicationContext（剪贴板写入与 DataStore 读取都不依赖 Activity）。
 * @param password 被填充的密码条目（可能绑定 TOTP 或 Steam Guard）。
 * @param autofillHints 本次填充请求的 hint 列表；若本次本身就是 OTP 字段则跳过。
 */
suspend fun performOtpAutofillSideEffects(
    context: Context,
    password: PasswordEntry,
    autofillHints: List<String>?,
) {
    val isOtpTarget = autofillHints
        ?.map { it.trim().lowercase() }
        ?.any(::isOtpHint) == true
    if (isOtpTarget) {
        Log.d(TAG, "skip: fill request is itself an OTP target, passwordId=${password.id}")
        return
    }

    runCatchingObserved {
        val preferences = AutofillPreferences(context)
        val showNotification = withContext(Dispatchers.IO) {
            preferences.isOtpNotificationEnabled.first()
        }
        val autoCopy = withContext(Dispatchers.IO) {
            preferences.isAutoCopyOtpEnabled.first()
        }
        Log.d(
            TAG,
            "prefs: autoCopy=$autoCopy, showNotification=$showNotification, passwordId=${password.id}, title=${password.title}"
        )
        if (!showNotification && !autoCopy) {
            Log.d(TAG, "skip: both autoCopy and showNotification disabled, passwordId=${password.id}")
            return
        }

        // 密码库本身已原生支持 Steam TOTP（OtpType.STEAM / TotpGenerator.generateSteamCode），
        // 不需要再查独立 Steam 模块。

        val totpData = resolveOtpDataForPassword(context, password)
        if (totpData == null) {
            Log.w(TAG, "no TOTP resolved (no authenticator key and no bound validator), passwordId=${password.id}")
            return
        }
        Log.d(
            TAG,
            "resolved TOTP: otpType=${totpData.otpType}, secretLen=${totpData.secret.length}, " +
                "boundPasswordId=${totpData.boundPasswordId}, passwordId=${password.id}"
        )
        val resolvedTotpData = resolveTotpDataForGeneration(context, totpData)
        val code = TotpGenerator.generateOtp(resolvedTotpData)
        Log.d(TAG, "generated OTP (len=${code.length}), passwordId=${password.id}")
        if (autoCopy) {
            writeClipboard(context, code)
            Log.i(TAG, "copied OTP to clipboard (len=${code.length}), passwordId=${password.id}")
        }
        if (showNotification) {
            val durationSeconds = withContext(Dispatchers.IO) {
                preferences.otpNotificationDuration.first()
            }
            AutofillOtpNotificationService.start(
                context = context.applicationContext,
                totpData = resolvedTotpData,
                label = password.title,
                durationSeconds = durationSeconds
            )
            Log.i(TAG, "started OTP notification, passwordId=${password.id}")
        }
    }.onFailure { e ->
        Log.e(TAG, "OTP side-effect failed, passwordId=${password.id}", e)
    }
}

/** 供填充 OTP 字段时直接生成验证码（不复制）。 */
suspend fun generateOtpCodeForPassword(context: Context, password: PasswordEntry): String? {
    val totpData = resolveOtpDataForPassword(context, password)
    if (totpData == null) {
        Log.w(TAG, "generateOtpCodeForPassword: no TOTP resolved, passwordId=${password.id}")
        return null
    }
    return runCatchingObserved {
        val resolvedTotpData = resolveTotpDataForGeneration(context, totpData)
        val code = TotpGenerator.generateOtp(resolvedTotpData)
        Log.d(TAG, "generated OTP for fill (len=${code.length}), passwordId=${password.id}")
        code.takeIf { it.isNotBlank() }
    }.onFailure { e ->
        Log.e(TAG, "generateOtpCodeForPassword failed, passwordId=${password.id}", e)
    }.getOrNull()
}

fun isOtpHint(normalizedHint: String): Boolean {
    if (normalizedHint.isBlank()) return false
    return normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.OTP_CODE.name.lowercase() ||
        normalizedHint.contains("totp") ||
        normalizedHint.contains("otp") ||
        normalizedHint.contains("2fa") ||
        normalizedHint.contains("twofactor") ||
        normalizedHint.contains("two_factor") ||
        normalizedHint.contains("verification") ||
        normalizedHint.contains("验证码") ||
        normalizedHint.contains("驗證碼") ||
        normalizedHint.contains("一次性")
}

private suspend fun resolveOtpDataForPassword(context: Context, password: PasswordEntry): TotpData? {
    val passwordTotpData = password.authenticatorKey
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let { parsePasswordAuthenticatorTotpData(context, it) }
    return resolveOtpFromExistingValidators(context, password, passwordTotpData) ?: passwordTotpData
}

private suspend fun resolveOtpFromExistingValidators(
    context: Context,
    password: PasswordEntry,
    passwordTotpData: TotpData?,
): TotpData? {
    val result = withContext(Dispatchers.IO) {
        val securityManager = SecurityManager(context)
        val dao = PasswordDatabase.getDatabase(context).secureItemDao()
        val items = dao.getActiveItemsByTypeSync(ItemType.TOTP)
        if (items.isEmpty()) return@withContext null

        // 按需解密 + 早退：先按 boundPasswordId 匹配，命中即返回，不再解密剩余条目。
        // 原实现用 mapNotNull 先全量解密所有 TOTP 条目再匹配，大 vault（大量 TOTP 校验器）
        // 下每次密码填充都做 O(N) 次 AES-GCM 解密；改为逐条解密、命中即退，
        // 常见情形从 O(N) 降到约 O(1) 次解密。匹配优先级（先 boundPasswordId 后 identityKey）
        // 与原逻辑完全一致，行为零变化。
        val parsed = mutableListOf<TotpData>()
        for (item in items) {
            val totp = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
            ) ?: continue
            if (totp.boundPasswordId == password.id) return@withContext totp
            parsed.add(totp)
        }

        val identityKey = buildTotpIdentityKey(passwordTotpData)
        if (identityKey.isNotEmpty()) {
            parsed.firstOrNull { buildTotpIdentityKey(it) == identityKey }
        } else {
            null
        }
    }
    return result
}

private fun buildTotpIdentityKey(data: TotpData?): String {
    val normalized = data?.let { TotpDataResolver.normalizeTotpData(it) } ?: return ""
    val normalizedSecret = TotpDataResolver.normalizeBase32Secret(normalized.secret)
    return listOf(
        normalized.otpType.name,
        normalizedSecret,
        normalized.digits.toString(),
        normalized.period.toString(),
        normalized.algorithm.uppercase(),
        normalized.counter.toString()
    ).joinToString("|")
}

private fun resolveTotpDataForGeneration(context: Context, totpData: TotpData): TotpData {
    val securityManager = SecurityManager(context)
    val decryptResult = runCatchingObserved { securityManager.decryptData(totpData.secret) }
    val decryptedSecret = decryptResult.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    Log.d(
        TAG,
        "OTP secret resolve: otpType=${totpData.otpType}, rawLen=${totpData.secret.length}, " +
            "decryptSuccess=${decryptResult.isSuccess && !decryptedSecret.isNullOrEmpty()}, " +
            "resolvedLen=${decryptedSecret?.length ?: totpData.secret.length}"
    )
    return if (!decryptedSecret.isNullOrEmpty()) {
        totpData.copy(secret = decryptedSecret)
    } else {
        totpData
    }
}

private fun parsePasswordAuthenticatorTotpData(context: Context, authenticatorKey: String): TotpData? {
    val securityManager = SecurityManager(context)
    return TotpDataResolver.fromAuthenticatorKey(
        rawKey = runCatchingObserved {
            securityManager.decryptDataIfBastionCiphertext(authenticatorKey)
        }.getOrDefault(authenticatorKey)
    )
}

private suspend fun writeClipboard(context: Context, code: String) {
    val appContext = context.applicationContext
    withContext(Dispatchers.Main) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "clipboard service unavailable, cannot copy OTP")
            return@withContext
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
    }
}
