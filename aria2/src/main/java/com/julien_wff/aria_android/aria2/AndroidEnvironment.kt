package com.julien_wff.aria_android.aria2

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

internal data class SessionEnvironment(val caFile: File, val dnsServers: List<String>)

internal class AndroidEnvironment(context: Context, private val root: File) {
    private val context = context.applicationContext

    fun load(): SessionEnvironment {
        check(root.isDirectory || root.mkdirs()) { "Cannot create engine directory" }
        // AndroidCAStore aliases distinguish system anchors from user-installed roots.
        // System-only trust matches the default policy for our target SDK.
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val certificates = store.aliases().toList().filter { it.startsWith("system:") }
            .mapNotNull { store.getCertificate(it) as? X509Certificate }
        check(certificates.isNotEmpty()) { "No platform system trust anchors available" }
        val caFile = File(root, "system-ca.pem")
        val temporary = File(root, "system-ca.pem.tmp")
        temporary.outputStream().buffered().use { stream ->
            certificates.forEach { cert ->
                stream.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
                stream.write(Base64.getMimeEncoder(64, byteArrayOf(10)).encode(cert.encoded))
                stream.write("\n-----END CERTIFICATE-----\n".toByteArray())
            }
        }
        check(temporary.renameTo(caFile)) { "Cannot replace trust bundle" }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val dns = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers
            .orEmpty().mapNotNull { it.hostAddress }
        return SessionEnvironment(caFile, dns)
    }
}
