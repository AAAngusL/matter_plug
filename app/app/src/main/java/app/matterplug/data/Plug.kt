package app.matterplug.data

/** A plug paired onto the app's fabric. [nodeId] is its address on the fabric; [endpoint] is its On/Off endpoint. */
data class Plug(val nodeId: Long, val endpoint: Int, val name: String)
