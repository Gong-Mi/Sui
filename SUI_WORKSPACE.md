# Sui workspace inventory

This directory is the unified local workspace for Sui source, build artifacts, checks, flashing inputs, and diagnostics.

- `sui-src/`: upstream Sui source snapshot
- `sui-build/`: v13.6.0 Riru/Zygisk debug and release artifacts
- `sui-check/`: validation copies
- `sui-check2/`: reserved empty validation workspace
- `sui-check3/`: validation artifact copy
- `sui-flash/`: flashing inputs and extracted module
- `sui_build.log`: build log
- `sui_magisk_rish_diagnosis_*.txt`: diagnosis records

Riru and Zygisk, and debug and release, remain separate variants. They are not combined into a single installable module.

The former inner Git metadata is preserved outside this workspace as rollback backups. The outer `Sui` repository tracks the actual files.
