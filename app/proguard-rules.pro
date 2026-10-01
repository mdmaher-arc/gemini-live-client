# Proguard rules for Gemini Live Client
-keep class com.geminilive.client.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okio.**
