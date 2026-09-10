// Root build file.
//
// AGP 9.x bringt "built-in Kotlin" mit: das Plugin `org.jetbrains.kotlin.android` muss daher
// NICHT angewendet werden. Fuer Compose wird nur das Compose-Compiler-Plugin benoetigt.
// Falls dein lokales Setup damit Probleme hat, siehe docs/BUILD.md (Abschnitt "Fallbacks").
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.android) apply false
}
