package com.majidhajizade.messages

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.Locale
import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OfflineMeshManager(
    context: Context,
    private val meshId: String,
    private val listener: Listener
) {

    interface Listener {
        fun onPeerDiscovered(endpointId: String, name: String)
        fun onPeerLost(endpointId: String)
        fun onPeerConnected(endpointId: String, name: String)
        fun onPeerDisconnected(endpointId: String)
        fun onMessage(endpointId: String, message: String)
        fun onError(message: String)
    }

    companion object {
        private const val SERVICE_ID =
            "com.majidhajizade.messages.offline"

        private const val HANDSHAKE_PREFIX =
            "MESSAGES_MESH_HANDSHAKE|v3|"

        private const val MESSAGE_PREFIX =
            "MESSAGES_MESH_MESSAGE|v1|"

        private const val ACK_PREFIX =
            "MESSAGES_MESH_ACK|v1|"

        private const val MAX_TTL = 6
        private const val RETRY_INTERVAL_MS = 5000L

        private const val SEEN_LIMIT = 2000

        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val identity = MeshIdentity(context)

    private val connectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    private val handler =
        Handler(Looper.getMainLooper())

    private val connectedEndpoints =
        ConcurrentHashMap.newKeySet<String>()

    private val connectingEndpoints =
        ConcurrentHashMap.newKeySet<String>()

    private val handshakeEndpoints =
        ConcurrentHashMap.newKeySet<String>()

    private val endpointNames =
        ConcurrentHashMap<String, String>()
    private val endpointPublicKeys =
        ConcurrentHashMap<String, String>()
    private val endpointSigningPublicKeys =
        ConcurrentHashMap<String, String>()

    private val handshakeTimeouts =
        ConcurrentHashMap<String, Runnable>()

    private val seenMessages = LinkedHashSet<String>()
    private val seenLock = Any()

    private val seenAcks = LinkedHashSet<String>()
    private val ackLock = Any()

    private val pendingMessages = LinkedHashMap<String, MeshMessage>()
    private val pendingLock = Any()

    private val pendingAttempts = ConcurrentHashMap<String, Int>()
    private val messageReturnRoutes = ConcurrentHashMap<String, String>()

    private fun queuePendingMessage(message: MeshMessage) {
        if (message.destination == null) return

        synchronized(pendingLock) {
            pendingMessages[message.id] = message

            while (pendingMessages.size > SEEN_LIMIT) {
                val iterator = pendingMessages.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun removePendingMessage(messageId: String) {
        synchronized(pendingLock) {
            pendingMessages.remove(messageId)
        }
        pendingAttempts.remove(messageId)
        messageReturnRoutes.remove(messageId)
    }

    private fun retryPendingMessages() {
        val pending = synchronized(pendingLock) {
            pendingMessages.values.toList()
        }

        pending.forEach { message ->
            pendingAttempts[message.id] =
                (pendingAttempts[message.id] ?: 0) + 1

            if (message.ttl <= 0) {
                removePendingMessage(message.id)
                return@forEach
            }

            val payload = Payload.fromBytes(
                encodeMessage(message)
                    .toByteArray(Charsets.UTF_8)
            )

            var deliveredToDestination = false

            connectedEndpoints.forEach { endpointId ->
                val peerId = endpointNames[endpointId]

                if (
                    message.destination == null ||
                    peerId.equals(message.destination, ignoreCase = true)
                ) {
                    deliveredToDestination = true
                }

                connectionsClient.sendPayload(
                    endpointId,
                    payload
                )
            }

            if (deliveredToDestination) {
                removePendingMessage(message.id)
            }
        }
    }

    @Volatile
    private var targetMeshId: String? = null

    @Volatile
    private var started = false

    fun setTargetMeshId(meshId: String?) {
        targetMeshId = meshId
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.matches(Regex("MJ-[A-Z0-9]{6}")) }

        // Keep all nearby peers connected.
        // The destination is carried inside each mesh message,
        // allowing intermediate peers to relay multi-hop traffic.
    }

    fun getTargetMeshId(): String? = targetMeshId

    private fun sendHandshake(endpointId: String) {
        val handshakeData =
            "$meshId|${identity.publicKeyBase64()}"

        val signature =
            identity.sign(
                handshakeData.toByteArray(Charsets.UTF_8)
            )

        val handshake = Payload.fromBytes(
            "$HANDSHAKE_PREFIX$handshakeData|$signature"
                .toByteArray(Charsets.UTF_8)
        )

        connectionsClient
            .sendPayload(endpointId, handshake)
            .addOnFailureListener {
                listener.onError(
                    "Mesh handshake failed: ${it.message ?: "unknown error"}"
                )
            }

        handshakeTimeouts.remove(endpointId)?.let(handler::removeCallbacks)

        val timeout = Runnable {
            if (!connectedEndpoints.contains(endpointId)) {
                handshakeEndpoints.remove(endpointId)

                listener.onError(
                    "Mesh handshake timeout: ${
                        endpointNames[endpointId] ?: endpointId
                    }"
                )

                connectionsClient.disconnectFromEndpoint(endpointId)
            }
        }

        handshakeTimeouts[endpointId] = timeout
        handler.postDelayed(timeout, 8000)
    }

    private fun verifyHandshakeSignature(
        meshId: String,
        publicKeyBase64: String,
        signatureBase64: String
    ): Boolean {
        return try {
            val keyBytes = Base64.decode(
                publicKeyBase64,
                Base64.NO_WRAP
            )

            val publicKey: PublicKey =
                KeyFactory.getInstance("RSA")
                    .generatePublic(
                        X509EncodedKeySpec(keyBytes)
                    )

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)

            val data = "$meshId|$publicKeyBase64"
                .toByteArray(Charsets.UTF_8)

            verifier.update(data)

            verifier.verify(
                Base64.decode(
                    signatureBase64,
                    Base64.NO_WRAP
                )
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun completeHandshake(
        endpointId: String,
        remoteMeshId: String
    ) {
        val normalized =
            remoteMeshId.trim().uppercase(Locale.US)

        if (!normalized.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            listener.onError(
                "Invalid Mesh ID received from ${
                    endpointNames[endpointId] ?: endpointId
                }"
            )

            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        handshakeTimeouts.remove(endpointId)?.let(handler::removeCallbacks)

        val wanted = targetMeshId

        if (wanted != null &&
            !normalized.equals(wanted, ignoreCase = true)
        ) {
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        endpointNames[endpointId] = normalized

        if (handshakeEndpoints.add(endpointId)) {
            connectedEndpoints.add(endpointId)

            listener.onPeerConnected(
                endpointId,
                normalized
            )

            handler.post {
                retryPendingMessages()
            }
        }
    }

    private fun rememberMessage(messageId: String): Boolean {
        synchronized(seenLock) {
            if (!seenMessages.add(messageId)) {
                return false
            }

            if (seenMessages.size > SEEN_LIMIT) {
                val iterator = seenMessages.iterator()
                iterator.next()
                iterator.remove()
            }

            return true
        }
    }

    private data class MeshMessage(
        val id: String,
        val source: String,
        val destination: String?,
        val ttl: Int,
        val body: String,
        val encrypted: Boolean,
        val signature: String
    )

    private fun signatureData(message: MeshMessage): ByteArray {
        return listOf(
            message.id,
            message.source,
            message.destination ?: "*",
            if (message.encrypted) "1" else "0",
            message.body
        ).joinToString("|").toByteArray(Charsets.UTF_8)
    }

    private fun signMessage(message: MeshMessage): String {
        return identity.sign(signatureData(message))
    }

    private fun verifyMessageSignature(
        message: MeshMessage
    ): Boolean {
        return try {
            val publicKeyBase64 =
                endpointSigningPublicKeys.entries
                    .firstOrNull {
                        endpointNames[it.key]
                            ?.equals(
                                message.source,
                                ignoreCase = true
                            ) == true
                    }
                    ?.let { endpointPublicKeys[it.key] }
                    ?: return false

            val keyBytes = Base64.decode(
                publicKeyBase64,
                Base64.NO_WRAP
            )

            val publicKey =
                KeyFactory.getInstance("RSA")
                    .generatePublic(
                        X509EncodedKeySpec(keyBytes)
                    )

            val verifier =
                Signature.getInstance("SHA256withRSA")

            verifier.initVerify(publicKey)
            verifier.update(signatureData(message))

            verifier.verify(
                Base64.decode(
                    message.signature,
                    Base64.NO_WRAP
                )
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeMessage(message: MeshMessage): String {
        return listOf(
            MESSAGE_PREFIX.removeSuffix("|"),
            message.id,
            message.source,
            message.destination ?: "*",
            message.ttl.toString(),
            if (message.encrypted) "1" else "0",
            message.body,
            message.signature
        ).joinToString("|")
    }

    private fun decodeMessage(raw: String): MeshMessage? {
        if (!raw.startsWith(MESSAGE_PREFIX)) {
            return null
        }

        val parts = raw.split("|", limit = 9)

        if (parts.size < 9) {
            return null
        }

        val ttl = parts[5].toIntOrNull() ?: return null

        val encrypted = parts[6] == "1"

        if (ttl < 0 || ttl > MAX_TTL) {
            return null
        }

        return MeshMessage(
            id = parts[2],
            source = parts[3],
            destination = parts[4].takeIf { it != "*" },
            ttl = ttl,
            body = parts[7],
            encrypted = encrypted,
            signature = parts[8]
        )
    }

    private fun rememberAck(messageId: String): Boolean {
        synchronized(ackLock) {
            if (!seenAcks.add(messageId)) {
                return false
            }

            if (seenAcks.size > SEEN_LIMIT) {
                val iterator = seenAcks.iterator()
                iterator.next()
                iterator.remove()
            }

            return true
        }
    }

    private fun sendAck(
        endpointId: String,
        messageId: String
    ) {
        val payload = Payload.fromBytes(
            "$ACK_PREFIX$messageId|$meshId"
                .toByteArray(Charsets.UTF_8)
        )

        connectionsClient.sendPayload(
            endpointId,
            payload
        )
    }

    private fun forwardAck(
        messageId: String,
        sourceMeshId: String
    ) {
        if (sourceMeshId.equals(meshId, ignoreCase = true)) {
            removePendingMessage(messageId)
            messageReturnRoutes.remove(messageId)
            return
        }

        val returnEndpointId =
            messageReturnRoutes[messageId]
                ?: return

        if (!connectedEndpoints.contains(returnEndpointId)) {
            return
        }

        val payload = Payload.fromBytes(
            "$ACK_PREFIX$messageId|$sourceMeshId"
                .toByteArray(Charsets.UTF_8)
        )

        connectionsClient.sendPayload(
            returnEndpointId,
            payload
        )
    }

    private fun forwardMessage(
        message: MeshMessage,
        incomingEndpointId: String
    ) {
        if (message.ttl <= 0) {
            return
        }

        val forwarded = message.copy(
            ttl = message.ttl - 1
        )

        val payload = Payload.fromBytes(
            encodeMessage(forwarded)
                .toByteArray(Charsets.UTF_8)
        )

        connectedEndpoints
            .filter { it != incomingEndpointId }
            .forEach { endpointId ->
                connectionsClient.sendPayload(
                    endpointId,
                    payload
                )
            }
    }

    private val payloadCallback =
        object : PayloadCallback() {

            override fun onPayloadReceived(
                endpointId: String,
                payload: Payload
            ) {
                val bytes = payload.asBytes() ?: return
                val raw = bytes.toString(Charsets.UTF_8)

                    if (raw.startsWith(HANDSHAKE_PREFIX)) {
                        val parts =
                            raw.removePrefix(HANDSHAKE_PREFIX)
                                .split("|", limit = 4)

                        val remoteMeshId =
                            parts.getOrNull(0)
                                ?.trim()
                                .orEmpty()

                        val remotePublicKey =
                            parts.getOrNull(1)
                                ?.trim()
                                .orEmpty()

                        val remoteSignature =
                            parts.getOrNull(2)
                                ?.trim()
                                .orEmpty()

                        if (
                            remoteMeshId.isEmpty() ||
                            remotePublicKey.isEmpty() ||
                            remoteSignature.isEmpty()
                        ) {
                            return
                        }

                        if (
                            !verifyHandshakeSignature(
                                remoteMeshId,
                                remotePublicKey,
                                remoteSignature
                            )
                        ) {
                            listener.onError(
                                "Invalid mesh handshake signature"
                            )
                            connectionsClient.disconnectFromEndpoint(
                                endpointId
                            )
                            return
                        }

                        endpointPublicKeys[endpointId] =
                            remotePublicKey

                        completeHandshake(
                            endpointId,
                            remoteMeshId
                        )

                        return
                    }

                if (!connectedEndpoints.contains(endpointId)) {
                    return
                }

                if (raw.startsWith(ACK_PREFIX)) {
                    val parts =
                        raw.removePrefix(ACK_PREFIX)
                            .split("|", limit = 2)

                    val messageId =
                        parts.getOrNull(0)
                            ?.trim()
                            .orEmpty()

                    val sourceMeshId =
                        parts.getOrNull(1)
                            ?.trim()
                            .orEmpty()

                    if (
                        messageId.isEmpty() ||
                        sourceMeshId.isEmpty() ||
                        !rememberAck(messageId)
                    ) {
                        return
                    }

                    forwardAck(
                        messageId,
                        sourceMeshId
                    )
                    return
                }

                val meshMessage = decodeMessage(raw)

                if (meshMessage == null) {
                    // Backward compatibility with plain messages.
                    listener.onMessage(
                        endpointId,
                        raw
                    )
                    return
                }

                if (!verifyMessageSignature(meshMessage)) {
                    listener.onError(
                        "Invalid mesh message signature"
                    )
                    return
                }

                messageReturnRoutes[meshMessage.id] = endpointId

                if (!rememberMessage(meshMessage.id)) {
                    return
                }

                val destination = meshMessage.destination

                if (
                    destination == null ||
                    destination.equals(meshId, ignoreCase = true)
                ) {
                    val deliveredBody =
                        if (meshMessage.encrypted) {
                            try {
                                identity.decryptMessage(
                                    meshMessage.body
                                )
                            } catch (_: Exception) {
                                listener.onError(
                                    "Failed to decrypt mesh message"
                                )
                                return
                            }
                        } else {
                            meshMessage.body
                        }

                    listener.onMessage(
                        endpointId,
                        deliveredBody
                    )

                    if (destination != null) {
                        sendAck(endpointId, meshMessage.id)
                        removePendingMessage(meshMessage.id)
                    }
                } else {
                    queuePendingMessage(meshMessage)

                    forwardMessage(
                        meshMessage,
                        endpointId
                    )
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate
            ) {
            }
        }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {

            override fun onConnectionInitiated(
                endpointId: String,
                connectionInfo: ConnectionInfo
            ) {
                endpointNames[endpointId] =
                    connectionInfo.endpointName

                connectionsClient
                    .acceptConnection(
                        endpointId,
                        payloadCallback
                    )
                    .addOnSuccessListener {
                        sendHandshake(endpointId)
                    }
                    .addOnFailureListener {
                        listener.onError(
                            "Accept connection failed: ${
                                it.message ?: "unknown error"
                            }"
                        )
                    }
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {
                connectingEndpoints.remove(endpointId)

                if (result.status.isSuccess) {
                    sendHandshake(endpointId)
                } else {
                    listener.onError(
                        "Connection failed: ${
                            result.status.statusMessage
                        }"
                    )
                }
            }

            override fun onDisconnected(
                endpointId: String
            ) {
                connectingEndpoints.remove(endpointId)
                connectedEndpoints.remove(endpointId)
                handshakeEndpoints.remove(endpointId)

                handshakeTimeouts
                    .remove(endpointId)
                    ?.let(handler::removeCallbacks)

                endpointNames.remove(endpointId)

                listener.onPeerDisconnected(endpointId)
            }
        }

    private val discoveryCallback =
        object : EndpointDiscoveryCallback() {

            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo
            ) {
                val remoteName = info.endpointName

                endpointNames[endpointId] = remoteName

                listener.onPeerDiscovered(
                    endpointId,
                    remoteName
                )

                val wanted = targetMeshId

                if (
                    wanted != null &&
                    !remoteName.equals(
                        wanted,
                        ignoreCase = true
                    )
                ) {
                    listener.onPeerLost(endpointId)
                    return
                }

                if (
                    connectedEndpoints.contains(endpointId) ||
                    !connectingEndpoints.add(endpointId)
                ) {
                    return
                }

                if (
                    !remoteName.matches(
                        Regex("MJ-[A-Z0-9]{6}")
                    )
                ) {
                    connectingEndpoints.remove(endpointId)

                    listener.onError(
                        "Ignored device with invalid Mesh ID: $remoteName"
                    )

                    return
                }

                connectionsClient
                    .requestConnection(
                        meshId,
                        endpointId,
                        connectionLifecycleCallback
                    )
                    .addOnFailureListener {
                        connectingEndpoints.remove(endpointId)

                        listener.onError(
                            "Request connection failed: ${
                                it.message ?: "unknown error"
                            }"
                        )
                    }
            }

            override fun onEndpointLost(
                endpointId: String
            ) {
                endpointNames.remove(endpointId)
                connectingEndpoints.remove(endpointId)
                handshakeEndpoints.remove(endpointId)

                handshakeTimeouts
                    .remove(endpointId)
                    ?.let(handler::removeCallbacks)

                listener.onPeerLost(endpointId)
            }
        }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!started) return

            retryPendingMessages()

            handler.postDelayed(
                this,
                RETRY_INTERVAL_MS
            )
        }
    }

    @Synchronized
    fun start() {
        if (started) {
            return
        }

        started = true

        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(
            retryRunnable,
            RETRY_INTERVAL_MS
        )

        val advertisingOptions =
            com.google.android.gms.nearby.connection.AdvertisingOptions
                .Builder()
                .setStrategy(STRATEGY)
                .build()

        val discoveryOptions =
            com.google.android.gms.nearby.connection.DiscoveryOptions
                .Builder()
                .setStrategy(STRATEGY)
                .build()

        connectionsClient
            .startAdvertising(
                meshId,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            )
            .addOnFailureListener { error ->
                val message =
                    error.message ?: "unknown error"

                if (
                    !message.contains(
                        "already advertising",
                        ignoreCase = true
                    )
                ) {
                    listener.onError(
                        "Advertising failed: $message"
                    )
                }
            }

        connectionsClient
            .startDiscovery(
                SERVICE_ID,
                discoveryCallback,
                discoveryOptions
            )
            .addOnFailureListener { error ->
                val message =
                    error.message ?: "unknown error"

                if (
                    !message.contains(
                        "already discovering",
                        ignoreCase = true
                    )
                ) {
                    listener.onError(
                        "Discovery failed: $message"
                    )
                }
            }
    }

    private fun encryptionPublicKeyForMeshId(
        destination: String
    ): PublicKey? {
        val endpointId = endpointNames.entries
            .firstOrNull {
                it.value.equals(destination, ignoreCase = true)
            }
            ?.key
            ?: return null

        val publicKeyBase64 =
            endpointPublicKeys[endpointId]
                ?: return null

        return try {
            val keyBytes = Base64.decode(
                publicKeyBase64,
                Base64.NO_WRAP
            )

            KeyFactory.getInstance("RSA")
                .generatePublic(
                    X509EncodedKeySpec(keyBytes)
                )
        } catch (_: Exception) {
            null
        }
    }

    fun sendMessage(message: String) {
        sendMeshMessage(
            destination = targetMeshId,
            message = message
        )
    }

    fun sendMeshMessage(
        destination: String?,
        message: String
    ) {
        if (message.isEmpty()) {
            return
        }

        val normalizedDestination =
            destination
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf {
                    it.matches(
                        Regex("MJ-[A-Z0-9]{6}")
                    )
                }

        val encryptedBody =
            if (normalizedDestination != null) {
                val publicKey =
                    encryptionPublicKeyForMeshId(
                        normalizedDestination
                    )
                    ?: run {
                        listener.onError(
                            "Encryption key for destination is not available"
                        )
                        return
                    }

                try {
                    identity.encryptMessage(
                        publicKey,
                        message.toByteArray(Charsets.UTF_8)
                    )
                } catch (_: Exception) {
                    listener.onError(
                        "Failed to encrypt mesh message"
                    )
                    return
                }
            } else {
                message
            }

        val meshMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            source = meshId,
            destination = normalizedDestination,
            ttl = MAX_TTL,
            body = encryptedBody,
            encrypted = normalizedDestination != null,
            signature = ""
        )

        val signedMeshMessage =
            meshMessage.copy(
                signature = signMessage(meshMessage)
            )

        rememberMessage(signedMeshMessage.id)
        messageReturnRoutes.remove(signedMeshMessage.id)

        if (normalizedDestination != null && connectedEndpoints.isEmpty()) {
            queuePendingMessage(signedMeshMessage)
            return
        }

        val payload = Payload.fromBytes(
            encodeMessage(signedMeshMessage)
                .toByteArray(Charsets.UTF_8)
        )

        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(
                endpointId,
                payload
            )
        }

        if (normalizedDestination != null) {
            queuePendingMessage(signedMeshMessage)
        }
    }

    @Synchronized
    fun stop() {
        started = false
        handler.removeCallbacks(retryRunnable)

        handshakeTimeouts
            .values
            .forEach(handler::removeCallbacks)

        handshakeTimeouts.clear()

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        connectedEndpoints.clear()
        connectingEndpoints.clear()
        handshakeEndpoints.clear()
        endpointNames.clear()
    }
}
