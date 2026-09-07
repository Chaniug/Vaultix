package io.vaultix.common

/**
 * 统一异常映射：加密/网络层抛具体异常 → 上层统一映射为 [Result] 或 sealed error。
 * 依据 Docs/11 异常处理规范：禁止吞异常。
 */
inline fun <T> safeCall(block: () -> T): Result<T> = runCatching(block)

object VaultixBuild {
    const val APP_ID = "io.vaultix"
    const val APP_NAME = "Vaultix"
}
