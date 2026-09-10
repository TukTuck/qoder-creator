# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# androidx.webkit / browser
-dontwarn androidx.webkit.**
-dontwarn androidx.browser.**

# Unsere Regelwerk-Modelle werden per org.json gelesen (keine Reflection, aber sicherheitshalber):
-keep class app.consolepocket.cache.Rule { *; }
-keep class app.consolepocket.cache.MatchSpec { *; }
