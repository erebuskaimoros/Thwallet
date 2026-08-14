# Thwallet Knowledge Index

## Identity

- Local path: `/Users/boonewheeler/Desktop/Projects/THORChain/Wallet`
- Origin: `https://github.com/erebuskaimoros/Thwallet.git`
- Upstream: `https://github.com/horizontalsystems/unstoppable-wallet-android.git`
- Stack: Kotlin, Android, Jetpack Compose, Gradle, JDK 17

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
