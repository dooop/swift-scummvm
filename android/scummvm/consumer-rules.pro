# The native engine resolves these types and members by name over JNI, so they
# must survive shrinking and obfuscation in the consuming app.
-keep class org.scummvm.scummvm.ScummVM { *; }
-keep class * extends org.scummvm.scummvm.ScummVM { *; }
-keep class org.scummvm.scummvm.SAFFSTree { *; }
-keep class org.scummvm.scummvm.SAFFSTree$* { *; }

-keepclasseswithmembernames class org.scummvm.scummvm.** {
    native <methods>;
}

# jni-android.cpp calls back into anything tagged @Keep.
-keep @androidx.annotation.Keep class org.scummvm.scummvm.** { *; }
-keepclassmembers class org.scummvm.scummvm.** {
    @androidx.annotation.Keep *;
}
