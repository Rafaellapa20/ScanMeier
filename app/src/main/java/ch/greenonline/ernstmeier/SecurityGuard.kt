package ch.greenonline.ernstmeier

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * Classe de segurança - deteta tentativas de modificação, análise ou hacking da app.
 * Se detetar qualquer ameaça, encerra a app imediatamente sem aviso.
 */
object SecurityGuard {

    fun runAllChecks(context: Context): Boolean {
        return isDebuggerConnected()
                || isRunningOnEmulator()
                || hasHackingTools()
                || isPackageNameTampered(context)
    }

    // 1. Verifica se há um debugger ligado (Frida, JADX com debug, etc.)
    private fun isDebuggerConnected(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    // 2. Verifica se está a correr num emulador (usado por hackers para analisar APKs)
    private fun isRunningOnEmulator(): Boolean {
        return (android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MANUFACTURER.contains("Genymotion")
                || android.os.Build.BRAND.startsWith("generic")
                || android.os.Build.DEVICE.startsWith("generic")
                || "google_sdk" == android.os.Build.PRODUCT)
    }

    // 3. Verifica se há ferramentas de hacking instaladas no telemóvel
    private fun hasHackingTools(): Boolean {
        val suspiciousPaths = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/re.frida.server",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/system/bin/.ext/su", "/system/usr/we-need-root/su-backup",
            "/su/bin/su"
        )
        return suspiciousPaths.any { File(it).exists() }
    }

    // 4. Verifica se o nome do pacote foi alterado (APK clonado/modificado)
    private fun isPackageNameTampered(context: Context): Boolean {
        return try {
            val expectedPackage = android.util.Base64.decode(
                "Y2guZ3JlZW5vbmxpbmUuZXJuc3RtZWllcg==",
                android.util.Base64.DEFAULT
            ).toString(Charsets.UTF_8)
            context.packageName != expectedPackage
        } catch (e: Exception) {
            true
        }
    }

    // 5. Verifica o hash EXATO do certificado de assinatura
    // Se o APK for re-assinado com qualquer outra chave → fecha imediatamente
    fun isSignatureTampered(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }

            if (signatures == null || signatures.isEmpty()) return true

            // Calcular o hash SHA-256 do certificado atual
            val certBytes = signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest(certBytes)
            val actualHash = hashBytes.joinToString("") { "%02x".format(it) }

            // Hash esperado dividido em partes para não ser visível em texto simples
            val h1 = android.util.Base64.decode("MmU5N2IzMDU5NjZj", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val h2 = android.util.Base64.decode("NDc1NzQ4MDczMWZj", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val h3 = android.util.Base64.decode("MzkyMDhjNDdkOWZh", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val h4 = android.util.Base64.decode("YjA1MmY0YmU3NzBm", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val h5 = android.util.Base64.decode("MTU2YjE4YWQwNjVj", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val h6 = android.util.Base64.decode("YmFlYw==", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            val expectedHash = h1 + h2 + h3 + h4 + h5 + h6

            actualHash != expectedHash
        } catch (e: Exception) {
            true // Se falhar a verificação, considera comprometido
        }
    }
}
