package com.majidhajizade.messages

import android.content.Context
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore

class MeshIdentity(context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "MessagesMeshIdentity"
    }

    private val keyStore = KeyStore.getInstance(KEYSTORE).apply {
        load(null)
    }

    private fun ensureKeyPair(): KeyPair {
        val existing = keyStore.getEntry(ALIAS, null)

        if (existing is KeyStore.PrivateKeyEntry) {
            return KeyPair(
                existing.certificate.publicKey,
                existing.privateKey
            )
        }

        val generator = KeyPairGenerator.getInstance(
            "RSA",
            KEYSTORE
        )

        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(
                    android.security.keystore.KeyProperties.DIGEST_SHA256,
                    android.security.keystore.KeyProperties.DIGEST_SHA512
                )
                .build()
        )

        return generator.generateKeyPair()
    }

    private val keyPair = ensureKeyPair()

    fun publicKeyBase64(): String =
        Base64.encodeToString(
            keyPair.public.encoded,
            Base64.NO_WRAP
        )

    fun sign(data: ByteArray): String {
        val signature = java.security.Signature.getInstance(
            "SHA256withRSA"
        )

        signature.initSign(keyPair.private)
        signature.update(data)

        return Base64.encodeToString(
            signature.sign(),
            Base64.NO_WRAP
        )
    }
}
