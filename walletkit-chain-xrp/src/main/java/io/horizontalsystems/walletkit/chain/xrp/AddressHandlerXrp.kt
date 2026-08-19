package io.horizontalsystems.walletkit.chain.xrp

import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.entities.Address
import io.horizontalsystems.walletkit.modules.address.IAddressHandler

/** Recognizes checksum-valid XRPL mainnet classic and X-addresses. */
class AddressHandlerXrp : IAddressHandler {
    override val blockchainType: BlockchainType = BlockchainType.Ripple

    override fun isSupported(value: String): Boolean = try {
        XrpAddressCodec.parse(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun parseAddress(value: String): Address {
        XrpAddressCodec.parse(value)
        return Address(value, blockchainType = blockchainType)
    }
}
