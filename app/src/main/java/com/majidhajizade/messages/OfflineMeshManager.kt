package com.majidhajizade.messages

import android.content.Context
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
    private val listener: Listener
) {

    interface Listener {
        fun onPeerConnected(endpointId: String, name: String)
        fun onPeerDisconnected(endpointId: String)
        fun onMessage(endpointId: String, message: String)
        fun onError(message: String)
    }

    companion object {
        private const val SERVICE_ID = "com.majidhajizade.messages.offline"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val connectedEndpoints = mutableSetOf<String>()
    private val connectingEndpoints = mutableSetOf<String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(
            endpointId: String,
            payload: Payload
        ) {
            val bytes = payload.asBytes() ?: return
            listener.onMessage(
                endpointId,
                bytes.toString(Charsets.UTF_8)
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
                connectionsClient.acceptConnection(
                    endpointId,
                    payloadCallback
                ).addOnFailureListener {
                    listener.onError(
                        "Accept connection failed: ${it.message}"
                    )
                }
            }

            override fun onConnectionResult(
                endpointId: String,
                result: ConnectionResolution
            ) {
                connectingEndpoints.remove(endpointId)

                if (result.status.isSuccess) {
                    connectedEndpoints.add(endpointId)
                    listener.onPeerConnected(
                        endpointId,
                        endpointId
                    )
                } else {
                    listener.onError(
                        "Connection failed: ${result.status.statusMessage}"
                    )
                }
            }

            override fun onDisconnected(endpointId: String) {
                connectingEndpoints.remove(endpointId)
                connectedEndpoints.remove(endpointId)
                listener.onPeerDisconnected(endpointId)
            }
        }

    private val discoveryCallback =
        object : EndpointDiscoveryCallback() {

            override fun onEndpointFound(
                endpointId: String,
                info: DiscoveredEndpointInfo
            ) {
                if (connectedEndpoints.contains(endpointId) ||
                    !connectingEndpoints.add(endpointId)
                ) {
                    return
                }

                connectionsClient.requestConnection(
                    "Messages",
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener {
                    connectingEndpoints.remove(endpointId)
                    listener.onError(
                        "Request connection failed: ${it.message}"
                    )
                }
            }

            override fun onEndpointLost(endpointId: String) {
            }
        }

    fun start() {
        connectionsClient.startAdvertising(
            "Messages",
            SERVICE_ID,
            connectionLifecycleCallback,
            com.google.android.gms.nearby.connection.AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()
        ).addOnFailureListener {
            listener.onError(
                "Advertising failed: ${it.message}"
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
                "Discovery failed: ${it.message}"
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
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        connectingEndpoints.clear()
    }
}
