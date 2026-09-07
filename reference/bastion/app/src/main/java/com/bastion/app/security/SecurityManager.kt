package com.bastion.app.security

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bastion.app.data.PredefinedSecurityQuestions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security manager for encryption and master password handling
 */
class SecurityManager(private val context: Context) {

    enum class VaultAccessState {
        ACCESSIBLE,
        REQUIRES_PASSWORD_REENTRY,
        LOCKED
    }

    private enum class WrapperMode {
        AUTH,
        COMPAT
    }

    private data class WrappedMdkBlob(
        val mode: WrapperMode,
        val payload: String
    )

    private val logTag = "SecurityManager"

    /**
     * 解锁 / 冷启动耗时剖析的统一 tag。
     *
     * 真机上一行拿到全部分段耗时：
     * `adb logcat | grep BASTION-PROFILE`
     */
    private val profileTag = "BASTION-PROFILE"

    private fun profileElapsed(phase: String, startedAt: Long, extra: String = "") {
        android.util.Log.i(
            profileTag,
            "$phase costMs=${System.currentTimeMillis() - startedAt} " +
                "thread=${Thread.currentThread().name}" +
                if (extra.isNotEmpty()) " $extra" else ""
        )
    }

    @Volatile
    private var mdkAuthUnavailableUntilMillis: Long = 0L
    @Volatile
    private var hasLoggedMdkAuthExpiredWarning = false
    @Volatile
    private var hasLoggedMdkFallbackEncryption = false
    /** [requiresPasswordReentryForWrapperRebuild] 的一次性告警闸门（详见该方法内注释）。 */
    @Volatile
    private var hasLoggedReentryRequiredWarning = false

    private val mdkAuthCooldownMillis = 30_000L

    init {
        SecurityDiagLogger.initialize(context.applicationContext)
    }

    private fun logRoutineDebug(message: String) {
        SecurityDiagLogger.append("D/$logTag $message")
    }
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // Secure Data Key Alias and Prefix
    private val KEY_ALIAS_DATA = "bastion_data_key_v2"
    private val KEY_ALIAS_DATA_COMPAT = "bastion_data_key_v2_compat"
    // Legacy aliases from pre-rebrand (Monica Pass). Used as decryption fallback so that
    // data encrypted before the rebrand remains readable after the applicationId/key-alias
    // rename. Encryption always uses the new aliases; only decryption tries the legacy ones.
    private val LEGACY_KEY_ALIAS_DATA = "monica_data_key_v2"
    private val LEGACY_KEY_ALIAS_DATA_COMPAT = "monica_data_key_v2_compat"
    private val DATA_PREFIX_V2 = "V2|"
    private val DATA_PREFIX_COMPAT = "C2|"
    private val WRAPPER_PREFIX_AUTH = "AU|"
    private val WRAPPER_PREFIX_COMPAT = "CP|"
    
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "bastion_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * 主密码是否已设置的进程内缓存。
     *
     * isMasterPasswordSet() 底层是 EncryptedSharedPreferences.contains()，每次调用都
     * 读取加密文件并解密 key 前缀（主线程 IO + 密码学操作）。LoginScreen 顶层
     * isFirstTime 与 MainAppLockPolicy.resolveAccessState 在重组/设置变化时高频调用它，
     * 首次登录输入密码时 settings Flow 更新频繁 → 反复触发该 IO → 输入卡顿。
     *
     * 该状态只会在 setMasterPassword（true）与 clearSecurityData（false）时变化，
     * 用 @Volatile 缓存避免重复读盘；跨进程（autofill 独立进程各自 new 本类）
     * 缓存互不影响，且此状态在进程生命周期内几乎不变化，一致性问题可忽略。
     */
    @Volatile
    private var masterPasswordSetCached: Boolean? = null
    
    companion object {
        private const val MASTER_PASSWORD_HASH_KEY = "master_password_hash"
        private const val MASTER_PASSWORD_SALT_KEY = "master_password_salt"
        private const val BIOMETRIC_ENABLED_KEY = "biometric_enabled"
        private const val AUTO_LOCK_TIMEOUT_KEY = "auto_lock_timeout"
        private const val SECURITY_QUESTION_1_ID_KEY = "security_question_1_id"
        private const val SECURITY_QUESTION_1_ANSWER_KEY = "security_question_1_answer"
        private const val SECURITY_QUESTION_1_TEXT_KEY = "security_question_1_text"
        private const val SECURITY_QUESTION_2_ID_KEY = "security_question_2_id"
        private const val SECURITY_QUESTION_2_ANSWER_KEY = "security_question_2_answer"
        private const val SECURITY_QUESTION_2_TEXT_KEY = "security_question_2_text"
        private const val SECURITY_QUESTION_1_SALT_KEY = "security_question_1_salt"
        private const val SECURITY_QUESTION_2_SALT_KEY = "security_question_2_salt"
        // KDF 迭代次数：新参数按 OWASP 标准取 600k；存量数据无迭代键时按旧值 100k 解读，
        // 验证成功后由 upgradePbkdf2ParamsIfNeeded 透明升级到新参数。
        private const val PBKDF2_ITERATIONS = 600000
        private const val PBKDF2_ITERATIONS_LEGACY = 100000
        private const val MASTER_PASSWORD_ITERATIONS_KEY = "master_password_kdf_iterations"
        private const val MDK_PASSWORD_ITERATIONS_KEY = "mdk_password_kdf_iterations"
        private const val MDK_PASSWORD_BLOB_KEY = "mdk_password_blob"
        private const val MDK_PASSWORD_SALT_KEY = "mdk_password_salt"
        private const val MDK_KEYSTORE_BLOB_KEY = "mdk_keystore_blob"
        private const val MDK_READY_KEY = "mdk_ready"
        
        // V2 Bitwarden 凭据存储键
        private const val BITWARDEN_ACCESS_TOKEN_KEY = "bitwarden_access_token"
        private const val BITWARDEN_REFRESH_TOKEN_KEY = "bitwarden_refresh_token"
        private const val BITWARDEN_TOKEN_EXPIRY_KEY = "bitwarden_token_expiry"
        private const val BITWARDEN_USER_EMAIL_KEY = "bitwarden_user_email"
        private const val BITWARDEN_USER_ID_KEY = "bitwarden_user_id"
        private const val BITWARDEN_MASTER_KEY_HASH_KEY = "bitwarden_master_key_hash"
        private const val BITWARDEN_SYMMETRIC_KEY_KEY = "bitwarden_symmetric_key"
        private const val BITWARDEN_PRIVATE_KEY_KEY = "bitwarden_private_key"
        private const val BITWARDEN_SERVER_URL_KEY = "bitwarden_server_url"
        private const val BITWARDEN_CONNECTED_KEY = "bitwarden_connected"

        @Volatile
        private var processCachedMdk: ByteArray? = null
        @Volatile
        private var cachedCompatDataKey: SecretKey? = null

        @Volatile
        private var cachedDataKey: SecretKey? = null

        @Volatile
        private var cachedInstance: SecurityManager? = null

        private val compatDataKeyLock = Any()

        /**
         * 主数据密钥（KEY_ALIAS_DATA）的缓存锁。
         *
         * 与 [compatDataKeyLock] 分开：两者服务于不同别名、不同失效时机，
         * 共用一把锁会让「重建 compat key」阻塞「读主 key」，白白串行化。
         */
        private val dataKeyLock = Any()

        fun clearRuntimeUnlockCache() {
            processCachedMdk = null
        }

        /**
         * 进程级单例访问点。首次访问在调用线程构造 SecurityManager（含 Keystore 初始化）。
         * 建议在 Application 启动时通过 [prewarm] 于后台线程预热，以避免冷启动 / 配置变更时
         * 在主线程触发 Keystore 初始化开销。
         */
        @Synchronized
        fun instance(context: Context): SecurityManager {
            val appContext = context.applicationContext
            return cachedInstance ?: SecurityManager(appContext).also { cachedInstance = it }
        }

        /** 后台线程预热单例，仅用于提前完成 Keystore 初始化。 */
        fun prewarm(context: Context) {
            instance(context)
        }
    }
    
