# R8 / ProGuard para :live.
#
# ML Kit Digital Ink: el AAR trae consumer-rules, pero mantenemos sus clases por seguridad
# (usa JNI/reflexión interna). Si el reconocimiento fallara en release, este keep lo cubre.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# Modelos del :core compartido que viajan por DataStore / se serializan por reflexión: nada
# especial, Kotlin data classes simples. Sin reglas extra.
