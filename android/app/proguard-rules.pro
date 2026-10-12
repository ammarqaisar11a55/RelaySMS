# RelaySMS release rules. Libraries ship their own consumer rules (Room, OkHttp, kotlinx
# serialization, CameraX, WorkManager); only what they do not cover is listed here.

# Tink serialises keysets with shaded protobuf-lite, which reads message fields by reflection.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# Optional dependencies referenced by Tink and OkHttp that are not on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable stack traces in bug reports written by the user; no source file names are needed.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
