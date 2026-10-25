package io.github.lejw0925.virtualscreen.phone

import android.content.Context
import android.os.Build
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import io.github.lejw0925.virtualscreen.core.CredentialVault
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** Local self-signed identity for libadb-android's TLS connection.
 * The private key and certificate persist as one encrypted CredentialVault value. */
internal class NativeAdbIdentity(context: Context) : AbsAdbConnectionManager() {
    private val privatePart: PrivateKey
    private val certificatePart: Certificate
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
        val vault = CredentialVault(context)
        val saved = vault.get("wireless-adb-identity")
        if (saved != null) {
            val json = JSONObject(saved)
            privatePart = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(json.getString("key"), Base64.NO_WRAP)))
            certificatePart = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(json.getString("certificate"), Base64.NO_WRAP).inputStream())
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            privatePart = pair.private
            val subject = X500Name("CN=AVSR phone")
            val holder = JcaX509v3CertificateBuilder(subject, BigInteger(160, SecureRandom()).add(BigInteger.ONE),
                Date(System.currentTimeMillis() - 60_000), Date(System.currentTimeMillis() + 3650L * 86_400_000),
                subject, pair.public).build(JcaContentSignerBuilder("SHA256withRSA").build(privatePart))
            certificatePart = CertificateFactory.getInstance("X.509").generateCertificate(holder.encoded.inputStream())
            vault.put("wireless-adb-identity", JSONObject().put("key", Base64.encodeToString(privatePart.encoded, Base64.NO_WRAP))
                .put("certificate", Base64.encodeToString(certificatePart.encoded, Base64.NO_WRAP)).toString())
        }
    }
    override fun getPrivateKey(): PrivateKey = privatePart
    override fun getCertificate(): Certificate = certificatePart
    override fun getDeviceName(): String = "AVSR phone"
}
