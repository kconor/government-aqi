# Keep metadata used by Gson/coroutines reflection.
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# Gson reflection on generic types and @SerializedName fields.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.example.aqi.AppLog { *; }
-keep class com.example.aqi.data.MasterDataPayload { *; }
-keep class com.example.aqi.data.SensorData { *; }
-keep class com.example.aqi.data.ForecastPayload { *; }
-keep class com.example.aqi.data.ForecastLocation { *; }
-keep class com.example.aqi.data.ForecastDay { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room / WorkManager startup path relies on reflection for generated classes.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.WorkDatabase_AutoMigration_*_Impl { *; }
-keep class androidx.work.impl.model.*_Impl { *; }
