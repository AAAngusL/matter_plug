package app.matterplug.data

/** A plug paired onto the app's fabric. [nodeId] is its address on the fabric; [endpoint] is its On/Off endpoint. */
data class Plug(val nodeId: Long, val endpoint: Int, val name: String)

/** The plug with [nodeId], or the first plug when that id is null or no longer exists (removed plugs never leave a dead selection). */
fun List<Plug>.resolve(nodeId: Long?): Plug? = firstOrNull { it.nodeId == nodeId } ?: firstOrNull()
