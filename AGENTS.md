# Thwallet Agent Notes

## Project identity

- This checkout owns the Android Thwallet product line.
- It is a public GitHub fork of `horizontalsystems/unstoppable-wallet-android`.
- `origin` is `https://github.com/erebuskaimoros/Thwallet.git` and is the only
  push target.
- `upstream` is fetch-only. Never push to Horizontal Systems.
- Preserve the MIT license, upstream Git history, and attribution.
- Thwallet is independent and must not claim official THORChain, Nine Realms,
  or Horizontal Systems endorsement.

## Product policy

- Direct THORChain swaps must not charge a wallet affiliate fee.
- `walletkit-chain-thorchain`'s `ThorChainProvider` must supply null values for
  both `affiliate` and `affiliateBps`; Retrofit then omits both quote parameters
  and THORChain builds a fee-free memo.
- Keep `ThorChainAffiliatePolicyTest` green. Treat any upstream change that
  reintroduces either parameter as a release blocker.
- Do not describe swaps as entirely fee-free: network, liquidity, outbound,
  slip, and gas costs still apply.
- Other swap providers are outside this narrow policy until they are audited
  and explicitly brought into scope.

## Security and releases

- Treat changes to key derivation, seed storage, signing, transaction assembly,
  destination addresses, quote validation, and dependency sources as
  security-critical.
- Never commit mnemonics, private keys, signing stores, credentials, or live
  service secrets.
- Inherited GitHub release and deployment workflows target upstream-owned
  services. Keep GitHub Actions disabled until Thwallet-owned CI and secrets are
  configured.
- Do not publish an APK or app-store bundle before the package ID, deep links,
  service endpoints, branding, signing identity, privacy policy, and
  reproducible-build process are independently reviewed.

## Build and test

Use JDK 17 and the checked-in Gradle wrapper.

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

- Unit tests live under each module's `src/test/` tree.
- Instrumentation tests live under each Android module's `src/androidTest/` tree.
- Prefer Kotlin coroutines and Flow for new reactive code.
- Prefer `uiv3/` for new Compose components.
- Use imperative commit messages without type prefixes; keep the summary at or
  below 60 characters.

## Shell safety

- In zsh snippets, do not use `path` or `status` as local variable names. They
  are special shell parameters; use task-specific names such as `target_path`
  or `http_code` instead.

## Upstream synchronization

1. Fetch `upstream` and inspect its changes before merging or rebasing.
2. Re-run the affiliate-policy test and inspect every THORChain quote/memo path.
3. Review wallet/security changes separately from UI or translation changes.
4. Record material architecture or policy changes in `knowledge/README.md` and
   the shared THORChain workspace wiki.
