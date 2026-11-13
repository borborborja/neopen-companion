# Moshi usa reflexión para DTOs, preferencias y JSON persistido de Room/outbox.
# Sus nombres y metadatos deben sobrevivir exactamente a la optimización.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
-keep class net.micapum.neopen.core.data.** { *; }
-keep class net.micapum.neopen.core.pen.KnownPen { *; }
-keep class net.micapum.neopen.core.pen.StrokeStyle { *; }
-keep class net.micapum.neopen.app.data.** { *; }

# AndroidJUnitRunner comparte esta dependencia con el APK probado. Mantener su
# API permite ejecutar la instrumentación contra el mismo binario distribuible.
-keep class androidx.tracing.Trace { *; }
# El runner de tests está compilado contra los facades públicos de la misma
# stdlib. R8 puede haber inlinado todos sus usos dentro de la aplicación.
-keep,allowoptimization class kotlin.**Kt { *; }
-keep,allowoptimization interface kotlin.jvm.functions.** { *; }
-keep,allowoptimization class kotlin.jvm.internal.Intrinsics { *; }
-keep,allowoptimization class kotlin.jvm.internal.Reflection* { *; }
-keep,allowoptimization class kotlin.coroutines.** { *; }
-keep,allowoptimization class kotlin.Pair { *; }
-keep,allowoptimization class kotlin.Triple { *; }
-keep,allowoptimization class kotlinx.coroutines.**Kt { *; }
-keep,allowoptimization class androidx.datastore.preferences.core.** { *; }
-keep,allowoptimization class androidx.arch.core.executor.** { *; }
-keep,allowoptimization class androidx.sqlite.db.** { *; }
-keep,allowoptimization class androidx.room.Room { *; }
-keep,allowoptimization class androidx.room.RoomDatabase { *; }
-keep,allowoptimization class androidx.room.RoomDatabase$Builder { *; }
-keep,allowoptimization class androidx.room.DatabaseConfiguration { *; }
-keep,allowoptimization class androidx.room.** { public *; protected *; }

# Entradas públicas ejercitadas desde el APK de pruebas. Conservarlas no impide
# optimizar su implementación ni el código de Compose que las llama.
-keep,allowoptimization class net.micapum.neopen.app.NeopenFullAppKt { *; }
-keep,allowoptimization class net.micapum.neopen.app.render.RenderKt { *; }
-keep,allowoptimization class net.micapum.neopen.app.render.ExportKt { *; }
-keep,allowoptimization class net.micapum.neopen.app.render.PreviewAssetStore { *; }
-keep,allowoptimization class net.micapum.neopen.app.render.PagePreviewCache { *; }
-keep,allowoptimization class net.micapum.neopen.app.sync.SyncEngine { public *; }
-keep,allowoptimization class net.micapum.neopen.app.sync.SyncEngine$Result { public *; }
-keep,allowoptimization class net.micapum.neopen.app.db.** { public *; protected *; }
-keep,allowoptimization class net.micapum.neopen.core.capability.WebdavConnector { *; }

# MockWebServer/TLS y el cliente comparten estas APIs en el proceso probado.
# Mantener sus firmas públicas conserva la optimización de sus cuerpos privados.
-keep,allowoptimization class okio.** { public *; protected *; }
-keep,allowoptimization class okhttp3.** { public *; protected *; }

# JSch selecciona algoritmos criptográficos por nombre de clase; su JAR no
# incluye reglas de consumo. No eliminar esas implementaciones por reflexión.
-keep class com.jcraft.jsch.** { *; }

# Integraciones opcionales de escritorio de JSch (JNA/Pageant, agentes Unix,
# Kerberos y loggers externos), ausentes también en el APK Android sin minificar.
# Reglas limitadas a las referencias opcionales detectadas por R8.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
-dontwarn org.newsclub.net.unix.AFUNIXServerSocketChannel
-dontwarn org.newsclub.net.unix.AFUNIXSocketAddress
-dontwarn org.newsclub.net.unix.AFUNIXSocketChannel
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
