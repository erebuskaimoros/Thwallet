package io.horizontalsystems.walletkit.chain.xrp

import io.horizontalsystems.hdwalletkit.Curve
import io.horizontalsystems.hdwalletkit.HDWallet

object XrpKeyDerivation {
    const val coinType = 144

    /** Derives exactly m/44'/144'/0'/0/0. */
    fun privateKey(seed: ByteArray): ByteArray {
        val key = HDWallet(seed, coinType, HDWallet.Purpose.BIP44, Curve.Secp256K1)
            .privateKey(0, 0, true)
            .privKeyBytes
        return when {
            key.size == 32 -> key
            key.size == 33 && key[0] == 0.toByte() -> key.copyOfRange(1, 33)
            else -> throw IllegalStateException("Unexpected secp256k1 private-key length")
        }
    }

    fun address(seed: ByteArray): String = XrpAddressCodec.classicAddress(privateKey(seed))
}
