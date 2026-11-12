# The Matter library is reached from native code by name; keep it whole.
-keep class chip.** { *; }
-keep class matter.** { *; }
-dontwarn chip.**
-dontwarn matter.**
