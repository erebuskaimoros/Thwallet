package io.horizontalsystems.walletkit.chain.xrp

import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.entities.Address
import io.horizontalsystems.walletkit.modules.send.address.EnterAddressValidator

class XrpAddressValidator(token: Token) : EnterAddressValidator {
    init {
        require(token.blockchainType == BlockchainType.Ripple && token.type is TokenType.Native) {
            "Only native XRP destinations are supported"
        }
    }

    override suspend fun validate(address: Address) {
        require(address.blockchainType == null || address.blockchainType == BlockchainType.Ripple) {
            "The address belongs to a different blockchain"
        }
        XrpAddressCodec.parse(address.hex)
    }
}
