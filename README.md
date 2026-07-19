# Thwallet

Thwallet is an experimental, community-maintained Android wallet focused on
THORChain-native self-custody and swaps.

The project is derived from the MIT-licensed
[Unstoppable Wallet Android](https://github.com/horizontalsystems/unstoppable-wallet-android)
codebase. Its GitHub fork relationship and `upstream` Git remote preserve that
provenance and provide a path for security updates.

## Current status

Thwallet is in its initial bootstrap phase. It has not yet completed its
independent package-ID, branding, signing, reproducible-build, or security-review
work and should not be treated as a production release.

The first product policy is implemented in source:

- direct THORChain quote requests omit `affiliate` and `affiliate_bps`
- therefore THORChain-generated transaction memos contain no wallet affiliate fee
- users still pay unavoidable network, outbound, liquidity, slip, and gas costs

This policy currently applies to the native THORChain provider. Other swap
providers have their own fee models and must be evaluated separately.

## Development

The app is implemented in Kotlin with Jetpack Compose and supports Android 8.1
and later.

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

Project-specific contribution and security rules are in [AGENTS.md](./AGENTS.md).

## Independence and attribution

Thwallet is not an official THORChain, Nine Realms, or Horizontal Systems
product. The current user interface still contains upstream Unstoppable Wallet
branding while the independent identity is designed. Do not distribute builds
under either project's name or signing identity.

## License

The inherited source is available under the [MIT License](./LICENSE). Preserve
the license and upstream attribution when redistributing substantial portions of
the software.
