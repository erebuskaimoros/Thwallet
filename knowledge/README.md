# Thwallet Knowledge Index

## Identity

- Local path: `/Users/boonewheeler/Desktop/Projects/THORChain/Wallet`
- Origin: `https://github.com/erebuskaimoros/Thwallet.git`
- Upstream: `https://github.com/horizontalsystems/unstoppable-wallet-android.git`
- Stack: Kotlin, Android, Jetpack Compose, Gradle, JDK 17

## Cross-platform product policy

Thwallet is maintained as a two-platform product. This Android repository is
paired with the Swift iOS fork at
`https://github.com/erebuskaimoros/Thwallet-iOS`. New blockchain, protocol,
swap, persistence, and wallet-format work is cross-platform by default. A
feature is not considered complete until equivalent security and user behavior
is tested and pushed on both platforms, unless an intentional exception is
recorded in both repositories.

Parity means matching persisted identifiers, derivation paths, address and fee
policies, quote validation, transaction safety, and user-visible capabilities;
it does not require Kotlin and Swift to share internal structure. During
upstream synchronization, audit whether either platform gained or lost a
capability and record temporary divergence explicitly.

## Current product policy

The direct THORChain provider sends neither `affiliate` nor `affiliate_bps` in
quote requests. THORChain therefore returns a swap memo without a wallet
affiliate fee. Network and protocol costs remain.

This checkout includes upstream's native THORChain and Maya wallet support:
RUNE and CACAO address derivation, balance synchronization, send/receive,
native swap deposits, transaction history, THORChain secured assets, and
automatic discovery of held THORChain-family assets. Maya remains outside the
narrow zero-affiliate policy and retains its independent provider configuration.

## Architecture

The August 2026 upstream synchronization moved shared wallet behavior from the
Android app module into `walletkit` and chain-specific plugin modules. THORChain
and Maya behavior now lives in `walletkit-chain-thorchain`, backed by
`horizontalsystems/thorchain-kit-android`. The Android `app` module is primarily
the product composition root.

## Six-chain EVM expansion

Thwallet's local EVM catalog adds Cronos (25), Blast (81457), Mantle (5000),
Sei EVM (1329), HyperEVM (999), and Robinhood Chain (4663). The catalog owns
the chain metadata and native-token fallbacks, while `MarketKitWrapper`
normalizes compatible remote token rows and rejects legacy contract rows whose
decimals are unknown. This keeps EVM-specific catalog and normalization logic
out of the MarketKit fork.

The persisted platform identifiers are deliberate: HyperEVM uses `hyperevm`
while its HYPE coin uses `hyperliquid`; Sei EVM retains MarketKit's current
`sei-network` identifier even though newer CoinGecko data calls the platform
`sei-v2`. Do not rename either stored chain UID without an alias/migration layer,
because enabled-wallet and backup records embed it in `TokenQuery.id`.

MarketKit is no longer entirely upstream. The build pins the Thwallet-owned
MarketKit fork at `7e7e05d4118945efca68d4545afcb5c1933e1e5b`; that fork adds only
first-class Dogecoin metadata. The six-chain EVM catalog and its data
normalization remain a local Thwallet overlay rather than MarketKit fork
changes.

EthereumKit's chain model is a closed enum used by signing and transaction
assembly, so the build pins the Thwallet-owned `ethereum-kit-android` fork at
`56bd013fcdbd93762f73edc152c347da5d12ff7e`. The fork adds the six chain IDs,
RPC-only history support, and Mantle's additional L1-data/operator fee model.
The app supplies its own RPC and history-provider configuration.

All six networks support EVM derivation, balances, native and EIP-20 wallets,
send/receive, WalletConnect chain recognition, and explorer links. Cronos is
currently RPC-only because its current explorer API requires credentials, so
incoming indexed history is unavailable there. Indexed history and swap routes
on every new network still depend on the configured third-party provider or
swap backend advertising that chain. This expansion is not by itself a release
security review.

## Native Dogecoin implementation

Dogecoin is implemented as a native-only chain through the Thwallet-owned
`bitcoin-kit-android` fork pinned at
`42d8991111fc7336bc05a31cbbdb0ebfd1280f73`. This immutable fork commit adds
native DOGE consensus, persistence, signing, and transport support. Wallet
derivation uses BIP44 coin type 3 and P2PKH receive/change addresses. A valid
Dogecoin P2SH address is accepted
as a send destination, but single-address watch wallets deliberately accept
P2PKH only. Extended public keys retain Bitcoin `xpub` version bytes while
using the coin-type-3 path; there is no first-class `dpub` export, so callers
must verify interoperability instead of assuming every Dogecoin tool will
import the exported key.

