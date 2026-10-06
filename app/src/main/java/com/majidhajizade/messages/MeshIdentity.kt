package com.majidhajizade.messages

import android.content.Context
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class MeshIdentity(context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val SIGN_ALIAS = "MessagesMeshIdentity"
        private const val ENCRYPT_ALIAS = "MessagesMeshEncryption"
    }

    private val keyStore = KeyStore.getInstance(KEYSTORE).apply {
        load(null)
    }

    private fun ensureSigningKeyPair(): KeyPair {
        val existing = keyStore.getEntry(SIGN_ALIAS, null)

        if (existing is KeyStore.PrivateKeyEntry) {
            return KeyPair(
                existing.certificate.publicKey,
                existing.privateKey
            )
        }

        val generator = KeyPairGenerator.getInstance("RSA", KEYSTORE)

        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                SIGN_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(
                    android.security.keystore.KeyProperties.DIGEST_SHA256
                )
                .setSignaturePaddings(
                    android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                )
                .build()
        )

        return generator.generateKeyPair()
    }

    private fun ensureEncryptionKeyPair(): KeyPair {
        val existing = keyStore.getEntry(ENCRYPT_ALIAS, null)

        if (existing is KeyStore.PrivateKeyEntry) {
            return KeyPair(
                existing.certificate.publicKey,
                existing.privateKey
            )
        }

        val generator = KeyPairGenerator.getInstance("RSA", KEYSTORE)

        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ENCRYPT_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_OAEP
                )
                .setDigests(
                    android.security.keystore.KeyProperties.DIGEST_SHA256
                )
                .build()
        )

        return generator.generateKeyPair()
    }

    private val signingKeyPair = ensureSigningKeyPair()
    private val encryptionKeyPair = ensureEncryptionKeyPair()

    fun publicKeyBase64(): String =
        Base64.encodeToString(
            signingKeyPair.public.encoded,
            Base64.NO_WRAP
        )

    fun encryptionPublicKeyBase64(): String =
        Base64.encodeToString(
            encryptionKeyPair.public.encoded,
            Base64.NO_WRAP
        )

    fun sign(data: ByteArray): String {
        val signature = java.security.Signature.getInstance(
            "SHA256withRSA"
        )

        signature.initSign(signingKeyPair.private)
        signature.update(data)

        return Base64.encodeToString(
            signature.sign(),
            Base64.NO_WRAP
        )
    }

    fun encryptMessage(
        publicKey: PublicKey,
        plaintext: ByteArray
    ): String {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val aesKey: SecretKey = keyGenerator.generateKey()

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            aesKey,
            GCMParameterSpec(128, iv)
        )

        val ciphertext = cipher.doFinal(plaintext)
        val encryptedKey = encryptForPublicKey(
            publicKey,
            aesKey.encoded
        )

        val packet = ByteBuffer.allocate(
            4 + encryptedKey.size +
                4 + iv.size +
                4 + ciphertext.size
        )

        packet.putInt(encryptedKey.size)
        packet.put(encryptedKey)
        packet.putInt(iv.size)
        packet.put(iv)
        packet.putInt(ciphertext.size)
        packet.put(ciphertext)

        return Base64.encodeToString(
            packet.array(),
            Base64.NO_WRAP
        )
    }

    fun decryptMessage(
        packetBase64: String
    ): String {
        val packet = ByteBuffer.wrap(
            Base64.decode(packetBase64, Base64.NO_WRAP)
        )

        val encryptedKeySize = packet.int
        require(encryptedKeySize > 0 && encryptedKeySize <= packet.remaining())

        val encryptedKey = ByteArray(encryptedKeySize)
        packet.get(encryptedKey)

        val ivSize = packet.int
        require(ivSize == 12 && ivSize <= packet.remaining())

        val iv = ByteArray(ivSize)
        packet.get(iv)

        val ciphertextSize = packet.int
        require(ciphertextSize > 0 && ciphertextSize <= packet.remaining())

        val ciphertext = ByteArray(ciphertextSize)
        packet.get(ciphertext)

        val aesKey = SecretKeySpec(
            decrypt(encryptedKey),
            "AES"
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            aesKey,
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(ciphertext),
            Charsets.UTF_8
        )
    }

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKeyPair.private,
            oaepSpec
        )
        return cipher.doFinal(data)
    }

    fun encryptForPublicKey(
        publicKey: PublicKey,
        data: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            oaepSpec
        )
        return cipher.doFinal(data)
    }
}
