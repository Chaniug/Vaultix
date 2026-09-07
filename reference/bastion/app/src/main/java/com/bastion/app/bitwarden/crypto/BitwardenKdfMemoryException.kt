package com.bastion.app.bitwarden.crypto

class BitwardenKdfMemoryException(
    val requestedMemoryMb: Int,
    val maxHeapMb: Long,
    val safeLimitMb: Long
) : IllegalStateException(
    "Bitwarden Argon2id KDF memory is too high for Bastion's current Android JVM crypto engine: " +
        "requested=${requestedMemoryMb}MB, safeLimit=${safeLimitMb}MB, heap=${maxHeapMb}MB"
)
