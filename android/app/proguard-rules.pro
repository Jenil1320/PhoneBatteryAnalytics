# Keep release stack traces readable once Play has the matching mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Components the platform instantiates by name ─────────────────────────────
# The manifest and WorkManager both name these as strings, so R8 cannot see the
# reference and would otherwise be free to rename or remove them. A stripped
# Worker fails at runtime with "Could not instantiate", and only in release —
# exactly the build nobody debugs.
-keep class io.github.jenil1320.cellspan.CellspanApp { *; }
-keep class io.github.jenil1320.cellspan.MainActivity { *; }
-keep class io.github.jenil1320.cellspan.data.PowerReceiver { *; }
-keep class io.github.jenil1320.cellspan.data.BootReceiver { *; }
-keep class io.github.jenil1320.cellspan.data.BatteryGuardService { *; }

# WorkManager reflects on Worker subclasses by their fully-qualified name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Hidden platform APIs reached by reflection ───────────────────────────────
# PowerProfileReader looks these up by string. They live in the framework, not in
# the APK, so R8 cannot strip them — but keeping the rule documents the dependency
# and stops a future -dontwarn sweep hiding a genuine failure.
-dontwarn com.android.internal.os.PowerProfile
-keepclassmembers class io.github.jenil1320.cellspan.data.PowerProfileReader { *; }

# ── Data classes read back from JSON on import ───────────────────────────────
# org.json is part of the platform; nothing to keep, but the import path relies on
# these field names surviving, which they do because they are only ever strings.
-dontwarn org.json.**
