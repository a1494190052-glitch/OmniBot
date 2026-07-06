package cn.com.omnimind.assists.task.vlmserver

object VLMToolDenylistRegistry {
    @Volatile private var denylist: Set<String> = emptySet()
    fun set(tools: Set<String>) { denylist = tools }
    fun get(): Set<String> = denylist
}
