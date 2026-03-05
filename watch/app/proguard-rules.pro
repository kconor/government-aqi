# WorkManager uses Room internally; keep the generated database impl.
-keep class androidx.work.impl.** { *; }

# Gson uses reflection to serialize/deserialize. Keep model classes.
-keep class com.example.aqi.data.SensorData { *; }
-keep class com.example.aqi.data.MasterDataPayload { *; }
-keep class com.example.aqi.data.ForecastPayload { *; }
-keep class com.example.aqi.data.ForecastLocation { *; }
-keep class com.example.aqi.data.ForecastDay { *; }

# Retrofit needs the interface methods kept for proxy generation.
-keep,allowobfuscation interface com.example.aqi.data.AqiApi

# Gson @SerializedName annotations
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson TypeToken requires generic signatures to be preserved
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
