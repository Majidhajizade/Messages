package com.majidhajizade.messages

object MeshSession {

    var manager: OfflineMeshManager? = null

    var activeChatId: String? = null

    var activeMessageHandler:
        ((senderId: String, message: String) -> Unit)? = null

    var targetConnected: Boolean = false
}
