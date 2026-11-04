# Tesseract4Android / Leptonica: к этим классам и их полям обращается нативный код (JNI),
# поэтому оптимизатор не должен их удалять или переименовывать.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Аннотации, которых нет во время выполнения
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
