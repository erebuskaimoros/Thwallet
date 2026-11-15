# Session Index

## Recent Sessions

| Session | Focus | Summary | Record |
|---------|-------|---------|--------|
| 2026-11-14 #1 | November upstream synchronization | Merged 557 upstream commits on `upstream-sync`, keeping the native THORChain provider and adopting upstream XRP. | [Session 1](2026-11-14/session-1.md) |
| 2026-08-15 #1 | Six-chain EVM expansion | Added and verified six EVM mainnets across wallet, signing, fee, and provider paths. | [Session 1](2026-08-15/session-1.md) |

## Current Work In Progress

- `upstream-sync` and the three kit fork branches are pushed and JitPack has
  built the pinned kit commits. Remaining before merging into `master`: a
  clean build without mavenLocal, lint, a release build and a device smoke test.
- iOS converged on upstream's `xrp` identifier on 2026-11-15 (its `upstream-sync`
  branch passes iOS CI); `xrp-support` is superseded on both platforms. Android
  still needs comparing against iOS's ZEC-destination guard and WalletConnect
  EVM parsing rules.
