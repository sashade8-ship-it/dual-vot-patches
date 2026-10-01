/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3337
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.statusbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal

/**
 * Present in all supported versions. 21.30+ inlines the flag check in many places.
 */
internal object StatusBarInsetsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45400535L)
    )
)
