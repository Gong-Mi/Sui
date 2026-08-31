# Sui build artifacts

This directory is the single local management repository for the Sui v13.6.0 build artifacts.

Variants:

- `sui-riru-v13.6.0-debug/` — Riru debug module
- `sui-riru-v13.6.0-release/` — Riru release module
- `sui-zygisk-v13.6.0-debug/` — Zygisk debug module
- `sui-zygisk-v13.6.0-release/` — Zygisk release module
- `sui-zygisk-v13.6.0-release-flashed.zip` — separately retained flashed ZIP

Riru and Zygisk are alternative module variants and must not be merged into one installable ZIP. Debug and release artifacts are also kept separate.

`ARTIFACTS.json` records the variant identity, file counts, sizes, and SHA-256 values generated from the files currently present in this directory.

This repository is local-only. No upstream remote is configured.
