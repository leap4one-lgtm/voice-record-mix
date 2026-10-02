# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.voicerecordmix.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.voicerecordmix.**$$serializer { *; }
