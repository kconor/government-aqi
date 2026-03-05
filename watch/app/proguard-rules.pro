# Disable aggressive optimizations (inlining, class merging, etc.)
# that break reflection-heavy libraries like WorkManager, Retrofit, Gson.
-dontoptimize

# Keep all app code — only shrink unused library classes and resources.
-keep class com.example.aqi.** { *; }

# Gson TypeToken requires generic signatures
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
