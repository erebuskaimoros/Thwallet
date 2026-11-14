# Session 1 - November upstream synchronization

> Date: 2026-11-14
> Focus: Triage and merge 557 upstream commits, keeping Thwallet policy

## Summary

Reviewed everything upstream Unstoppable shipped since August, then merged
`upstream/master` through `be15af281` into a new `upstream-sync` branch. The
merge brings upstream's security work while keeping the native, affiliate-free
THORChain provider; the debug app builds and 537 of 538 unit tests pass.

## Work Done

- Read all 557 upstream commits and classified the security-relevant ones
  (clipboard expiry, WalletConnect lock/deleted-account fixes, signing
  summaries, account binding, in-process Tor, backup key encoding, Zcash SDK
  checksums, swap memo-destination validation).
- Created `upstream-sync` from `master`, merged upstream and resolved all 26
  conflicts.
- Kept `ThorChainProvider`/`BaseThorChainProvider` by three-way merging them
  against upstream's last version (`920774bb5^`), restored the
  `ChainPlugin.swapProviders()` hook, and removed the
  `USwapProvider(UProvider.ThorChain)` registry entry and its retired stub.
- Adopted upstream's XRP module (`xrp` UID); left `xrp-support` unmerged.
- Moved Robinhood Chain from `EvmNetworkCatalog` to upstream's
  `BlockchainType.RobinhoodChain` (same `robinhood` UID, chain ID 4663).
- Ported the Dogecoin adapter, the EVM fee service's explicit-gas-limit path
  and `EvmMantleGasDataService` (plus their tests) from RxJava to coroutines.
- Rebased the three kit forks onto the upstream commits the app pins, on local
  `upstream-sync` branches, and published them to mavenLocal:
  bitcoin-kit `a3db60f4`, ethereum-kit `8048cb15`, market-kit `c7587748`.
- Regenerated `chain-behavior-parity.txt` for the five catalog chains and
  Dogecoin on top of upstream's fixture.
- Recorded decisions in `knowledge/README.md` and the shared workspace wiki.

## Discoveries

- Upstream deleted its native THORChain and Maya providers on 2026-10-15 and
  quotes every venue through its own USwap backend. (Promoted to
  `knowledge/README.md`.)
- Upstream's route score is now server-published and keyed by provider id, so
  the native `thorchain` provider shows none unless the backend lists that id.
- Upstream removed RxJava from the app and every kit; any Thwallet kit fork
  must sit on the coroutine-based upstream kit before the app compiles.
- bitcoin-kit upstream took Room version 20, so the fork's Dogecoin
  `isCoinbase` migration is now 20→21 and tolerates a pre-existing column.
- ethereum-kit fork: `SourceType.apiBaseUrl`/`apiKeys` exist only on
  `Etherscan`; `RpcOnly` carries just `txBaseUrl`.
- Upstream's own `NearAddressAndTokenTest.rpcSourcesFollowTheKitFailoverOrder`
  fails against the pinned NEAR kit; the module is identical to upstream.
- Building needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17`; the system default
  Java is 1.8.

## Files Changed

| File | Change |
|------|--------|
| 937 files via merge | Upstream `master` through `be15af281` |
| `gradle/libs.versions.toml` | Upstream pins plus the three rebased fork SHAs |
| `walletkit-chain-thorchain/.../BaseThorChainProvider.kt`, `ThorChainProvider.kt` | Kept, with upstream memo validation merged in |
| `walletkit/.../core/chain/ChainPlugin.kt`, `MultiSwapProviderRegistry.kt` | Restored provider hook; THORChain quoted natively only |
| `walletkit/.../core/ethereum/EvmNetworkCatalog.kt` and call sites | Robinhood Chain moved to upstream's type |
| `walletkit-chain-evm/.../evmfee/*` | Explicit gas limit and Mantle fees on coroutines |
| `walletkit-chain-bitcoin/.../DogecoinAdapter.kt` | Flow emitters in place of Rx subjects |
| `walletkit/.../USwapProvider.kt` | Shared chain-id map with Dogecoin and catalog chains |
| `app/src/test/resources/chain-behavior-parity.txt` | Regenerated |
| `knowledge/README.md` | November synchronization section and new pins |

## In Progress

- The kit forks' `upstream-sync` branches were pushed at the end of the session
  and JitPack built all three pinned commits. A wallet build that resolves them
  from JitPack rather than mavenLocal has not been run yet.
- `upstream-sync` is not merged into `master`.
- iOS has not yet converged on the `xrp` identifier (open parity gap).

## Next Steps

- [ ] Confirm a clean wallet build that resolves the kits from JitPack, not mavenLocal
- [ ] Run lint, a release (R8) build and a device smoke test of send, swap and WalletConnect
- [ ] Decide whether to fix or skip upstream's failing NEAR RPC-order test
- [ ] Record the `xrp` identifier decision in the iOS repository and port it
- [ ] Merge `upstream-sync` into `master`; retire `xrp-support` once agreed
