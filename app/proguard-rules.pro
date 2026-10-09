# R8 (release). Compose, AndroidX y haze traen sus propias reglas de consumidor.
# Trazas legibles si algo falla en un dispositivo.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# libsu: el shell root se arma con clases internas que la librería instancia por nombre.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
