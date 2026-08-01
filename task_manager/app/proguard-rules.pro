# OSMDroid tile sources use reflection
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# Keep Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Keep StateFlow used by LocationAlarmService companion
-keep class com.example.taskmanager.LocationAlarmService$Companion { *; }
