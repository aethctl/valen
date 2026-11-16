# SPDX-License-Identifier: GPL-3.0-only
#
# R8 for the release build. The goal is speed (Compose and the other libraries optimised), not a
# small or hidden app, so nothing is renamed and Valen's own code is kept as written: page bridges
# are called from JavaScript by name, serializers and the vendored Orpheon code are reached by
# reflection, and a stack trace from a phone should read like the source.

-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions

# Valen, including the vendored Orpheon modules.
-keep class app.valen.** { *; }

# Methods a WebView page calls.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# NewPipe Extractor runs YouTube's player code in Rhino, which loads its classes by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
