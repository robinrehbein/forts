# kotlinx.serialization: Serializer der Command-/Content-Klassen behalten
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class de.bollwerk.**$$serializer { *; }
-keepclassmembers class de.bollwerk.** { *** Companion; }
-keepclasseswithmembers class de.bollwerk.** { kotlinx.serialization.KSerializer serializer(...); }
