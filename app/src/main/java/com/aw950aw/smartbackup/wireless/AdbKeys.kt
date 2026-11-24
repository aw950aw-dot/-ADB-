package com.aw950aw.smartbackup.wireless

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AdbKeys(val privateKey: PrivateKey, val certificate: Certificate)

/**
 * The RSA key that identifies this app to the device. Created once and kept in the app's private
 * folder; the device remembers it after pairing, so pairing is needed only once.
 * The certificate is built by hand (DER) so no extra crypto library is needed.
 */
object AdbKeyStore {
    private val lock = Any()

    fun load(context: Context): AdbKeys = synchronized(lock) {
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.isFile && certFile.isFile) {
            try {
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                return@synchronized AdbKeys(privateKey, certificate)
            } catch (_: Exception) {
                keyFile.delete()
                certFile.delete()
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val der = buildCertificate(pair.private, pair.public.encoded)
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(der)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        AdbKeys(pair.private, certificate)
    }

    /** Forgets the key. The device must be paired again afterwards. */
    fun reset(context: Context) = synchronized(lock) {
        File(File(context.filesDir, "adb"), "key.pk8").delete()
        File(File(context.filesDir, "adb"), "cert.der").delete()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun lengthBytes(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray =
        concat(byteArrayOf(tag.toByte()), lengthBytes(body.size), body)

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
    private val sha256WithRsaOid = byteArrayOf(
        0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b
    )

    // OID 2.5.4.3 (commonName)
    private val commonNameOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    private fun buildCertificate(privateKey: PrivateKey, publicKeyInfo: ByteArray): ByteArray {
        val algorithm = sequence(sha256WithRsaOid, byteArrayOf(0x05, 0x00))
        val name = sequence(tlv(0x31, sequence(commonNameOid, tlv(0x0c, "SmartBackup".toByteArray(Charsets.UTF_8)))))
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val notBefore = format.format(Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L))
        val validity = sequence(
            tlv(0x17, notBefore.toByteArray(Charsets.US_ASCII)),
            tlv(0x17, "491231235959Z".toByteArray(Charsets.US_ASCII))
        )
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)
        val tbs = sequence(
            tlv(0xa0, integer(BigInteger.valueOf(2))),
            integer(serial),
            algorithm,
            name,
            validity,
            name,
            publicKeyInfo
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(tbs)
        val signature = signer.sign()
        return sequence(tbs, algorithm, tlv(0x03, concat(byteArrayOf(0), signature)))
    }
}
