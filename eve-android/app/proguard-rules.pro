# Keep Chaquopy Python bridge classes
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep EVE agent classes referenced from Python via jclass()
# VirtualComputer.getInstance() and EveKotlinBridge.on*() are called by Python.
-keep class com.eve.agent.** { *; }
-keepclassmembers class com.eve.agent.EveKotlinBridge {
    public static *;
}
-keepclassmembers class com.eve.agent.VirtualComputer {
    public static *;
    public *;
}

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
