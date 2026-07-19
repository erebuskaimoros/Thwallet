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

Relevant source:

- `app/src/main/java/io/horizontalsystems/bankwallet/modules/multiswap/providers/ThorChainProvider.kt`
- `app/src/main/java/io/horizontalsystems/bankwallet/modules/multiswap/providers/BaseThorChainProvider.kt`
- `app/src/test/java/io/horizontalsystems/bankwallet/modules/multiswap/providers/ThorChainAffiliatePolicyTest.kt`

## Progressive disclosure

Start with the shared THORChain workspace [project page](../../knowledge/projects/thwallet.md),
then the [frontend/app-layer workstream](../../knowledge/workstreams/frontends-and-app-layer.md).
Open only the relevant section of the [shared reference base](../../knowledge/reference-base.md)
when a cross-project source or API reference is needed.
