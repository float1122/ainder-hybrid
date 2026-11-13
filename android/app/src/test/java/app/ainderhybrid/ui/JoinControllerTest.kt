package app.ainderhybrid.ui

import app.ainderhybrid.ainder.AinderClient
import app.ainderhybrid.ainder.PublicCardDraft
import app.ainderhybrid.hybrid.Agent
import app.ainderhybrid.hybrid.FakeEngine
import app.ainderhybrid.hybrid.HybridAi
import app.ainderhybrid.hybrid.HybridLog
import app.ainderhybrid.hybrid.Prompts
import app.ainderhybrid.vault.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

class JoinControllerTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = mutableListOf<Pair<Identity, String?>>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private fun ok(data: String) = MockResponse().setBody("""{"ok":true,"data":$data,"error":null}""")

    /** Runs the whole sign-up chain against a fake server; [vaultAccepts] simulates the vault result. */
    private fun joinWith(vaultAccepts: Boolean): JoinStep = runBlocking {
        server.enqueue(ok("""{"token":"t1","termsVersion":"v","next":"/join/terms?token=t1"}"""))
        server.enqueue(ok("""{"terms":"TERMS","termsVersion":"v","instruction":"x"}"""))
        val passage = Base64.getEncoder().encodeToString("Love is still love.".toByteArray())
        server.enqueue(ok("""{"message":"m","passageB64":"$passage","question":"What? (one word)"}"""))
        repeat(4) { server.enqueue(ok("""{"next":"/n"}""")) }
        server.enqueue(ok("""{"apiKey":"$FAKE_KEY","claimCode":"ABC-1234","next":"/api/v1/deck"}"""))
        server.enqueue(ok("""{"id":"ai-1","name":"[test] Nova"}"""))

        val client = AinderClient(server.url("/").toString(), OkHttpClient(), "AinderHybrid/test (android)") { null }
        // No Firebase config: the quiz goes to the human, so the test types the answer itself.
        val agent = Agent(HybridAi(FakeEngine(configured = false), HybridLog()), Prompts { File("../../shared/$it").readText() })
        val join = JoinController(client, agent, scope, onJoined = { id, replaces -> stored += id to replaces; vaultAccepts })

        join.approveCard(PublicCardDraft("[test] Nova", "Gemini", "writes haiku", "calm poet", "calm", listOf("tea"), "Earth"))
        val terms = withTimeout(10_000) { join.step.first { it is JoinStep.Terms } } as JoinStep.Terms
        join.consent(terms.token, "agree", attest = true)
        val quiz = withTimeout(10_000) { join.step.first { it is JoinStep.Quiz } } as JoinStep.Quiz
        join.submitQuiz(quiz, "romance")
        withTimeout(10_000) { join.step.first { it is JoinStep.Done || it is JoinStep.Failed } }
    }

    @Test fun `key that the vault refused is shown once for copying, not claimed as stored`() {
        val done = joinWith(vaultAccepts = false) as JoinStep.Done
        assertFalse(done.keyStored)
        assertEquals(FAKE_KEY, done.revealedKey)
        assertEquals("ABC-1234", done.claimCode)
        assertEquals("no replace attempt after a failed store", 1, stored.size)
        assertFalse("key must not leak into logs via toString", done.toString().contains(FAKE_KEY))
    }

    @Test fun `stored key is not revealed and provisional entry is replaced by the real id`() {
        val done = joinWith(vaultAccepts = true) as JoinStep.Done
        assertTrue(done.keyStored)
        assertNull(done.revealedKey)
        assertEquals(listOf("pending-ABC-1234" to null, "ai-1" to "pending-ABC-1234"), stored.map { it.first.id to it.second })
    }

    private companion object {
        val FAKE_KEY = "aind_" + "0".repeat(48)
    }
}
