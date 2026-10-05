package com.majidhajizade.messages

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

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
            "MESSAGES_MESH_HANDSHAKE|v1|"

        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    private val handler = Handler(Looper.getMainLooper())

    private val connectedEndpoints = mutableSetOf<String>()
    private val connectingEndpoints = mutableSetOf<String>()
    private val handshakeEndpoints = mutableSetOf<String>()
    private val endpointNames = mutableMapOf<String, String>()

    @Volatile
    private var targetMeshId: String? = null

    fun setTargetMeshId(meshId: String?) {
        targetMeshId = meshId
            ?.trim()
            ?.uppercase(java.util.Locale.US)
            ?.takeIf { it.matches(Regex("MJ-[A-Z0-9]{6}")) }

        connectingEndpoints.forEach { endpointId ->
            if (
                targetMeshId != null &&
                endpointNames[endpointId] != targetMeshId
            ) {
                connectionsClient.disconnectFromEndpoint(endpointId)
            }
        }
    }

    fun getTargetMeshId(): String? = targetMeshId

    private val handshakeTimeouts = mutableMapOf<String, Runnable>()

    private fun sendHandshake(endpointId: String) {
        val handshake = Payload.fromBytes(
            "$HANDSHAKE_PREFIX$meshId".toByteArray(Charsets.UTF_8)
        )

        connectionsClient.sendPayload(endpointId, handshake)
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
                    "Mesh handshake timeout: ${endpointNames[endpointId] ?: endpointId}"
                )
                connectionsClient.disconnectFromEndpoint(endpointId)
            }
        }

        handshakeTimeouts[endpointId] = timeout
        handler.postDelayed(timeout, 8000)
    }

    private fun completeHandshake(
        endpointId: String,
        remoteMeshId: String
    ) {
        if (!remoteMeshId.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            listener.onError(
                "Invalid Mesh ID received from ${endpointNames[endpointId] ?: endpointId}"
            )
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        handshakeTimeouts.remove(endpointId)?.let(handler::removeCallbacks)

        if (handshakeEndpoints.add(endpointId)) {
            connectedEndpoints.add(endpointId)

            listener.onPeerConnected(
                endpointId,
                remoteMeshId
            )
        }
    }

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(
            endpointId: String,
            payload: Payload
        ) {
            val bytes = payload.asBytes() ?: return
            val message = bytes.toString(Charsets.UTF_8)

            if (message.startsWith(HANDSHAKE_PREFIX)) {
                val remoteMeshId =
                    message.removePrefix(HANDSHAKE_PREFIX).trim()

                completeHandshake(
                    endpointId,
                    remoteMeshId
                )
                return
            }

            if (!connectedEndpoints.contains(endpointId)) {
                return
            }

            listener.onMessage(
                endpointId,
                message
            )
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

                connectionsClient.acceptConnection(
                    endpointId,
                    payloadCallback
                ).addOnSuccessListener {
                    sendHandshake(endpointId)
                }.addOnFailureListener {
                    listener.onError(
                        "Accept connection failed: ${it.message ?: "unknown error"}"
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
                        "Connection failed: ${result.status.statusMessage}"
                    )
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectingEndpoints.remove(endpointId)
                connectedEndpoints.remove(endpointId)
                handshakeEndpoints.remove(endpointId)

                handshakeTimeouts.remove(endpointId)?.let(handler::removeCallbacks)

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

                val wantedMeshId = targetMeshId

                if (
                    wantedMeshId != null &&
                    !remoteName.equals(wantedMeshId, ignoreCase = true)
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

                if (!remoteName.matches(Regex("MJ-[A-Z0-9]{6}"))) {
                    connectingEndpoints.remove(endpointId)
                    listener.onError(
                        "Ignored device with invalid Mesh ID: $remoteName"
                    )
                    return
                }

                connectionsClient.requestConnection(
                    meshId,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener {
                    connectingEndpoints.remove(endpointId)

                    listener.onError(
                        "Request connection failed: ${it.message ?: "unknown error"}"
                    )
                }
            }

            override fun onEndpointLost(endpointId: String) {
                endpointNames.remove(endpointId)
                connectingEndpoints.remove(endpointId)
                handshakeEndpoints.remove(endpointId)

                handshakeTimeouts.remove(endpointId)?.let(handler::removeCallbacks)

                listener.onPeerLost(endpointId)
            }
        }

    fun start() {
        connectionsClient.startAdvertising(
            meshId,
            SERVICE_ID,
            connectionLifecycleCallback,
            com.google.android.gms.nearby.connection.AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()
        ).addOnFailureListener {
            listener.onError(
                "Advertising failed: ${it.message ?: "unknown error"}"
            )
        }

        connectionsClient.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            com.google.android.gms.nearby.connection.DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()
        ).addOnFailureListener {
            listener.onError(
                "Discovery failed: ${it.message ?: "unknown error"}"
            )
        }
    }

    fun sendMessage(message: String) {
        if (message.isEmpty()) return

        val payload = Payload.fromBytes(
            message.toByteArray(Charsets.UTF_8)
        )

        connectedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(
                endpointId,
                payload
            )
        }
    }

    fun stop() {
        handshakeTimeouts.values.forEach(handler::removeCallbacks)
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
