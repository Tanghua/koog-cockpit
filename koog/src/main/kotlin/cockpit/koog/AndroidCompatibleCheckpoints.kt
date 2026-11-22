package cockpit.koog

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.agents.snapshot.providers.PersistenceUtils
import ai.koog.agents.snapshot.providers.filters.AgentCheckpointPredicateFilter
import java.io.File
import java.io.FileOutputStream

/** Host storage adapter: Koog's JVMFileSystemProvider is absent from its Android artifact. */
class AndroidCompatibleCheckpoints(private val root: File) : PersistenceStorageProvider<AgentCheckpointPredicateFilter> {
    private val json = PersistenceUtils.defaultCheckpointJson
    private val lock = Any()

    private fun directory(sessionId: String): File {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        return File(root, "checkpoints/$sessionId").apply { check(mkdirs() || isDirectory) }
    }

    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) {
        synchronized(lock) {
            val id = agentCheckpointData.checkpointId
            require(id.matches(Regex("[A-Za-z0-9_-]+")))
            val dir = directory(sessionId)
            val target = File(dir, "$id.json")
            val temporary = File(dir, "$id.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(json.encodeToString(AgentCheckpointData.serializer(), agentCheckpointData).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(target)) { "checkpoint commit failed" }
        }
    }

    override suspend fun getCheckpoints(sessionId: String, filter: AgentCheckpointPredicateFilter?): List<AgentCheckpointData> = synchronized(lock) {
        directory(sessionId).listFiles().orEmpty().filter { it.extension == "json" }.map {
            json.decodeFromString(AgentCheckpointData.serializer(), it.readText())
        }.filter { filter == null || filter.check(it) }
    }

    override suspend fun getLatestCheckpoint(sessionId: String, filter: AgentCheckpointPredicateFilter?): AgentCheckpointData? =
        getCheckpoints(sessionId, filter).maxByOrNull { it.createdAt }
}
