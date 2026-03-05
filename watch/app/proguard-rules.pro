# Keep all app classes — let R8 only shrink unused library code and resources.
-keep class com.example.aqi.** { *; }

# WorkManager uses Room internally; keep the generated database impl.
-keep class androidx.work.impl.** { *; }

# Gson TypeToken requires generic signatures to be preserved
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
