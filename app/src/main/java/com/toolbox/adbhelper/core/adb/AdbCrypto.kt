package com.toolbox.adbhelper.core.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

class AdbCrypto(private val keyPair: KeyPair) {

    companion object {
        // RSA signature padding for SHA-1 digest (PKCS#1 v1.5 standard padding for sign)
        // 2 + 218 + 1 + 15 = 236 bytes padding, combined with 20 bytes SHA-1 token = 256 bytes (2048-bit key)
        private val SIGNATURE_PADDING = byteArrayOf(
            0x00.toByte(), 0x01.toByte(),
            *ByteArray(218) { 0xFF.toByte() },
            0x00.toByte(),
            // ASN.1 DigestInfo for SHA-1
            0x30.toByte(), 0x21.toByte(), 0x30.toByte(), 0x09.toByte(),
            0x06.toByte(), 0x05.toByte(), 0x2b.toByte(), 0x0e.toByte(),
            0x03.toByte(), 0x02.toByte(), 0x1a.toByte(), 0x05.toByte(),
            0x00.toByte(), 0x04.toByte(), 0x14.toByte()
        )

        fun loadOrGenerate(context: Context): AdbCrypto {
            val keyDir = File(context.filesDir, "keys")
            if (!keyDir.exists()) keyDir.mkdirs()
            val privFile = File(keyDir, "adbkey")
            val pubFile = File(keyDir, "adbkey.pub")

            if (privFile.exists() && pubFile.exists()) {
                try {
                    val kf = KeyFactory.getInstance("RSA")
                    val privSpec = PKCS8EncodedKeySpec(privFile.readBytes())
                    val pubSpec = X509EncodedKeySpec(pubFile.readBytes())
                    val priv = kf.generatePrivate(privSpec)
                    val pub = kf.generatePublic(pubSpec)
                    return AdbCrypto(KeyPair(pub, priv))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)

            return AdbCrypto(kp)
        }
    }

    /**
     * Signs the 20-byte ADB auth token using RSA with PKCS#1 v1.5 padding and SHA-1 ASN.1 header.
     */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        val data = ByteArray(SIGNATURE_PADDING.size + token.size)
        System.arraycopy(SIGNATURE_PADDING, 0, data, 0, SIGNATURE_PADDING.size)
        System.arraycopy(token, 0, data, SIGNATURE_PADDING.size, token.size)
        return cipher.doFinal(data)
    }

    /**
     * Converts a standard RSAPublicKey object to the special ADB format (mincrypt format).
     */
    fun getPublicKeyBytes(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val keyBytes = convertToAdbFormat(pub)
        val b64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val full = "$b64 adbhelper@toolbox\u0000"
        return full.toByteArray(Charsets.UTF_8)
    }

    private fun convertToAdbFormat(pub: RSAPublicKey): ByteArray {
        val keyWords = 64 // 2048 bits / 32 bits per word
        val r32 = java.math.BigInteger.ZERO.setBit(32)
        var n = pub.modulus
        val r = java.math.BigInteger.ZERO.setBit(keyWords * 32)
        var rr = r.modPow(java.math.BigInteger.valueOf(2), n)
        val rem = n.remainder(r32)
        val n0inv = rem.modInverse(r32)

        val myN = IntArray(keyWords)
        val myRr = IntArray(keyWords)
        for (i in 0 until keyWords) {
            val resRr = rr.divideAndRemainder(r32)
            rr = resRr[0]
            myRr[i] = resRr[1].toInt()

            val resN = n.divideAndRemainder(r32)
            n = resN[0]
            myN[i] = resN[1].toInt()
        }

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(keyWords)
        buf.putInt(n0inv.negate().toInt())
        for (v in myN) buf.putInt(v)
        for (v in myRr) buf.putInt(v)
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }
}
