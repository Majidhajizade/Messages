package com.majidhajizade.messages

object MeshSession {

    var manager: OfflineMeshManager? = null

    var activeChatId: String? = null

    var activeMessageHandler:
        ((senderId: String, message: String) -> Unit)? = null

    var targetConnected: Boolean = false

    var logHandler: ((String) -> Unit)? = null

    fun log(message: String) {
        logHandler?.invoke(message)
    }
}
