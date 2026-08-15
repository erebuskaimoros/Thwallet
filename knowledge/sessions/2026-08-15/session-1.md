# Session 1 - Six-chain EVM expansion

> Date: 2026-08-15
> Focus: Add six Vultisig-aligned EVM networks to Thwallet

## Summary

Expanded Thwallet with Cronos, Blast, Mantle, Sei EVM, HyperEVM, and Robinhood
Chain using the existing EthereumKit architecture. The work adds safe market
metadata overlays, chain-specific RPC/history and fee handling, persistence and
cleanup coverage, and regression tests without introducing Trust Wallet Core.

## Papercuts

- Resolved: `pc-20260815-012756-d3b806` and
  `pc-20260815-010114-b9cb2e` by documenting zsh's reserved `path` and `status`
  parameters in `AGENTS.md` and verifying the recommended `http_code` pattern.
- Deferred: `pc-20260815-010014-eb89df`,
  `pc-20260814-210221-6f3e64`, and `pc-20260814-192611-302c82` because a durable
  project-local JDK selection needs a separate cross-machine toolchain decision;
  `pc-20260814-192511-3157d0` because the upstream-whitespace condition is not
  part of the current feature delta and needs separate reproduction.
- Remaining open: 4

## Work Done

- Added local catalog and native-token overlays for six EVM mainnets.
- Forked and pinned EthereumKit for the six closed-enum chain definitions,
  RPC-only transaction support, and Mantle fee estimation.
- Wired derivation, balances, native and EIP-20 wallets, send/receive,
  WalletConnect, RPC/history providers, explorer links, swap identifiers, and
  account cleanup.
- Added Mantle and OP-stack fee regressions, including explicit gas-limit and
  send-all safety coverage.
- Verified full walletkit, EVM, and app unit suites; THORChain's zero-affiliate
  policy; debug assembly; app lint; and EVM-module lint.

## Discoveries

- EthereumKit's signing path uses a closed `Chain` enum, so adding chains
  requires a pinned fork or vendoring; MarketKit can remain upstream by applying
  a Thwallet-owned compatibility overlay.
- HyperEVM's persistent blockchain UID is `hyperevm`, while the native HYPE coin
  UID is `hyperliquid`; conflating them would break future market-data matching.
- Mantle's total-fee RPC estimates execution gas internally, so additional-fee
  decomposition must subtract the estimated usage rather than the padded signing
  ceiling.
- Provider-supplied gas limits must still pass through specialized rollup fee
  services or swaps can under-reserve L1/additional fees.

## Files Changed

| File | Change |
|------|--------|
| `walletkit/.../EvmNetworkCatalog.kt` | Added six-chain metadata, native tokens, and safe token normalization. |
| `walletkit/.../MarketKitWrapper.kt` | Overlaid local chain/token metadata across lookup paths. |
| `walletkit-chain-evm/.../EvmKitManager.kt` | Mapped the six networks to EthereumKit signing chains. |
| `walletkit-chain-evm/.../EvmSyncSourceKitMapping.kt` | Added transaction-history providers. |
| `walletkit-chain-evm/.../evmfee/` | Added Mantle and OP-stack fee handling and regressions. |
| `walletkit/.../EvmSyncSourceManager.kt` | Added the six mainnet RPC defaults. |
| `walletkit/.../MarketKitExtensions.kt` | Added EVM capability and display gates. |
| `app/src/test/` and module test trees | Added chain parity, persistence, cleanup, and fee coverage. |
| `gradle/libs.versions.toml` | Pinned the Thwallet EthereumKit fork commit. |
| `knowledge/README.md` | Recorded architecture, persistent IDs, and provider limitations. |
| `AGENTS.md` | Added zsh reserved-variable guidance. |

## In Progress

None - session complete

## Next Steps

- [ ] Add dedicated chain icons and translated descriptions.
- [ ] Evaluate an authenticated Cronos indexer for incoming history.
- [ ] Audit swap-backend coverage for all six official chain IDs.
- [ ] Complete the broader release security and reproducible-build review.
