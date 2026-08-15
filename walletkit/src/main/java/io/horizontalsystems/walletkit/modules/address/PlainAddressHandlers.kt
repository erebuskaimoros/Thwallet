package io.horizontalsystems.walletkit.modules.address

import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.core.chain.ChainRegistry
import io.horizontalsystems.walletkit.core.isEvm

/**
 * Plain (non-domain) address handlers per blockchain — the single source of truth used by
 * both [AddressHandlerFactory] and [AddressInputModule].
 */
fun plainAddressHandlers(blockchainType: BlockchainType): List<IAddressHandler> =
    if (blockchainType.isEvm) {
        listOf(AddressHandlerEvm(blockchainType))
    } else when (blockchainType) {

        BlockchainType.Tron -> listOf(AddressHandlerTron())

        else -> ChainRegistry[blockchainType]?.addressHandlers().orEmpty()
    }
