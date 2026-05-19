// ── build.gradle.kts (PROJECT level — the one in the ROOT folder, not app/) ──
// Replace your entire root build.gradle.kts with this
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.application")        version "8.13.2" apply false
    id("org.jetbrains.kotlin.android")   version "1.9.24" apply false
    id("com.google.gms.google-services") version "4.4.2"  apply false
}