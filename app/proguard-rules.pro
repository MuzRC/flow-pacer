# Pastikan coroutines dan pantulan rangkaian tidak dikacau
-keep class com.muz.flowpacer.** { *; }
-dontwarn kotlinx.coroutines.**
