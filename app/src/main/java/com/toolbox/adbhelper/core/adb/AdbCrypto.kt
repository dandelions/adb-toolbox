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

    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(token)
    }

    fun getPublicKeyBytes(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val mod = pub.modulus
        val exp = pub.publicExponent

        // ADB public key 格式 (mincrypt RSA 结构体)
        // struct RSAPublicKey {
        //     int len;                  /* Length of n[] in number of uint32_t */
        //     uint32_t n0inv;           /* -1 / n[0] mod 2^32 */
        //     uint32_t n[RSANUMWORDS];  /* modulus as little endian array */
        //     uint32_t rr[RSANUMWORDS]; /* R^2 as little endian array */
        //     int exponent;             /* 3 or 65537 */
        // };
        // 简单标准：很多 Android 版本支持标准 X509 / mincrypt 格式，通过 Base64 编码附加标识
        val keyBytes = convertToAdbFormat(pub)
        val b64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val full = "$b64 ADBToolbox@Android\u0000"
        return full.toByteArray(Charsets.UTF_8)
    }

    private fun convertToAdbFormat(pub: RSAPublicKey): ByteArray {
        val mod = pub.modulus
        val numWords = 2048 / 32 // 64 words for 2048-bit key
        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(numWords)

        // n0inv = -1 / n[0] mod 2^32
        val r32 = java.math.BigInteger.valueOf(2).pow(32)
        val n0 = mod.remainder(r32)
        val n0inv = n0.modInverse(r32).negate().remainder(r32).add(r32).remainder(r32)
        buf.putInt(n0inv.toInt())

        // n: modulus little endian
        val modBytes = mod.toByteArray()
        val nWords = IntArray(numWords)
        for (i in 0 until numWords) {
            var word = 0
            for (j in 0 until 4) {
                val byteIndex = modBytes.size - 1 - (i * 4 + j)
                val b = if (byteIndex >= 0) modBytes[byteIndex].toInt() and 0xFF else 0
                word = word or (b shl (j * 8))
            }
            buf.putInt(word)
        }

        // rr = (2^2048)^2 mod n
        val r = java.math.BigInteger.valueOf(2).pow(2048)
        val rr = r.multiply(r).remainder(mod)
        val rrBytes = rr.toByteArray()
        for (i in 0 until numWords) {
            var word = 0
            for (j in 0 until 4) {
                val byteIndex = rrBytes.size - 1 - (i * 4 + j)
                val b = if (byteIndex >= 0) rrBytes[byteIndex].toInt() and 0xFF else 0
                word = word or (b shl (j * 8))
            }
            buf.putInt(word)
        }

        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }

    companion object {
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
}
