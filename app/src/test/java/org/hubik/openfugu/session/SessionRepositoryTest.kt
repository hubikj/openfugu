package org.hubik.openfugu.session

import kotlinx.coroutines.runBlocking
import org.hubik.openfugu.storage.FileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory FileStore so repository logic is tested without disk or Android. */
private class FakeFileStore : FileStore {
    val files = mutableMapOf<String, String>()
    override fun readText(name: String): String? = files[name]
    override fun writeTextAtomic(name: String, text: String) { files[name] = text }
    override fun delete(name: String) { files.remove(name) }
    override fun list(): List<String> = files.keys.toList()
}

class SessionRepositoryTest {

    private fun gameSession(id: String, timestamp: Long) = Session.GameSession(
        id = id, type = SessionType.REEF_GAME, timestamp = timestamp,
        durationMs = 1000L, deviceName = "eFugu", userName = null,
        pressureTrace = emptyList(), score = 7
    )

    @Test
    fun `save then load returns the session and updates the index`() = runBlocking {
        val store = FakeFileStore()
        val repo = SessionRepository(store)
        val session = gameSession("a", timestamp = 100L)

        assertTrue(repo.saveSession(session))
        assertEquals(session, repo.loadSession("a"))
        assertEquals(listOf("a"), repo.loadIndex().map { it.id })
    }

    @Test
    fun `delete removes session file and index entry`() = runBlocking {
        val store = FakeFileStore()
        val repo = SessionRepository(store)
        repo.saveSession(gameSession("a", 100L))
        repo.deleteSession("a")

        assertNull(repo.loadSession("a"))
        assertTrue(repo.loadIndex().isEmpty())
        assertFalse(store.files.keys.any { it.startsWith("session_a") })
    }

    @Test
    fun `index is rebuilt from session files when missing`() = runBlocking {
        val store = FakeFileStore()
        SessionRepository(store).saveSession(gameSession("a", 100L))
        store.files.remove("sessions_index.json")

        // Fresh repository instance: no cached index, must rebuild from files.
        val entries = SessionRepository(store).loadIndex()
        assertEquals(listOf("a"), entries.map { it.id })
    }

    @Test
    fun `oldest sessions beyond the cap are deleted on save`() = runBlocking {
        val store = FakeFileStore()
        val repo = SessionRepository(store)
        repeat(51) { i -> repo.saveSession(gameSession("s$i", timestamp = i.toLong())) }

        val index = repo.loadIndex()
        assertEquals(50, index.size)
        // The oldest (timestamp 0) fell off; its file is gone too.
        assertFalse(index.any { it.id == "s0" })
        assertNull(repo.loadSession("s0"))
    }

    @Test
    fun `corrupt session file is skipped, not fatal`() = runBlocking {
        val store = FakeFileStore()
        val repo = SessionRepository(store)
        store.files["session_bad.json"] = "{not json"
        repo.saveSession(gameSession("good", 100L))

        assertEquals(listOf("good"), repo.loadIndex().map { it.id })
        assertNull(repo.loadSession("bad"))
    }
}
