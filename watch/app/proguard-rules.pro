# Gson uses reflection to serialize/deserialize. Keep model classes.
-keep class com.example.aqi.data.SensorData { *; }
-keep class com.example.aqi.data.MasterDataPayload { *; }

# Retrofit needs the interface methods kept for proxy generation.
-keep,allowobfuscation interface com.example.aqi.data.AqiApi

# Gson @SerializedName annotations
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
