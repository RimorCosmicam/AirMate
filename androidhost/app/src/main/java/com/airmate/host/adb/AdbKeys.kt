package com.airmate.host.adb

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The RSA identity this device knows us by.
 *
 * Pairing is a one-time cost only because the key outlives it: adbd remembers the public half, so
 * every later connection is a TLS handshake against a key it already trusts and never asks for a
 * code again. Losing the key means pairing again, so it is written once and reused.
 */
class AdbKeys private constructor(val keyPair: KeyPair) {
    companion object {
        private const val PRIVATE = "airmate_adb.priv"
        private const val PUBLIC = "airmate_adb.pub"

        fun loadOrGenerate(context: Context): AdbKeys {
            val privateFile = File(context.filesDir, PRIVATE)
            val publicFile = File(context.filesDir, PUBLIC)

            if (privateFile.exists() && publicFile.exists()) {
                runCatching {
                    val factory = KeyFactory.getInstance("RSA")
                    val private = factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
                    val public = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                    // A half-written pair authenticates as nothing and fails deep inside the
                    // handshake, where the error names TLS rather than the two files behind it.
                    require(
                        (private as? RSAPrivateCrtKey)?.modulus == (public as? RSAPublicKey)?.modulus
                    ) { "stored ADB keys do not match each other" }
                    return AdbKeys(KeyPair(public, private))
                }.onFailure {
                    privateFile.delete()
                    publicFile.delete()
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            privateFile.writeBytes(pair.private.encoded)
            publicFile.writeBytes(pair.public.encoded)
            return AdbKeys(pair)
        }
    }
}
