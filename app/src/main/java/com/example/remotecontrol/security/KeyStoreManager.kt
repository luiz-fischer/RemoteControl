package com.example.remotecontrol.security

import android.content.Context
import android.os.Build
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

class KeyStoreManager(private val context: Context) {
    private val keyStoreAlias = "atv_remote_v2"
    private val keyStoreFile = File(context.filesDir, "remote_v2.p12")
    private val password = "password".toCharArray()

    fun getSSLContext(): SSLContext {
        val keyStore = loadOrGenerateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf(trustAllManager), SecureRandom())
        return sslContext
    }

    fun getCertificate(): X509Certificate? {
        val ks = loadOrGenerateKeyStore()
        return ks.getCertificate(keyStoreAlias) as? X509Certificate
    }

    private fun loadOrGenerateKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        if (keyStoreFile.exists()) {
            try {
                FileInputStream(keyStoreFile).use { ks.load(it, password) }
                val cert = ks.getCertificate(keyStoreAlias) as? X509Certificate
                // Se o certificado ainda for o antigo 'anymote', deletamos para forçar a nova identidade 'atvremote'
                if (cert?.subjectDN?.name?.contains("CN=anymote", ignoreCase = true) == true) {
                    Log.i("Security", "Old identity detected. Forcing certificate regeneration.")
                    keyStoreFile.delete()
                } else {
                    return ks
                }
            } catch (e: Exception) {
                keyStoreFile.delete()
            }
        }
        
        ks.load(null, password)
        val keyPair = generateKeyPair()
        val cert = generateCertificate(keyPair)
        ks.setKeyEntry(keyStoreAlias, keyPair.private, password, arrayOf(cert))
        FileOutputStream(keyStoreFile).use { ks.store(it, password) }
        Log.i("Security", "New 'atvremote' certificate generated successfully")
        return ks
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        // Identidade oficial exigida pelo protocolo Android TV Remote v2
        val dnName = X500Name("CN=atvremote, OU=Android, O=Google Inc., L=Mountain View, ST=California, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 1000 * 60 * 60 * 24)
        val notAfter = Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 20)

        val builder = JcaX509v3CertificateBuilder(
            dnName, serial, notBefore, notAfter, dnName, keyPair.public
        )

        // BasicConstraints obrigatório para alguns Android TVs validarem o cert do cliente.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth))
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "atvremote")))
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
