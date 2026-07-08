package com.noop.cloud

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * TLS for the app↔cloud HTTPS link (noop-cloud Phase 5): **mutual** authentication.
 *
 *  * Server identity — the cloud's self-signed cert is **pinned** trust-on-first-use: first link records
 *    its SHA-256 (anchored by the in-person rotating code + admin PIN), later connections accept only
 *    that exact cert (defeats LAN MITM).
 *  * Client identity — when the cloud issued us a client cert at pairing, we present it via a KeyManager
 *    so the cloud can verify the app in turn (full mTLS). Absent → server-pinning only.
 *
 * Plain-http (non-TLS) cloud URLs are unaffected — this only kicks in for https:// bases.
 */
object CloudTls {

    /** An OkHttpClient that pins the cloud cert, presents our client cert (if any), AND stamps the
     *  unique client id on every request for [ctx]. For http:// bases OkHttp never uses the custom
     *  socket factory, so this is safe for every call. */
    fun client(ctx: Context, base: OkHttpClient): OkHttpClient {
        val app = ctx.applicationContext
        val tm = PinningTrustManager(app)
        val km = clientKeyManagers(app)
        val ssl = SSLContext.getInstance("TLS").apply { init(km, arrayOf(tm), null) }
        return base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, tm)
            // Hostname mismatch is irrelevant once the exact cert is pinned (LAN IPs won't match CN);
            // the pin IS the identity check. Only relax it for a pinned cert.
            .hostnameVerifier { _, _ -> true }
            // Bind every cloud request to this device+strap so a stolen token can't be replayed elsewhere.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Noop-Client-Id", ClientId.get(app))
                        .build(),
                )
            }
            .build()
    }

    /** Build KeyManagers presenting our stored client cert + PKCS#8 key, or null if we have none. */
    private fun clientKeyManagers(ctx: Context): Array<KeyManager>? {
        val certPem = CloudPrefs.clientCert(ctx) ?: return null
        val keyPem = CloudPrefs.clientKey(ctx) ?: return null
        return runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certPem.byteInputStream()) as X509Certificate
            // The cloud mints EC (P-256) client keys so Android's KeyFactory can parse them (ML-DSA
            // keys aren't in Android's providers yet). The CA signature may be post-quantum; the leaf
            // key stays EC.
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(pemToDer(keyPem)))
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("noop-client", key, CHARS, arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, CHARS) }
            kmf.keyManagers
        }.getOrNull()
    }

    private val CHARS = CharArray(0)

    /** Strip the PEM header/footer/newlines and base64-decode to DER. */
    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(body, Base64.DEFAULT)
    }

    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    private class PinningTrustManager(private val ctx: Context) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("no server certificate")
            val fp = sha256(leaf)
            val pinned = CloudPrefs.certPin(ctx)
            if (pinned.isNullOrBlank()) {
                // Trust-on-first-use: remember this cert as the pin for every later connection.
                CloudPrefs.saveCertPin(ctx, fp)
                return
            }
            if (!constantTimeEquals(fp, pinned)) {
                throw java.security.cert.CertificateException(
                    "cloud certificate changed — refusing (possible MITM). Unlink + re-link to re-pin.",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var r = 0
            for (i in a.indices) r = r or (a[i].code xor b[i].code)
            return r == 0
        }
    }
}
