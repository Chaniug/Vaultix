package com.bastion.app.bitwarden.service

import com.bastion.app.logging.runCatchingObserved
import android.util.Log
import com.bastion.app.bitwarden.api.CipherApiResponse
import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey

internal object BitwardenCipherKeyResolver {

    suspend inline fun <T> withCipherKey(
        cipher: CipherApiResponse,
        vaultKey: SymmetricCryptoKey,
        logTag: String,
        block: suspend (SymmetricCryptoKey) -> T
    ): T {
        val effectiveKey = resolveCipherKey(cipher, vaultKey, logTag)
        return try {
            block(effectiveKey)
        } finally {
            clearIfDerived(effectiveKey, vaultKey)
        }
    }

    fun resolveCipherKey(
        cipher: CipherApiResponse,
        vaultKey: SymmetricCryptoKey,
        logTag: String
    ): SymmetricCryptoKey {
        val encryptedItemKey = cipher.key?.takeIf { it.isNotBlank() } ?: return vaultKey
        return runCatchingObserved {
            BitwardenCrypto.decryptSymmetricKey(encryptedItemKey, vaultKey)
        }.getOrElse { error ->
            Log.w(
                logTag,
                "Failed to decrypt cipher key for ${cipher.id} (organization=${cipher.organizationId != null}): ${error.javaClass.simpleName}"
            )
            vaultKey
        }
    }

    fun clearIfDerived(
        effectiveKey: SymmetricCryptoKey?,
        vaultKey: SymmetricCryptoKey?
    ) {
        if (effectiveKey != null && vaultKey != null && effectiveKey !== vaultKey) {
            effectiveKey.clear()
        }
    }
}
