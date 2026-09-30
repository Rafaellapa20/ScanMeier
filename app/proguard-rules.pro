# ============================================================
# PROGUARD RULES - MAXIMUM OBFUSCATION
# ============================================================

# Renomear tudo com nomes de 1 caracter (a, b, c...)
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# Agressividade máxima
-optimizationpasses 7
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses ''
-flattenpackagehierarchy ''

# Remover toda a informação de debug
-renamesourcefileattribute x
-keepattributes SourceFile,LineNumberTable

# Remover todos os logs (Log.d, Log.e, Log.v, etc.)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(...);
}

# Remover stack traces
-assumenosideeffects class java.lang.Exception {
    public void printStackTrace();
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
    public java.lang.String getMessage();
}

# Manter apenas o mínimo necessário para a app funcionar
-keep class ch.greenonline.ernstmeier.SecurityGuard { *; }

# Manter componentes Android necessários
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.core.content.FileProvider

# ViewBinding
-keep class ch.greenonline.ernstmeier.databinding.** { *; }

# Manter anotações JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Camera e ML Kit
-keep class com.google.mlkit.** { *; }
-keep class androidx.camera.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# Remover informações de debug do Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}