    /**
     * Hash the master password using PBKDF2
     */
    fun hashMasterPassword(
        password: String,
        salt: ByteArray? = null,
        iterations: Int = PBKDF2_ITERATIONS
    ): Pair<String, ByteArray> {
        val actualSalt = salt ?: generateSalt()
        val spec = PBEKeySpec(password.toCharArray(), actualSalt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Pair(hash.joinToString("") { "%02x".format(it) }, actualSalt)
    }

    /**
     * Generate a random salt
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun decodeHex(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun getStoredMasterPasswordIterations(): Int {
        return sharedPreferences.getInt(MASTER_PASSWORD_ITERATIONS_KEY, PBKDF2_ITERATIONS_LEGACY)
    }

    private fun getStoredMdkPasswordIterations(): Int {
        return sharedPreferences.getInt(MDK_PASSWORD_ITERATIONS_KEY, PBKDF2_ITERATIONS_LEGACY)
    }

    /**
     * 存量 KDF 参数（PBKDF2 100k）透明升级到当前参数（600k）。
     * 仅在主密码验证成功、MDK 已驻留内存缓存后调用；MDK 本身不变，
     * 仅以新盐+新迭代重新计算口令哈希并重新包装 MDK。
     */
    private fun upgradePbkdf2ParamsIfNeeded(password: String) {
        val needsHashUpgrade = getStoredMasterPasswordIterations() < PBKDF2_ITERATIONS
        val needsMdkUpgrade = getStoredMdkPasswordIterations() < PBKDF2_ITERATIONS
        if (!needsHashUpgrade && !needsMdkUpgrade) return
        try {
            if (needsHashUpgrade) {
                val (newHash, newSalt) = hashMasterPassword(password, null, PBKDF2_ITERATIONS)
                sharedPreferences.edit()
                    .putString(MASTER_PASSWORD_HASH_KEY, newHash)
                    .putString(MASTER_PASSWORD_SALT_KEY, newSalt.joinToString("") { "%02x".format(it) })
                    .putInt(MASTER_PASSWORD_ITERATIONS_KEY, PBKDF2_ITERATIONS)
                    .apply()
            }
            if (needsMdkUpgrade) {
                // forceUpdate 会重新生成 MDK 包装盐并按当前迭代派生 KEK，
                // MDK 取自 processCachedMdk（同一把密钥，仅重新包装）。
                ensureMdkInitializedWithPassword(password, forceUpdate = true)
            }
            SecurityDiagLogger.append(
                "I/$logTag upgradePbkdf2ParamsIfNeeded: hash=$needsHashUpgrade mdk=$needsMdkUpgrade"
            )
        } catch (e: Exception) {
            android.util.Log.w(logTag, "upgradePbkdf2ParamsIfNeeded failed: ${e.message}")
            SecurityDiagLogger.append("W/$logTag upgradePbkdf2ParamsIfNeeded failed: ${e.javaClass.simpleName}")
        }
    }
    
    /**
     * Verify the master password.
     */
    fun verifyMasterPassword(inputPassword: String): Boolean {
        android.util.Log.d("SecurityManager", "Performing normal password verification")
        val storedHash = sharedPreferences.getString(MASTER_PASSWORD_HASH_KEY, null) ?: return false
        val storedSalt = sharedPreferences.getString(MASTER_PASSWORD_SALT_KEY, null)?.let { saltStr ->
            decodeHex(saltStr)
        } ?: return false

        val (computedHash, _) = hashMasterPassword(inputPassword, storedSalt, getStoredMasterPasswordIterations())
        // 恒定时间比较（对齐 Keyguard / Bitwarden 的防时序侧信道做法），
        // 避免 hash 字符串逐位比较泄漏前缀匹配进度。
        val result = MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
        android.util.Log.d("SecurityManager", "Password verification result: $result")
        if (result) {
            try {
                ensureMdkInitializedWithPassword(inputPassword)
                upgradePbkdf2ParamsIfNeeded(inputPassword)
            } catch (e: Exception) {
                android.util.Log.w("SecurityManager", "MDK init failed: ${e.message}")
            }
        }
        return result
    }

    fun unlockVaultWithPassword(inputPassword: String): Boolean {
        if (!isMasterPasswordSet()) {
            return true
        }

        android.util.Log.d("SecurityManager", "Vault unlock requested")

        return try {
            val startedAt = System.currentTimeMillis()
            val storedHash = sharedPreferences.getString(MASTER_PASSWORD_HASH_KEY, null) ?: return false
            val storedSalt = sharedPreferences.getString(MASTER_PASSWORD_SALT_KEY, null)?.let { saltStr ->
                decodeHex(saltStr)
            } ?: return false

            // 主密码验证的代价几乎全在 PBKDF2 上：迭代次数直接决定耗时
            // （600k 次在本机上通常是秒级）。单独计时以便与 MDK 初始化区分开。
            val iterations = getStoredMasterPasswordIterations()
            val hashStartedAt = System.currentTimeMillis()
            val (computedHash, _) = hashMasterPassword(inputPassword, storedSalt, iterations)
            profileElapsed(
                "passwordUnlock.hashMasterPassword",
                hashStartedAt,
                "iterations=$iterations"
            )
            if (computedHash != storedHash) {
                profileElapsed("passwordUnlock.total", startedAt, "result=badPassword")
                return false
            }

            val mdkStartedAt = System.currentTimeMillis()
            ensureMdkInitializedWithPassword(inputPassword)
            profileElapsed("passwordUnlock.ensureMdkInitializedWithPassword", mdkStartedAt)

            // 存量参数升级会再跑 1~2 次 PBKDF2（600k），是密码解锁偏慢的常见放大器
            val upgradeStartedAt = System.currentTimeMillis()
            upgradePbkdf2ParamsIfNeeded(inputPassword)
            profileElapsed("passwordUnlock.upgradePbkdf2ParamsIfNeeded", upgradeStartedAt)

            profileElapsed("passwordUnlock.total", startedAt, "result=ok")
            true
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Vault password unlock failed: ${e.message}")
            false
        }
    }

    fun unlockVaultWithBiometric(): Boolean {
        if (!isMasterPasswordSet()) {
            return true
        }

        return try {
            // 注意：本方法由 Compose 侧（AuthComponents / PasswordListTopSection）
            // 直接同步调用，默认跑在**主线程**。Keystore 的 getEntry / Cipher.init
            // 都是跨进程 Binder 调用，主线程上做会直接卡住 UI —— 所以这里打上线程名，
            // 若日志显示 thread=main 且 costMs 偏大，就该把它挪到后台线程。
            val startedAt = System.currentTimeMillis()
            // A previous failed keystore read can set a short cooldown. Once the
            // user has just passed biometric auth, retry immediately instead of
            // reporting that the vault key is still unavailable.
            mdkAuthUnavailableUntilMillis = 0L
            val wrapperStartedAt = System.currentTimeMillis()
            ensureMdkKeystoreWrapper()
            profileElapsed("biometricUnlock.ensureMdkKeystoreWrapper", wrapperStartedAt)
            val mdkStartedAt = System.currentTimeMillis()
            val mdk = getMdkForCrypto()
            profileElapsed("biometricUnlock.getMdkForCrypto", mdkStartedAt)
            val ok = mdk != null && mdk.isNotEmpty()
            profileElapsed("biometricUnlock.total", startedAt, "ok=$ok")
            ok
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Vault biometric unlock failed: ${e.message}")
            false
        }
    }

    fun isVaultRuntimeUnlocked(): Boolean {
        return processCachedMdk?.isNotEmpty() == true
    }

    /**
     * Main app authentication success hook.
     * Writes the shared app session and clears temporary keystore cooldown flags.
     */
    fun markVaultAuthenticated() {
        SessionManager.markUnlocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
        hasLoggedReentryRequiredWarning = false
    }

    /**
     * Secondary entry-point authentication success hook.
     * Grants Autofill and IME their own unlock window without unlocking the main app.
     */
    fun markSecondaryVaultAuthenticated(autoLockMinutes: Int) {
        SecondarySessionManager.updateAutoLockTimeout(autoLockMinutes)
        SecondarySessionManager.markUnlocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
        hasLoggedReentryRequiredWarning = false
    }

    fun forceVaultReauthentication(reason: String) {
        android.util.Log.w(logTag, "forceVaultReauthentication: $reason")
        SecurityDiagLogger.append("W/$logTag forceVaultReauthentication: $reason")
        SessionManager.markLocked()
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
        hasLoggedReentryRequiredWarning = false
    }

    fun shouldForceVaultReauthenticationAfterDecryptFailure(error: Throwable?): Boolean {
        if (error == null || !isMasterPasswordSet()) return false
        if (error is android.security.keystore.KeyPermanentlyInvalidatedException) return true
        if (error is UserNotAuthenticatedException) return true
        if (error.message == "MDK not available") return true
        return !isVaultRuntimeUnlocked() && requiresPasswordReentryForWrapperRebuild()
    }

    fun handleVaultDecryptFailure(error: Throwable?): Boolean {
        if (!shouldForceVaultReauthenticationAfterDecryptFailure(error)) {
            return false
        }
        forceVaultReauthentication(
            "decrypt failure requires reauth: ${error?.javaClass?.simpleName ?: "unknown"}"
        )
        return true
    }

    fun canRestoreMainAppSession(context: Context, autoLockMinutes: Int): Boolean {
        if (!isMasterPasswordSet()) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: no master password set -> accessible")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: no master password set -> accessible")
            return true
        }

        if (!hasActiveSharedSession(context, autoLockMinutes)) {
            logRoutineDebug("canRestoreMainAppSession: session inactive -> locked")
            return false
        }

        if (isVaultRuntimeUnlocked()) {
            logRoutineDebug("canRestoreMainAppSession: runtime MDK cache present")
            return true
        }

