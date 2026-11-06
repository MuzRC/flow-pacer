# Kekalkan semua kelas teras FlowPacer daripada dipotong R8
-keep class com.muz.flowpacer.** { *; }

# Elak amaran palsu dari pustaka coroutines
-dontwarn kotlinx.coroutines.**
