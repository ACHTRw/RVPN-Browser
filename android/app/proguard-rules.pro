# ---------------------------------------------------------------------------
# RVPN Browser - R8 / ProGuard rules
#
# The app uses no reflection, so the only critical rules are the ones that keep
# the gomobile binding intact: the Go runtime resolves its Java methods through
# JNI by *name* (Java_org_rvpnbrowser_core_rvpncore_Rvpncore_<method>), so any
# renaming or removal of that class breaks the engine at runtime.
# ---------------------------------------------------------------------------

# gomobile binding (org.rvpnbrowser.core.rvpncore.Rvpncore + Seq/Universe helpers)
-keep class org.rvpnbrowser.core.rvpncore.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Never strip the runtime entry points that the framework instantiates by name.
-keep class com.rvpnbrowser.browser.MainActivity { *; }
-keep class com.rvpnbrowser.browser.RvpnSettingsActivity { *; }
-keep class com.rvpnbrowser.browser.RvpnService { *; }
-keep class com.rvpnbrowser.browser.BootReceiver { *; }
-keep class com.rvpnbrowser.browser.RvpnApp { *; }

# org.json is part of the platform; the models are plain data holders.
-keepclassmembers class com.rvpnbrowser.browser.** {
    <init>(...);
}

# Keep the annotations that R8 would otherwise report as missing at compile time.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# Useful crash reports for a release build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
