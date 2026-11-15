# R8 rules for Tiiny Chat release builds.
# kotlinx.serialization, OkHttp, coroutines and Compose ship their own consumer rules.

# Keep line numbers for readable crash stack traces (no obfuscation mapping needed to read them).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# jsoup references optional libraries that are not on the classpath.
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**

# OkHttp optional TLS providers (not used on Android).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
