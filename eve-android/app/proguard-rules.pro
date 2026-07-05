# Keep Chaquopy Python bridge classes
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep EVE agent classes referenced from Python via jclass()
-keep class com.eve.agent.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