        if (isMdkReadable()) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: MDK readable with active session")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: MDK readable with active session")
            return true
        }

        mdkAuthUnavailableUntilMillis = 0L
        val retriedAccessible = isMdkReadable()
        if (retriedAccessible) {
            android.util.Log.d(logTag, "canRestoreMainAppSession: MDK readable on retry with active session")
            SecurityDiagLogger.append("D/$logTag canRestoreMainAppSession: MDK readable on retry with active session")
            return true
        }

        android.util.Log.w(logTag, "canRestoreMainAppSession: active session but MDK unavailable")
        SecurityDiagLogger.append("W/$logTag canRestoreMainAppSession: active session but MDK unavailable")
        return false
    }

    fun canAccessVaultNowStrict(context: Context, autoLockMinutes: Int): Boolean {
        return canAccessVaultNow(context, autoLockMinutes, allowSessionOnlyFallback = false)
    }

    /**
     * Vault accessibility check for secondary secure entry points.
     * Requires an active access window from either the main app session or the
     * isolated secondary session, plus usable runtime key material.
     */
    fun canAccessVaultNow(
        context: Context,
        autoLockMinutes: Int,
        allowSessionOnlyFallback: Boolean = true
    ): Boolean {
        if (!isMasterPasswordSet()) {
            android.util.Log.d(logTag, "canAccessVaultNow: no master password set -> accessible")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: no master password set -> accessible")
            return true
        }
        val secondarySessionActive = hasActiveSecondarySession(context, autoLockMinutes)
        val sharedSessionActive = if (secondarySessionActive) {
            false
        } else {
            hasActiveSharedSession(context, autoLockMinutes)
        }
        if (!sharedSessionActive && !secondarySessionActive) {
            logRoutineDebug("canAccessVaultNow: session inactive -> locked")
            return false
        }

        if (isVaultRuntimeUnlocked()) {
            logRoutineDebug("canAccessVaultNow: runtime MDK cache present -> accessible")
            return true
        }

        if (isMdkReadable()) {
            android.util.Log.d(logTag, "canAccessVaultNow: MDK readable on first attempt")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: MDK readable on first attempt")
            return true
        }

        // Session is still valid but MDK read previously failed (often due a stale auth cooldown).
        // Force one immediate retry before reporting locked state.
        mdkAuthUnavailableUntilMillis = 0L
        val retriedAccessible = isMdkReadable()

        if (retriedAccessible) {
            android.util.Log.d(logTag, "canAccessVaultNow: MDK readable on retry")
            SecurityDiagLogger.append("D/$logTag canAccessVaultNow: MDK readable on retry")
            return true
        }

        val hasKeystoreWrapper = sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)
        val authCooldownActive = System.currentTimeMillis() < mdkAuthUnavailableUntilMillis

        android.util.Log.w(
            logTag,
            "canAccessVaultNow: locked after retry; wrapperPresent=$hasKeystoreWrapper, sharedSessionActive=$sharedSessionActive, secondarySessionActive=$secondarySessionActive, allowSessionOnlyFallback=$allowSessionOnlyFallback, authCooldownActive=$authCooldownActive"
        )
        SecurityDiagLogger.append(
            "W/$logTag canAccessVaultNow: locked after retry wrapperPresent=$hasKeystoreWrapper sharedSessionActive=$sharedSessionActive secondarySessionActive=$secondarySessionActive allowSessionOnlyFallback=$allowSessionOnlyFallback authCooldownActive=$authCooldownActive"
        )

        return false
    }

    /**
     * Checks whether usable vault key material is immediately available after an
     * explicit authentication step, without consulting any app session window.
     */
    fun canAccessVaultMaterialNow(): Boolean {
        if (!isMasterPasswordSet()) {
            return true
        }
        if (isVaultRuntimeUnlocked()) {
            return true
        }
        if (isMdkReadable()) {
            return true
        }
        mdkAuthUnavailableUntilMillis = 0L
        return isMdkReadable()
    }

    fun getVaultAccessState(context: Context, autoLockMinutes: Int): VaultAccessState {
        if (canAccessVaultNowStrict(context, autoLockMinutes)) {
            return VaultAccessState.ACCESSIBLE
        }
        return if (requiresPasswordReentryForWrapperRebuild()) {
            VaultAccessState.REQUIRES_PASSWORD_REENTRY
        } else {
            VaultAccessState.LOCKED
        }
    }

    fun requiresPasswordReentryForWrapperRebuild(): Boolean {
        if (!isMasterPasswordSet()) return false
        if (isVaultRuntimeUnlocked()) return false
        val ready = sharedPreferences.getBoolean(MDK_READY_KEY, false)
        if (!ready) return false
        val hasPasswordBlob = sharedPreferences.contains(MDK_PASSWORD_BLOB_KEY)
        if (!hasPasswordBlob) return false
        val hasKeystoreBlob = sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)
        val keystoreAliasMissing = hasKeystoreBlob && !hasSecureKeyAlias()
        val requires = !hasKeystoreBlob || keystoreAliasMissing
        // 一次性告警：本方法现在被 PasswordViewModel 的解密门控逐条调用，
        // 不加闸门会在「MDK 包装丢失」这种持续状态下一次批量操作刷出上百条同样的日志。
        if (requires && !hasLoggedReentryRequiredWarning) {
            hasLoggedReentryRequiredWarning = true
            android.util.Log.w(
                logTag,
                "requiresPasswordReentryForWrapperRebuild=true: ready=$ready, hasPasswordBlob=$hasPasswordBlob, hasKeystoreBlob=$hasKeystoreBlob, keystoreAliasMissing=$keystoreAliasMissing"
            )
            SecurityDiagLogger.append(
                "W/$logTag requiresPasswordReentryForWrapperRebuild=true ready=$ready hasPasswordBlob=$hasPasswordBlob hasKeystoreBlob=$hasKeystoreBlob keystoreAliasMissing=$keystoreAliasMissing"
            )
        }
        return requires
    }

    fun rebuildKeystoreWrapperFromRuntimeCacheIfNeeded(): Boolean {
        if (sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) {
            android.util.Log.d(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: wrapper already exists")
            SecurityDiagLogger.append("D/$logTag rebuildWrapper: wrapper already exists")
            return true
        }
        val mdk = processCachedMdk
        if (mdk == null || mdk.isEmpty()) {
            android.util.Log.w(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: runtime MDK cache missing")
            SecurityDiagLogger.append("W/$logTag rebuildWrapper: runtime MDK cache missing")
            return false
        }
        val persisted = persistKeystoreWrappedMdk(mdk)
        android.util.Log.d(logTag, "rebuildKeystoreWrapperFromRuntimeCacheIfNeeded: persisted=$persisted")
        SecurityDiagLogger.append("D/$logTag rebuildWrapper: persisted=$persisted")
        return persisted
    }

    private fun hasActiveSharedSession(context: Context, autoLockMinutes: Int): Boolean {
        SessionManager.updateAutoLockTimeout(autoLockMinutes)
        return SessionManager.canSkipVerification(context)
    }

    private fun hasActiveSecondarySession(context: Context, autoLockMinutes: Int): Boolean {
        SecondarySessionManager.updateAutoLockTimeout(autoLockMinutes)
        return SecondarySessionManager.canSkipVerification(context)
    }
    
    /**
     * Set the master password
     */
    fun setMasterPassword(password: String) {
        val (hashedPassword, salt) = hashMasterPassword(password)
        sharedPreferences.edit()
            .putString(MASTER_PASSWORD_HASH_KEY, hashedPassword)
            .putString(MASTER_PASSWORD_SALT_KEY, salt.joinToString("") { "%02x".format(it) })
            .putInt(MASTER_PASSWORD_ITERATIONS_KEY, PBKDF2_ITERATIONS)
            .apply()
        masterPasswordSetCached = true
        try {
            ensureMdkInitializedWithPassword(password, true)
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "MDK init on setMasterPassword failed: ${e.message}")
        }
    }
    
    /**
     * Check if master password is set
     */
    fun isMasterPasswordSet(): Boolean {
        return masterPasswordSetCached ?: sharedPreferences.contains(MASTER_PASSWORD_HASH_KEY)
            .also { masterPasswordSetCached = it }
    }
    
    /**
     * Reset master password - requires current password verification
     */
    fun resetMasterPassword(currentPassword: String, newPassword: String): Boolean {
        // Verify current password first
        if (!verifyMasterPassword(currentPassword)) {
            return false
        }
        
        // Set new password
        setMasterPassword(newPassword)
        return true
    }
    
    /**
     * Clear all security data (for complete reset scenarios)
     */
    fun clearSecurityData() {
        sharedPreferences.edit()
            .remove(MASTER_PASSWORD_HASH_KEY)
            .remove(MASTER_PASSWORD_SALT_KEY)
            .remove(MASTER_PASSWORD_ITERATIONS_KEY)
            .remove(BIOMETRIC_ENABLED_KEY)
            .remove(AUTO_LOCK_TIMEOUT_KEY)
            .remove(SECURITY_QUESTION_1_ID_KEY)
            .remove(SECURITY_QUESTION_1_ANSWER_KEY)
            .remove(SECURITY_QUESTION_1_SALT_KEY)
            .remove(SECURITY_QUESTION_2_ID_KEY)
            .remove(SECURITY_QUESTION_2_ANSWER_KEY)
            .remove(SECURITY_QUESTION_2_SALT_KEY)
            .remove(MDK_PASSWORD_BLOB_KEY)
            .remove(MDK_PASSWORD_SALT_KEY)
            .remove(MDK_PASSWORD_ITERATIONS_KEY)
            .remove(MDK_KEYSTORE_BLOB_KEY)
            .remove(MDK_READY_KEY)
            // V2 Bitwarden 凭据
            .remove(BITWARDEN_ACCESS_TOKEN_KEY)
            .remove(BITWARDEN_REFRESH_TOKEN_KEY)
            .remove(BITWARDEN_TOKEN_EXPIRY_KEY)
            .remove(BITWARDEN_USER_EMAIL_KEY)
            .remove(BITWARDEN_USER_ID_KEY)
            .remove(BITWARDEN_MASTER_KEY_HASH_KEY)
            .remove(BITWARDEN_SYMMETRIC_KEY_KEY)
            .remove(BITWARDEN_PRIVATE_KEY_KEY)
            .remove(BITWARDEN_SERVER_URL_KEY)
            .remove(BITWARDEN_CONNECTED_KEY)
            .apply()
        masterPasswordSetCached = false
        clearRuntimeUnlockCache()
    }
    
    /**
     * Biometric settings
     */
    fun setBiometricEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(BIOMETRIC_ENABLED_KEY, enabled).apply()
    }
    
    fun isBiometricEnabled(): Boolean {
        return sharedPreferences.getBoolean(BIOMETRIC_ENABLED_KEY, false)
    }
    
    /**
     * Auto-lock timeout settings (in minutes)
     */
    fun setAutoLockTimeout(minutes: Int) {
        sharedPreferences.edit().putInt(AUTO_LOCK_TIMEOUT_KEY, minutes).apply()
    }
    
    fun getAutoLockTimeout(): Int {
        return sharedPreferences.getInt(AUTO_LOCK_TIMEOUT_KEY, 5) // Default 5 minutes
    }
    
    /**
     * Security Questions Management
     */
    fun setSecurityQuestions(
        question1Id: Int,
        answer1: String,
        question2Id: Int,
        answer2: String,
        question1Text: String? = null,
        question2Text: String? = null
    ) {
        // 每个答案独立随机盐 + PBKDF2 慢哈希，抵御离线字典攻击（密保答案熵普遍偏低）。
        val salt1 = generateSalt()
        val salt2 = generateSalt()
        val hashedAnswer1 = hashAnswer(answer1, salt1)
        val hashedAnswer2 = hashAnswer(answer2, salt2)

        sharedPreferences.edit()
            .putInt(SECURITY_QUESTION_1_ID_KEY, question1Id)
            .putString(SECURITY_QUESTION_1_ANSWER_KEY, hashedAnswer1)
            .putString(SECURITY_QUESTION_1_SALT_KEY, salt1.joinToString("") { "%02x".format(it) })
            .putString(
                SECURITY_QUESTION_1_TEXT_KEY,
                if (PredefinedSecurityQuestions.isCustomQuestion(question1Id)) question1Text else null
            )
            .putInt(SECURITY_QUESTION_2_ID_KEY, question2Id)
            .putString(SECURITY_QUESTION_2_ANSWER_KEY, hashedAnswer2)
            .putString(SECURITY_QUESTION_2_SALT_KEY, salt2.joinToString("") { "%02x".format(it) })
            .putString(
                SECURITY_QUESTION_2_TEXT_KEY,
                if (PredefinedSecurityQuestions.isCustomQuestion(question2Id)) question2Text else null
            )
            .apply()
    }
    
    fun areSecurityQuestionsSet(): Boolean {
        return sharedPreferences.contains(SECURITY_QUESTION_1_ID_KEY) &&
                sharedPreferences.contains(SECURITY_QUESTION_2_ID_KEY)
    }
    
    fun getSecurityQuestion1Id(): Int {
        return sharedPreferences.getInt(SECURITY_QUESTION_1_ID_KEY, -1)
    }
    
    fun getSecurityQuestion2Id(): Int {
        return sharedPreferences.getInt(SECURITY_QUESTION_2_ID_KEY, -1)
    }

    fun getSecurityQuestion1Text(isZh: Boolean = false): String? {
        return resolveSecurityQuestionText(
            id = getSecurityQuestion1Id(),
            customText = sharedPreferences.getString(SECURITY_QUESTION_1_TEXT_KEY, null),
            isZh = isZh
        )
    }

    fun getSecurityQuestion2Text(isZh: Boolean = false): String? {
        return resolveSecurityQuestionText(
            id = getSecurityQuestion2Id(),
            customText = sharedPreferences.getString(SECURITY_QUESTION_2_TEXT_KEY, null),
            isZh = isZh
        )
    }
    
    fun verifySecurityAnswers(answer1: String, answer2: String): Boolean {
        val storedAnswer1 = sharedPreferences.getString(SECURITY_QUESTION_1_ANSWER_KEY, null) ?: return false
        val storedAnswer2 = sharedPreferences.getString(SECURITY_QUESTION_2_ANSWER_KEY, null) ?: return false

        val salt1Hex = sharedPreferences.getString(SECURITY_QUESTION_1_SALT_KEY, null)
        val salt2Hex = sharedPreferences.getString(SECURITY_QUESTION_2_SALT_KEY, null)

        val match1 = if (salt1Hex != null) {
            hashAnswer(answer1, decodeHex(salt1Hex)) == storedAnswer1
        } else {
            hashAnswerLegacy(answer1) == storedAnswer1
        }
        val match2 = if (salt2Hex != null) {
            hashAnswer(answer2, decodeHex(salt2Hex)) == storedAnswer2
        } else {
            hashAnswerLegacy(answer2) == storedAnswer2
        }

        // 旧无盐格式验证通过后透明升级为加盐 PBKDF2，用户无感知。
        if (match1 && match2 && (salt1Hex == null || salt2Hex == null)) {
            upgradeSecurityAnswerHashes(answer1, answer2)
        }
        return match1 && match2
    }

    private fun upgradeSecurityAnswerHashes(answer1: String, answer2: String) {
        try {
            val editor = sharedPreferences.edit()
            if (sharedPreferences.getString(SECURITY_QUESTION_1_SALT_KEY, null) == null) {
                val salt = generateSalt()
                editor.putString(SECURITY_QUESTION_1_SALT_KEY, salt.joinToString("") { "%02x".format(it) })
                editor.putString(SECURITY_QUESTION_1_ANSWER_KEY, hashAnswer(answer1, salt))
            }
            if (sharedPreferences.getString(SECURITY_QUESTION_2_SALT_KEY, null) == null) {
                val salt = generateSalt()
                editor.putString(SECURITY_QUESTION_2_SALT_KEY, salt.joinToString("") { "%02x".format(it) })
                editor.putString(SECURITY_QUESTION_2_ANSWER_KEY, hashAnswer(answer2, salt))
            }
            editor.apply()
            SecurityDiagLogger.append("I/$logTag upgradeSecurityAnswerHashes: legacy SHA-256 upgraded to salted PBKDF2")
        } catch (e: Exception) {
            android.util.Log.w(logTag, "upgradeSecurityAnswerHashes failed: ${e.message}")
        }
    }

    private fun hashAnswer(answer: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): String {
        val cleanAnswer = answer.trim().lowercase()
        val spec = PBEKeySpec(cleanAnswer.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    /** 旧版无盐 SHA-256，仅用于存量数据验证与透明升级。 */
    private fun hashAnswerLegacy(answer: String): String {
        val cleanAnswer = answer.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(cleanAnswer.toByteArray())
        return hashedBytes.joinToString("") { "%02x".format(it) }
    }

    fun clearSecurityQuestions() {
        sharedPreferences.edit()
            .remove(SECURITY_QUESTION_1_ID_KEY)
            .remove(SECURITY_QUESTION_1_ANSWER_KEY)
            .remove(SECURITY_QUESTION_1_TEXT_KEY)
            .remove(SECURITY_QUESTION_1_SALT_KEY)
            .remove(SECURITY_QUESTION_2_ID_KEY)
            .remove(SECURITY_QUESTION_2_ANSWER_KEY)
            .remove(SECURITY_QUESTION_2_TEXT_KEY)
            .remove(SECURITY_QUESTION_2_SALT_KEY)
            .apply()
    }

    private fun resolveSecurityQuestionText(
        id: Int,
        customText: String?,
        isZh: Boolean
    ): String? {
        return if (PredefinedSecurityQuestions.isCustomQuestion(id)) {
            customText
        } else {
            PredefinedSecurityQuestions.getQuestionById(id, isZh)?.questionText
        }
    }
    
    /**
     * Get or create a secure key from Android KeyStore.
     * This key requires user authentication (biometric) to be used.
     *
     * 缓存说明（与 [getOrGenerateCompatSecureKey] 同一套双检锁模式）：
     * `KeyStore.getInstance + load + getEntry` 每次调用都是一次跨进程 Binder 往返，
     * 实测是冷启动批量解密的主要开销来源 —— 旧实现下每个密码条目最多会触发 3 次。
     *
     * 安全性不变：缓存的只是 Keystore 返回的密钥**句柄**，密钥材料仍留在 Keystore 内；
     * `setUserAuthenticationRequired(true)` 的校验发生在 `Cipher.init()` 而不是
     * `getEntry()`，因此缓存句柄不会绕过生物认证，认证过期时仍会照常抛
     * UserNotAuthenticatedException。密钥被永久失效（录入新指纹）时，
     * [invalidateCachedSecureKey] 会清掉缓存，下次调用重新取。
     */
    private fun getOrGenerateSecureKey(): SecretKey {
        cachedDataKey?.let { return it }

        return synchronized(dataKeyLock) {
            cachedDataKey?.let { return@synchronized it }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS_DATA)) {
                val entry = keyStore.getEntry(KEY_ALIAS_DATA, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return@synchronized entry.secretKey.also { cachedDataKey = it }
                }
            }

            // Generate new key
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS_DATA,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(300) // Allow use for 5 minutes after authentication

            builder.setInvalidatedByBiometricEnrollment(true) // Key becomes permanently invalid if new biometric is enrolled

            keyGenerator.init(builder.build())
            keyGenerator.generateKey().also { cachedDataKey = it }
        }
    }

    /**
     * 丢弃 [cachedDataKey]，迫使下次 [getOrGenerateSecureKey] 重新访问 Keystore。
     *
     * 必须在「密钥可能已不再有效」的时刻调用：别名被删除、密钥被永久失效
     * （KeyPermanentlyInvalidatedException）、或不可恢复（UnrecoverableKeyException）。
     * 否则缓存会一直持有一个已失效的句柄，让后续解密反复失败。
     */
    private fun invalidateCachedSecureKey() {
        cachedDataKey = null
    }

    private fun getOrGenerateCompatSecureKey(): SecretKey {
        cachedCompatDataKey?.let { return it }

        return synchronized(compatDataKeyLock) {
            cachedCompatDataKey?.let { return@synchronized it }

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS_DATA_COMPAT)) {
                val entry = keyStore.getEntry(KEY_ALIAS_DATA_COMPAT, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return@synchronized entry.secretKey.also { cachedCompatDataKey = it }
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS_DATA_COMPAT,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey().also { cachedCompatDataKey = it }
        }
    }

    private fun hasSecureKeyAlias(alias: String = KEY_ALIAS_DATA): Boolean {
        return runCatchingObserved {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias(alias)
        }.getOrDefault(false)
    }

    private fun deleteSecureKeyAlias(alias: String) {
        // 别名被删 → 缓存里的句柄即使还指向旧对象也已失去意义，必须丢弃，
        // 否则下次 getOrGenerateSecureKey() 会直接返回这个悬空句柄而不是重新生成。
        if (alias == KEY_ALIAS_DATA) invalidateCachedSecureKey()
        runCatchingObserved {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }.onFailure { e ->
            android.util.Log.w(logTag, "deleteSecureKeyAlias failed for $alias: ${e.javaClass.simpleName}")
            SecurityDiagLogger.append("W/$logTag deleteSecureKeyAlias failed alias=$alias type=${e.javaClass.simpleName}")
        }
    }

    private fun parseWrappedMdkBlob(rawBlob: String): WrappedMdkBlob {
        return when {
            rawBlob.startsWith(WRAPPER_PREFIX_COMPAT) -> WrappedMdkBlob(
                mode = WrapperMode.COMPAT,
                payload = rawBlob.removePrefix(WRAPPER_PREFIX_COMPAT)
            )
            rawBlob.startsWith(WRAPPER_PREFIX_AUTH) -> WrappedMdkBlob(
                mode = WrapperMode.AUTH,
                payload = rawBlob.removePrefix(WRAPPER_PREFIX_AUTH)
            )
            else -> WrappedMdkBlob(
                mode = WrapperMode.AUTH,
                payload = rawBlob
            )
        }
    }

    private fun hasRequiredAliasForStoredWrapper(): Boolean {
        val rawBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null) ?: return true
        val wrapper = parseWrappedMdkBlob(rawBlob)
        return when (wrapper.mode) {
            WrapperMode.AUTH -> hasSecureKeyAlias(KEY_ALIAS_DATA)
            WrapperMode.COMPAT -> hasSecureKeyAlias(KEY_ALIAS_DATA_COMPAT)
        }
    }

    private fun isMdkReadable(): Boolean {
        return try {
            val mdk = getMdkForCrypto()
            mdk != null && mdk.isNotEmpty()
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun clearKeystoreWrappedMdk(reason: String) {
        android.util.Log.w("SecurityManager", "Clearing stale MDK keystore wrapper: $reason")
        sharedPreferences.edit().remove(MDK_KEYSTORE_BLOB_KEY).apply()
    }

    private fun persistKeystoreWrappedMdk(mdk: ByteArray): Boolean {
        if (mdk.isEmpty()) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: skip empty MDK")
            return false
        }
        return try {
            val ksKey = getOrGenerateSecureKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, ksKey)
            val iv = cipher.iv
            val enc = cipher.doFinal(mdk)
            val combined = iv + enc
            val blob = WRAPPER_PREFIX_AUTH + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            sharedPreferences.edit()
                .putString(MDK_KEYSTORE_BLOB_KEY, blob)
                .apply()
            android.util.Log.d(logTag, "persistKeystoreWrappedMdk: success")
            SecurityDiagLogger.append("D/$logTag persistKeystoreWrappedMdk: success")
            true
        } catch (e: UserNotAuthenticatedException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: user not authenticated")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: user not authenticated")
            persistCompatKeystoreWrappedMdk(mdk)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: secure key invalidated; rebuilding with compatibility wrapper")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: secure key invalidated")
            deleteSecureKeyAlias(KEY_ALIAS_DATA)
            clearKeystoreWrappedMdk("secure key permanently invalidated while rebuilding wrapper")
            persistCompatKeystoreWrappedMdk(mdk)
        } catch (e: UnrecoverableKeyException) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk: secure key unrecoverable; rebuilding with compatibility wrapper")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk: secure key unrecoverable")
            deleteSecureKeyAlias(KEY_ALIAS_DATA)
            clearKeystoreWrappedMdk("secure key unrecoverable while rebuilding wrapper")
            persistCompatKeystoreWrappedMdk(mdk)
        } catch (e: Exception) {
            android.util.Log.w(logTag, "persistKeystoreWrappedMdk failed: ${e.javaClass.simpleName}, message=${e.message}")
            SecurityDiagLogger.append("W/$logTag persistKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun persistCompatKeystoreWrappedMdk(mdk: ByteArray): Boolean {
        return try {
            val compatKey = getOrGenerateCompatSecureKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, compatKey)
            val iv = cipher.iv
            val enc = cipher.doFinal(mdk)
            val combined = iv + enc
            val blob = WRAPPER_PREFIX_COMPAT + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            sharedPreferences.edit()
                .putString(MDK_KEYSTORE_BLOB_KEY, blob)
                .apply()
            android.util.Log.w(logTag, "persistCompatKeystoreWrappedMdk: saved NON-biometric compatibility wrapper (biometric binding NOT enforced)")
            SecurityDiagLogger.append("W/$logTag persistCompatKeystoreWrappedMdk: saved NON-biometric compatibility wrapper (biometric binding NOT enforced)")
            true
        } catch (e: Exception) {
            android.util.Log.w(logTag, "persistCompatKeystoreWrappedMdk failed: ${e.javaClass.simpleName}, message=${e.message}")
            SecurityDiagLogger.append("W/$logTag persistCompatKeystoreWrappedMdk failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun generateRandom(bytes: Int): ByteArray {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b
    }

    private fun deriveAesKeyFromPassword(
        password: String,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun aesGcmEncrypt(key: SecretKeySpec, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val enc = cipher.doFinal(data)
        val combined = iv + enc
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    private fun aesGcmDecrypt(key: SecretKeySpec, combinedBase64: String): ByteArray {
        val combined = android.util.Base64.decode(combinedBase64, android.util.Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val enc = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(enc)
    }

    private fun ensureMdkInitializedWithPassword(password: String, forceUpdate: Boolean = false) {
        val hasPasswordBlob = sharedPreferences.contains(MDK_PASSWORD_BLOB_KEY)
        var hasKeystoreBlob = sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)
        android.util.Log.d(
            logTag,
            "ensureMdkInitializedWithPassword: forceUpdate=$forceUpdate, hasPasswordBlob=$hasPasswordBlob, hasKeystoreBlob=$hasKeystoreBlob"
        )
        if (hasKeystoreBlob && !hasRequiredAliasForStoredWrapper()) {
            clearKeystoreWrappedMdk("secure key alias missing; likely app clone or restored app data")
            hasKeystoreBlob = false
        }
        var mdk: ByteArray? = null
        if (!hasPasswordBlob && !hasKeystoreBlob) {
            mdk = generateRandom(32)
        }
        val salt = if (forceUpdate || !sharedPreferences.contains(MDK_PASSWORD_SALT_KEY)) {
            generateRandom(32)
        } else {
            val saltHex = sharedPreferences.getString(MDK_PASSWORD_SALT_KEY, null)
            saltHex?.let { decodeHex(it) } ?: generateRandom(32)
        }
        // 解密存量 blob 用存储的迭代次数（无键时按旧值 100k）；
        // 新建/重写包装时升级到当前参数，并把实际值持久化。
        val mdkIterations = if (forceUpdate || !hasPasswordBlob) {
            PBKDF2_ITERATIONS
        } else {
            getStoredMdkPasswordIterations()
        }
        val pwKey = deriveAesKeyFromPassword(password, salt, mdkIterations)
        var shouldRewritePasswordBlob = !hasPasswordBlob || forceUpdate
        val actualMdk = if (hasPasswordBlob && !forceUpdate) {
            val blob = sharedPreferences.getString(MDK_PASSWORD_BLOB_KEY, null)
            val decrypted = if (blob != null) {
                aesGcmDecrypt(pwKey, blob)
            } else {
                ByteArray(0)
            }
            if (decrypted.isNotEmpty()) {
                decrypted
            } else {
                android.util.Log.w(
                    logTag,
                    "ensureMdkInitializedWithPassword: password-wrapped MDK is empty; attempting recovery"
                )
                SecurityDiagLogger.append(
                    "W/$logTag ensureMdkInitializedWithPassword: password-wrapped MDK is empty; attempting recovery"
                )
                val recovered = mdk ?: getOrCreateMdkBytes()
                shouldRewritePasswordBlob = true
                if (recovered.isNotEmpty()) {
                    recovered
                } else {
                    android.util.Log.w(
                        logTag,
                        "ensureMdkInitializedWithPassword: MDK recovery unavailable; generating fresh MDK"
                    )
                    SecurityDiagLogger.append(
                        "W/$logTag ensureMdkInitializedWithPassword: MDK recovery unavailable; generating fresh MDK"
                    )
                    clearKeystoreWrappedMdk("MDK recovery unavailable after empty password blob")
                    generateRandom(32)
                }
            }
        } else {
            val candidate = mdk ?: getOrCreateMdkBytes()
            if (candidate.isNotEmpty()) {
                candidate
            } else if (forceUpdate) {
                android.util.Log.w(
                    logTag,
                    "ensureMdkInitializedWithPassword: existing MDK unavailable during forceUpdate; generating fresh MDK"
                )
                SecurityDiagLogger.append(
                    "W/$logTag ensureMdkInitializedWithPassword: existing MDK unavailable during forceUpdate; generating fresh MDK"
                )
                clearKeystoreWrappedMdk("existing MDK unavailable during forceUpdate")
                generateRandom(32)
            } else {
                candidate
            }
        }
        processCachedMdk = actualMdk
        if (shouldRewritePasswordBlob) {
            val blob = aesGcmEncrypt(pwKey, actualMdk)
            sharedPreferences.edit()
                .putString(MDK_PASSWORD_BLOB_KEY, blob)
                .putString(MDK_PASSWORD_SALT_KEY, salt.joinToString("") { "%02x".format(it) })
                .putInt(MDK_PASSWORD_ITERATIONS_KEY, mdkIterations)
                .putBoolean(MDK_READY_KEY, true)
                .apply()
        } else {
            sharedPreferences.edit().putBoolean(MDK_READY_KEY, true).apply()
        }
        // 密码解锁后重新包装 MDK 的 Keystore 包装：优先用 AUTH（需生物识别）密钥包装，
        // 以恢复生物识别绑定（数据静息保护）。persistKeystoreWrappedMdk 仅在 Keystore 拒绝
        // AUTH 写入（UserNotAuthenticatedException / 密钥失效等已知机型兼容问题）时才回退到
        // COMPAT 兜底，因此在支持的设备上生物识别绑定得以恢复，同时不破坏已知异常机型的可用性。
        val persisted = persistKeystoreWrappedMdk(actualMdk)
        android.util.Log.d(
            logTag,
            "ensureMdkInitializedWithPassword: keystore wrapper refresh after password unlock success=$persisted"
        )
        mdkAuthUnavailableUntilMillis = 0L
        hasLoggedMdkAuthExpiredWarning = false
        hasLoggedMdkFallbackEncryption = false
        hasLoggedReentryRequiredWarning = false
    }

    private fun ensureMdkKeystoreWrapper() {
        if (sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) return
        val mdk = getOrCreateMdkBytes()
        if (mdk.isEmpty()) {
            android.util.Log.w("SecurityManager", "Cannot create MDK keystore wrapper without current MDK")
            return
        }
        persistKeystoreWrappedMdk(mdk)
    }

    private fun getOrCreateMdkBytes(): ByteArray {
        processCachedMdk?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached.copyOf()
            }
            processCachedMdk = null
            android.util.Log.w(logTag, "getOrCreateMdkBytes: ignoring empty runtime MDK cache")
            SecurityDiagLogger.append("W/$logTag getOrCreateMdkBytes: ignoring empty runtime MDK cache")
        }
        val passwordBlob = sharedPreferences.getString(MDK_PASSWORD_BLOB_KEY, null)
        val keystoreBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null)
        if (passwordBlob == null && keystoreBlob == null) {
            return generateRandom(32)
        }
        if (keystoreBlob == null) {
            android.util.Log.w("SecurityManager", "MDK keystore wrapper missing and no cached MDK is available")
            return ByteArray(0)
        }
        return try {
            val wrapped = parseWrappedMdkBlob(keystoreBlob)
            val ksKey = when (wrapped.mode) {
                WrapperMode.AUTH -> getOrGenerateSecureKey()
                WrapperMode.COMPAT -> getOrGenerateCompatSecureKey()
            }
            val combined = android.util.Base64.decode(wrapped.payload, android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, ksKey, spec)
            cipher.doFinal(enc)
        } catch (e: Exception) {
            android.util.Log.w("SecurityManager", "Get MDK by keystore failed: ${e.message}")
            ByteArray(0)
        }
    }

    private fun getMdkForCrypto(): ByteArray? {
        processCachedMdk?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
            processCachedMdk = null
            android.util.Log.w(logTag, "getMdkForCrypto: ignoring empty runtime MDK cache")
            SecurityDiagLogger.append("W/$logTag getMdkForCrypto: ignoring empty runtime MDK cache")
        }
        val now = System.currentTimeMillis()
        if (now < mdkAuthUnavailableUntilMillis) {
            return null
        }
        val ready = sharedPreferences.getBoolean(MDK_READY_KEY, false)
        if (!ready) return null
        val rawBlob = sharedPreferences.getString(MDK_KEYSTORE_BLOB_KEY, null) ?: return null
        val wrapped = parseWrappedMdkBlob(rawBlob)
        val usesCompatWrapper = wrapped.mode == WrapperMode.COMPAT
        if (!usesCompatWrapper && !hasSecureKeyAlias(KEY_ALIAS_DATA)) {
            clearKeystoreWrappedMdk("secure key alias missing while reading wrapper; likely app clone or restored app data")
            return null
        }
        return try {
            // 冷启动可观测性：只在这条「真的要走 Keystore 解包」的分支计时。
            // 命中 processCachedMdk 的调用在上面就 return 了，不会刷日志。
            val unwrapStartedAt = System.currentTimeMillis()
            val ksKey = if (usesCompatWrapper) getOrGenerateCompatSecureKey() else getOrGenerateSecureKey()
            val combined = android.util.Base64.decode(wrapped.payload, android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, ksKey, spec)
            val mdk = cipher.doFinal(enc)
            android.util.Log.i(
                logTag,
                "getMdkForCrypto: unwrapped MDK in " +
                    "${System.currentTimeMillis() - unwrapStartedAt}ms (compatWrapper=$usesCompatWrapper)"
            )
            processCachedMdk = mdk
            mdkAuthUnavailableUntilMillis = 0L
            hasLoggedMdkAuthExpiredWarning = false
            hasLoggedMdkFallbackEncryption = false
            if (usesCompatWrapper) {
                android.util.Log.w(logTag, "getMdkForCrypto: using compatibility wrapper mode")
            }
            mdk
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException: 生物识别已更改，密钥永久失效
            // UserNotAuthenticatedException: 用户认证已过期，需要重新认证
            if (e is android.security.keystore.KeyPermanentlyInvalidatedException) {
                // 句柄已永久失效，清缓存，避免后续调用一直复用它反复失败
                invalidateCachedSecureKey()
                throw e  // 密钥永久失效，必须抛出让用户重新设置
            }
            if (e is UserNotAuthenticatedException) {
                if (!hasLoggedMdkAuthExpiredWarning) {
                    android.util.Log.w("SecurityManager", "User authentication expired, MDK not available")
                    hasLoggedMdkAuthExpiredWarning = true
                }
                // Cooldown to avoid hot-looping keystore access when auth is expired.
                mdkAuthUnavailableUntilMillis = System.currentTimeMillis() + mdkAuthCooldownMillis
                return null  // 认证过期，返回 null 让调用方降级处理
            }
            clearKeystoreWrappedMdk("keystore wrapper unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private val DATA_PREFIX_MDK = "MDK|"

    /**
     * AES encryption for sensitive data (additional layer)
     * Automatically chooses between V2 (Secure KeyStore) and V1 (Legacy) based on biometric settings.
     * 
     * 安全策略：
     * - 优先使用 MDK 加密（最安全）
     * - 如果 MDK 不可用（认证过期），降级到 V1 加密
     * - 只有当密钥永久失效时才抛出异常
     */
    fun encryptData(data: String): String {
        // 尝试使用 MDK 加密
        val mdk = try {
            getMdkForCrypto()
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // 密钥永久失效（生物识别已更改），需要用户重新设置
            android.util.Log.e("SecurityManager", "Key permanently invalidated", e)
            throw e
        }
        
        if (mdk != null && mdk.isNotEmpty()) {
            val key = SecretKeySpec(mdk, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val enc = cipher.doFinal(data.toByteArray())
            val combined = iv + enc
            return DATA_PREFIX_MDK + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        }
        
        // MDK 不可用，降级到不需要生物识别窗口的兼容 Keystore 密钥。
        if (!hasLoggedMdkFallbackEncryption) {
            android.util.Log.d("SecurityManager", "MDK not available, using compat Keystore encryption")
            hasLoggedMdkFallbackEncryption = true
        }
        return encryptDataCompat(data)
    }

    /**
     * Compatibility helper (Phase B.1.3): force compat payload for scenarios where
     * immediate readability is required under unstable MDK auth state.
     *
     * 历史背景：本方法命名中的 "Legacy" 指品牌重塑前 (Monica Pass) 的加密兼容层，
     * 与已移除的 MDBX 存储引擎无关。存量用户数据可能使用旧 Keystore 别名
     * (monica_data_key_v2 / monica_data_key_v2_compat) 加密，解密时通过
     * [tryGetLegacyKey] 回退读取，加密时一律使用新别名 (bastion_data_key_v2)。
     * 未来可通过渐进式迁移将旧别名数据重新加密为新区名后淘汰本兼容路径。
     */
    fun encryptDataLegacyCompat(data: String): String {
        return encryptDataCompat(data)
    }

    /**
     * Timeline snapshots must never silently downgrade to the compatibility key
     * when the vault has a master password. They are readable only while the
     * main vault key is present in the authenticated runtime session.
     */
    fun encryptTimelineSnapshot(data: String): String {
        if (!isMasterPasswordSet()) return encryptDataCompat(data)
        check(isVaultRuntimeUnlocked()) { "Vault authentication required" }
        val encrypted = encryptData(data)
        check(encrypted.startsWith(DATA_PREFIX_MDK)) { "Timeline snapshot requires MDK encryption" }
        return encrypted
    }

    fun decryptTimelineSnapshot(encryptedData: String): String {
        if (isMasterPasswordSet()) {
            check(isVaultRuntimeUnlocked()) { "Vault authentication required" }
            check(
                encryptedData.startsWith(DATA_PREFIX_MDK) ||
                    encryptedData.startsWith(DATA_PREFIX_COMPAT)
            ) { "Invalid timeline snapshot encryption" }
        }
        return decryptData(encryptedData)
    }

    fun looksLikeBastionCiphertext(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith(DATA_PREFIX_MDK) ||
            trimmed.startsWith(DATA_PREFIX_V2) ||
            trimmed.startsWith(DATA_PREFIX_COMPAT)
    }

    fun decryptDataIfBastionCiphertext(value: String): String {
        return if (looksLikeBastionCiphertext(value)) {
            decryptData(value)
        } else {
            value
        }
    }

    private fun encryptDataV2(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrGenerateSecureKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(data.toByteArray())
        
        // Combine IV and encrypted data
        val combined = iv + encryptedBytes
        return DATA_PREFIX_V2 + android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
    }

    private fun encryptDataCompat(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = getOrGenerateCompatSecureKey()
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes
        return DATA_PREFIX_COMPAT + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }
    
    fun decryptData(encryptedData: String): String {
        if (encryptedData.isEmpty()) {
            return ""
        }

        if (encryptedData.startsWith(DATA_PREFIX_MDK)) {
            val mdk = getMdkForCrypto()
            if (mdk == null || mdk.isEmpty()) throw Exception("MDK not available")
            val combined = android.util.Base64.decode(encryptedData.substring(DATA_PREFIX_MDK.length), android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val key = SecretKeySpec(mdk, "AES")
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val dec = cipher.doFinal(enc)
            return String(dec, kotlin.text.Charsets.UTF_8)
        }

        if (encryptedData.startsWith(DATA_PREFIX_V2)) {
            return try {
                decryptDataV2(encryptedData)
            } catch (e: Exception) {
                android.util.Log.e("SecurityManager", "V2 Decryption failed", e)
                throw e // Rethrow to let caller handle auth failure
            }
        }

        if (encryptedData.startsWith(DATA_PREFIX_COMPAT)) {
            return decryptDataCompat(encryptedData)
        }

        return decryptLegacyV1OrPlainText(encryptedData)
    }

    private fun decryptDataV2(encryptedData: String): String {
        val combined = android.util.Base64.decode(encryptedData.substring(DATA_PREFIX_V2.length), android.util.Base64.DEFAULT)
        
        // Extract IV and encrypted data
        val iv = combined.copyOfRange(0, 12) // GCM IV is 12 bytes
        val encrypted = combined.copyOfRange(12, combined.size)

        val gcmSpec = GCMParameterSpec(128, iv)

        // Try current key first
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = getOrGenerateSecureKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val decryptedBytes = cipher.doFinal(encrypted)
            return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
        } catch (e: Exception) {
            // 缓存的密钥句柄已失效（录入新指纹 / 密钥不可恢复）：丢弃缓存，
            // 让下次调用重新从 Keystore 取，而不是一直复用这个坏句柄。
            if (e is android.security.keystore.KeyPermanentlyInvalidatedException ||
                e is UnrecoverableKeyException
            ) {
                invalidateCachedSecureKey()
            }
            // Fallback: try legacy key alias (pre-rebrand "monica_data_key_v2")
            tryGetLegacyKey(LEGACY_KEY_ALIAS_DATA)?.let { legacyKey ->
                try {
                    val legacyCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    legacyCipher.init(Cipher.DECRYPT_MODE, legacyKey, gcmSpec)
                    val decryptedBytes = legacyCipher.doFinal(encrypted)
                    return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
                } catch (_: Exception) {
                    // Legacy key also failed, fall through to rethrow original error
                }
            }
            throw e
        }
    }

    private fun decryptDataCompat(encryptedData: String): String {
        val combined = android.util.Base64.decode(
            encryptedData.substring(DATA_PREFIX_COMPAT.length),
            android.util.Base64.NO_WRAP
        )
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

        val gcmSpec = GCMParameterSpec(128, iv)

        // Try current compat key first
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = getOrGenerateCompatSecureKey()
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val decryptedBytes = cipher.doFinal(encrypted)
            return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback: try legacy compat key alias (pre-rebrand "monica_data_key_v2_compat")
            tryGetLegacyKey(LEGACY_KEY_ALIAS_DATA_COMPAT)?.let { legacyKey ->
                try {
                    val legacyCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    legacyCipher.init(Cipher.DECRYPT_MODE, legacyKey, gcmSpec)
                    val decryptedBytes = legacyCipher.doFinal(encrypted)
                    return String(decryptedBytes, kotlin.text.Charsets.UTF_8)
                } catch (_: Exception) {
                    // Legacy key also failed, fall through to rethrow original error
                }
            }
            throw e
        }
    }

    /**
     * Attempt to retrieve a legacy Keystore key by alias WITHOUT generating a new key.
     * Returns null if the alias doesn't exist or can't be accessed.
     * Used for backward-compatible decryption of data encrypted before the rebrand.
     */
    private fun tryGetLegacyKey(alias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(alias)) {
                (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptLegacyV1OrPlainText(encryptedData: String): String {
        if (encryptedData.isEmpty()) return ""

        val combined = try {
            android.util.Base64.decode(encryptedData, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            // Not a Base64 payload, likely plain text.
            return encryptedData
        }

        // Legacy payload format is: 12-byte IV + ciphertext(>=0) + 16-byte GCM tag.
        // Empty plaintext is valid and yields exactly 28 bytes.
        if (combined.size < 28) {
            return encryptedData
        }

        return try {
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Historical unprefixed V1 payloads were derived from this predictable
            // value. Keep it read-only so old local data can be opened and migrated.
            val keyBytes = masterKey.toString().toByteArray().copyOf(32)
            val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(encrypted)
            String(decryptedBytes, kotlin.text.Charsets.UTF_8)
        } catch (_: Exception) {
            // Fallback to original data if decryption fails.
            encryptedData
        }
    }
    
    /**
     * Generate secure random password
     */
    fun generateSecurePassword(length: Int = 16, includeSymbols: Boolean = true): String {
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val numbers = "0123456789"
        val symbols = "!@#$%^&*()_+-=[]{}|;:,.<>?"
        
        val charset = lowercase + uppercase + numbers + if (includeSymbols) symbols else ""
        val random = SecureRandom()
        
        return (1..length)
        .map { charset[random.nextInt(charset.length)] }
        .joinToString("")
    }
    
    /**
     * Validate password strength
     */
    fun validatePasswordStrength(password: String): PasswordStrength {
        val length = password.length
        val hasLowercase = password.any { it.isLowerCase() }
        val hasUppercase = password.any { it.isUpperCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        
        val score = listOf(
            length >= 8,
            length >= 12,
            hasLowercase,
            hasUppercase,
            hasDigit,
            hasSymbol
        ).count { it }
        
        return when {
            score <= 2 -> PasswordStrength.WEAK
            score <= 4 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.STRONG
        }
    }
    
    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }
    
    // ==================== V2 Bitwarden 凭据管理 ====================
    
    /**
     * Bitwarden 凭据数据类
     * 用于存储登录后的认证信息
     */
    data class BitwardenCredential(
        val accessToken: String,
        val refreshToken: String,
        val tokenExpiry: Long,          // 过期时间戳（毫秒）
        val userEmail: String,
        val userId: String,
        val serverUrl: String = "https://vault.bitwarden.com"  // 默认官方服务器
    )
    
    /**
     * Bitwarden 加密密钥数据类
     * 用于存储解密 Vault 数据所需的密钥
     */
    data class BitwardenCryptoKeys(
        val masterKeyHash: String,      // 主密码哈希（用于验证）
        val symmetricKey: String,       // 对称加密密钥（加密存储）
        val privateKey: String?         // RSA 私钥（加密存储，可选）
    )
    
    /**
     * 保存 Bitwarden 登录凭据
     * 使用 EncryptedSharedPreferences 安全存储
     * 
     * @param credential Bitwarden 凭据
     */
    fun saveBitwardenCredential(credential: BitwardenCredential) {
        // Access Token 和 Refresh Token 使用额外的 AES-GCM 加密层
        val encryptedAccessToken = encryptData(credential.accessToken)
        val encryptedRefreshToken = encryptData(credential.refreshToken)
        
        sharedPreferences.edit()
            .putString(BITWARDEN_ACCESS_TOKEN_KEY, encryptedAccessToken)
            .putString(BITWARDEN_REFRESH_TOKEN_KEY, encryptedRefreshToken)
            .putLong(BITWARDEN_TOKEN_EXPIRY_KEY, credential.tokenExpiry)
            .putString(BITWARDEN_USER_EMAIL_KEY, credential.userEmail)
            .putString(BITWARDEN_USER_ID_KEY, credential.userId)
            .putString(BITWARDEN_SERVER_URL_KEY, credential.serverUrl)
            .putBoolean(BITWARDEN_CONNECTED_KEY, true)
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden credential saved")
    }
    
    /**
     * 获取 Bitwarden 登录凭据
     * 
     * @return BitwardenCredential 或 null（未登录）
     */
    fun getBitwardenCredential(): BitwardenCredential? {
        if (!isBitwardenConnected()) {
            return null
        }
        
        return try {
            val encryptedAccessToken = sharedPreferences.getString(BITWARDEN_ACCESS_TOKEN_KEY, null)
            val encryptedRefreshToken = sharedPreferences.getString(BITWARDEN_REFRESH_TOKEN_KEY, null)
            
            if (encryptedAccessToken == null || encryptedRefreshToken == null) {
                return null
            }
            
            BitwardenCredential(
                accessToken = decryptData(encryptedAccessToken),
                refreshToken = decryptData(encryptedRefreshToken),
                tokenExpiry = sharedPreferences.getLong(BITWARDEN_TOKEN_EXPIRY_KEY, 0L),
                userEmail = sharedPreferences.getString(BITWARDEN_USER_EMAIL_KEY, "") ?: "",
                userId = sharedPreferences.getString(BITWARDEN_USER_ID_KEY, "") ?: "",
                serverUrl = sharedPreferences.getString(BITWARDEN_SERVER_URL_KEY, "https://vault.bitwarden.com") ?: "https://vault.bitwarden.com"
            )
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to get Bitwarden credential", e)
            null
        }
    }
    
    /**
     * 保存 Bitwarden 加密密钥
     * 这些密钥用于解密从服务器获取的 Vault 数据
     * 
     * @param keys Bitwarden 加密密钥
     */
    fun saveBitwardenCryptoKeys(keys: BitwardenCryptoKeys) {
        // 所有密钥都使用 AES-GCM 加密存储
        val encryptedSymmetricKey = encryptData(keys.symmetricKey)
        val encryptedPrivateKey = keys.privateKey?.let { encryptData(it) }
        
        sharedPreferences.edit()
            .putString(BITWARDEN_MASTER_KEY_HASH_KEY, keys.masterKeyHash)
            .putString(BITWARDEN_SYMMETRIC_KEY_KEY, encryptedSymmetricKey)
            .apply {
                if (encryptedPrivateKey != null) {
                    putString(BITWARDEN_PRIVATE_KEY_KEY, encryptedPrivateKey)
                }
            }
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden crypto keys saved")
    }
    
    /**
     * 获取 Bitwarden 加密密钥
     * 
     * @return BitwardenCryptoKeys 或 null
     */
    fun getBitwardenCryptoKeys(): BitwardenCryptoKeys? {
        return try {
            val masterKeyHash = sharedPreferences.getString(BITWARDEN_MASTER_KEY_HASH_KEY, null)
            val encryptedSymmetricKey = sharedPreferences.getString(BITWARDEN_SYMMETRIC_KEY_KEY, null)
            
            if (masterKeyHash == null || encryptedSymmetricKey == null) {
                return null
            }
            
            val encryptedPrivateKey = sharedPreferences.getString(BITWARDEN_PRIVATE_KEY_KEY, null)
            
            BitwardenCryptoKeys(
                masterKeyHash = masterKeyHash,
                symmetricKey = decryptData(encryptedSymmetricKey),
                privateKey = encryptedPrivateKey?.let { decryptData(it) }
            )
        } catch (e: Exception) {
            android.util.Log.e("SecurityManager", "Failed to get Bitwarden crypto keys", e)
            null
        }
    }
    
    /**
     * 检查是否已连接 Bitwarden
     * 
     * @return true 如果已保存凭据
     */
    fun isBitwardenConnected(): Boolean {
        return sharedPreferences.getBoolean(BITWARDEN_CONNECTED_KEY, false)
    }
    
    /**
     * 检查 Bitwarden Token 是否过期
     * 
     * @return true 如果已过期或未连接
     */
    fun isBitwardenTokenExpired(): Boolean {
        if (!isBitwardenConnected()) return true
        
        val expiry = sharedPreferences.getLong(BITWARDEN_TOKEN_EXPIRY_KEY, 0L)
        // 提前 5 分钟判断为过期，以便有时间刷新
        return System.currentTimeMillis() > (expiry - 5 * 60 * 1000)
    }
    
    /**
     * 更新 Bitwarden Access Token（Token 刷新后调用）
     * 
     * @param newAccessToken 新的 Access Token
     * @param newRefreshToken 新的 Refresh Token（可选）
     * @param newExpiry 新的过期时间
     */
    fun updateBitwardenTokens(
        newAccessToken: String,
        newRefreshToken: String? = null,
        newExpiry: Long
    ) {
        val encryptedAccessToken = encryptData(newAccessToken)
        
        sharedPreferences.edit().apply {
            putString(BITWARDEN_ACCESS_TOKEN_KEY, encryptedAccessToken)
            putLong(BITWARDEN_TOKEN_EXPIRY_KEY, newExpiry)
            
            if (newRefreshToken != null) {
                putString(BITWARDEN_REFRESH_TOKEN_KEY, encryptData(newRefreshToken))
            }
        }.apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden tokens updated, new expiry: $newExpiry")
    }
    
    /**
     * 获取 Bitwarden 用户邮箱
     * 
     * @return 用户邮箱或 null
     */
    fun getBitwardenUserEmail(): String? {
        return sharedPreferences.getString(BITWARDEN_USER_EMAIL_KEY, null)
    }
    
    /**
     * 获取 Bitwarden 服务器 URL
     * 
     * @return 服务器 URL
     */
    fun getBitwardenServerUrl(): String {
        return sharedPreferences.getString(BITWARDEN_SERVER_URL_KEY, "https://vault.bitwarden.com") 
            ?: "https://vault.bitwarden.com"
    }
    
    /**
     * 清除 Bitwarden 凭据（登出时调用）
     * 同时清除所有相关的加密密钥
     */
    fun clearBitwardenCredential() {
        sharedPreferences.edit()
            .remove(BITWARDEN_ACCESS_TOKEN_KEY)
            .remove(BITWARDEN_REFRESH_TOKEN_KEY)
            .remove(BITWARDEN_TOKEN_EXPIRY_KEY)
            .remove(BITWARDEN_USER_EMAIL_KEY)
            .remove(BITWARDEN_USER_ID_KEY)
            .remove(BITWARDEN_MASTER_KEY_HASH_KEY)
            .remove(BITWARDEN_SYMMETRIC_KEY_KEY)
            .remove(BITWARDEN_PRIVATE_KEY_KEY)
            .remove(BITWARDEN_SERVER_URL_KEY)
            .remove(BITWARDEN_CONNECTED_KEY)
            .apply()
        
        android.util.Log.d("SecurityManager", "Bitwarden credential cleared")
    }

    fun putProtectedString(key: String, value: String?) {
        sharedPreferences.edit().apply {
            if (value.isNullOrEmpty()) {
                remove(key)
            } else {
                putString(key, value)
            }
        }.apply()
    }

    fun getProtectedString(key: String): String? {
        return sharedPreferences.getString(key, null)
    }

    fun removeProtectedString(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }
    
    /**
     * 验证 Bitwarden 主密码哈希
     * 用于解锁时验证用户输入的主密码是否正确
     * 
     * @param masterPasswordHash 用户输入的主密码生成的哈希
     * @return true 如果匹配
     */
    fun verifyBitwardenMasterPasswordHash(masterPasswordHash: String): Boolean {
        val storedHash = sharedPreferences.getString(BITWARDEN_MASTER_KEY_HASH_KEY, null)
            ?: return false
        // 恒定时间比较，防时序侧信道（同 verifyMasterPassword）。
        return MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            masterPasswordHash.toByteArray(Charsets.UTF_8)
        )
    }
}