The native kit validates Dogecoin's Scrypt proof of work, merged-mining AuxPoW
commitments, DigiShield and legacy difficulty rules, contextual median-time and
activation rules, cumulative chainwork, and pinned checkpoints. Full sync uses
different safe starting points by wallet origin: newly created wallets use the
`RecentPinned` checkpoint, while restored and watch wallets use the older
`Bip44` checkpoint so historical funds are discoverable. Blockchair and other
API-assisted sync modes trust an external indexer for history and can misreport
balances or confirmations; they do not provide the same verification model as
peer-backed full sync.

The future-timestamp ceiling currently uses the Android device clock. The
shared Bitcoin core has no bounded, median peer-time service equivalent to
Dogecoin Core's adjusted network time, so a device clock more than roughly two
hours behind can stall otherwise valid peer sync. Peer timestamps cannot loosen
the ceiling, but releases should still test clock-skew behavior explicitly.

Spend selection applies Dogecoin's height-aware coinbase maturity: 30 blocks
before the DigiShield activation height and 240 blocks from that activation
onward, based on the source coinbase height. Network parsing enforces
Dogecoin's 4 MB serialized-block limit and rejects negative or oversized peer
payload lengths before allocation. Transaction policy also enforces a hard
100-koinu-per-byte fee floor, a 1,000-koinu-per-byte recommendation, a
100,000-koinu hard dust floor, and a 1,000,000-koinu soft dust threshold.
Ambiguous API or peer broadcast outcomes do not invalidate the local
transaction or release its inputs, and retry loops are bounded.

UTXO selection uses checked, fail-closed arithmetic for input aggregation,
value and fee calculations, transaction-size and change-size fee products,
and remainder calculations. Dust equality is spendable, while change dust is
evaluated against the actual change script type, including change returned to
the first input's script type.

Direct THORChain DOGE swaps preserve Thwallet's zero-wallet-affiliate policy:
the quote request omits both affiliate fields. They are not cost-free; normal
network, liquidity, outbound, slip, and gas costs remain. UTXO quotes fail
closed unless `gas_rate_units` is exactly `satsperbyte` (case-insensitive) and
the recommended rate is in the validated 1...1,000,000 range. The quote must
also provide a positive `dust_threshold` no greater than 1,000,000,000 base
units. OpenCryptoPay DOGE is
intentionally disabled until that protocol documents Dogecoin transaction
support. uSwap exposes the Dogecoin chain mapping, but an executable route still
depends on the provider advertising availability.

This implementation is not ready for production distribution. Before release,
validate live mainnet tip sync, testnet sync, and full historical restoration;
exercise adversarial reorg behavior; run Android instrumentation coverage;
establish reproducible builds; and obtain an independent security review of
derivation, parsing, consensus validation, transaction construction, signing,
and broadcasting.

## August 2026 synchronization baseline

Thwallet merged upstream `master` through `1f50b1fb8`. Debug assembly and the
complete unit-test suite pass, including the zero-affiliate guard. App lint and
the THORChain plugin lint also pass. Root `lint` still fails in unchanged
upstream code: `components:chartview` has eight API-level findings and the new
standalone `walletkit` module reports inherited permission/resource findings.
Do not describe the repository as fully lint-green until upstream's module
baseline is repaired or intentionally baselined.

Relevant source:

- `walletkit-chain-thorchain/src/main/java/io/horizontalsystems/walletkit/modules/multiswap/providers/ThorChainProvider.kt`
- `walletkit-chain-thorchain/src/main/java/io/horizontalsystems/walletkit/modules/multiswap/providers/BaseThorChainProvider.kt`
- `walletkit-chain-thorchain/src/main/java/io/horizontalsystems/walletkit/core/adapters/ThorchainAdapter.kt`
- `walletkit-chain-thorchain/src/test/java/io/horizontalsystems/walletkit/modules/multiswap/providers/ThorChainAffiliatePolicyTest.kt`

## Progressive disclosure

Start with the shared THORChain workspace [project page](../../knowledge/projects/thwallet.md),
then the [frontend/app-layer workstream](../../knowledge/workstreams/frontends-and-app-layer.md).
Open only the relevant section of the [shared reference base](../../knowledge/reference-base.md)
when a cross-project source or API reference is needed.
