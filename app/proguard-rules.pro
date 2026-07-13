-keep class com.solis.assistant.data.** { *; }
-keep class com.solis.assistant.api.** { *; }
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
