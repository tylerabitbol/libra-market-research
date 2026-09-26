package com.tylerabitbol.libra

/// Smoke test for the multiplatform wiring: proves every target compiles an
/// actual and that commonMain code can reach platform APIs. Delete once real
/// expect/actual pairs (SecretsStore, app paths) exist.
expect fun platformName(): String
