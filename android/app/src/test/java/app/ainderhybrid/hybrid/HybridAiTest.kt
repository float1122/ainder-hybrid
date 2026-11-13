package app.ainderhybrid.hybrid

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridAiTest {

    @Test fun `private task with no on-device model never calls the engine`() = runTest {
        val engine = FakeEngine(onDevice = OnDeviceState.UNAVAILABLE)
        val ai = HybridAi(engine, HybridLog())
        val out = ai.run(AiTask.GUARD, "secret notes")
        assertTrue(out is AiOutcome.UseFallback)
        assertTrue(engine.calls.isEmpty())
        assertEquals(Source.NO_AI, ai.log.entries.value.single().source)
        assertEquals(0, ai.log.entries.value.single().cloudChars)
    }

    @Test fun `private task runs ONLY_ON_DEVICE`() = runTest {
        val engine = FakeEngine(answer = { _, _ -> """{"ok":true}""" })
        val out = HybridAi(engine, HybridLog()).run(AiTask.GUARD, "notes")
        assertEquals(Route.ONLY_ON_DEVICE, engine.calls.single().route)
        assertEquals(Source.ON_DEVICE, (out as AiOutcome.Done).result.source)
        assertEquals(0, out.result.cloudChars)
    }

    @Test fun `a cloud answer to a private task is discarded and the leak is logged`() = runTest {
        val engine = FakeEngine(sourceFor = { Source.IN_CLOUD })
        val ai = HybridAi(engine, HybridLog())
        val out = ai.run(AiTask.MEMORY, "abcd")
        assertTrue(out is AiOutcome.UseFallback)
        assertEquals(4, ai.log.cloudCharsFor(Privacy.PRIVATE))
    }

    @Test fun `public task goes through its preferred mode and counts cloud chars`() = runTest {
        val engine = FakeEngine(onDevice = OnDeviceState.UNAVAILABLE)
        val ai = HybridAi(engine, HybridLog())
        val out = ai.run(AiTask.LETTER, "public prompt")
        assertEquals(Route.PREFER_IN_CLOUD, engine.calls.single().route)
        assertEquals(13, (out as AiOutcome.Done).result.cloudChars)
        assertEquals(13, ai.log.cloudCharsFor(Privacy.PUBLIC))
    }

    @Test fun `offline fallback to the device is reported as on-device`() = runTest {
        // PREFER_IN_CLOUD while offline: the SDK answers on the device.
        val engine = FakeEngine(sourceFor = { Source.ON_DEVICE })
        val out = HybridAi(engine, HybridLog()).run(AiTask.LETTER, "p")
        assertEquals(Source.ON_DEVICE, (out as AiOutcome.Done).result.source)
        assertEquals(0, out.result.cloudChars)
    }

    @Test fun `engine errors become the task fallback`() = runTest {
        val engine = FakeEngine().apply { throwOnGenerate = IllegalStateException("quota") }
        val out = HybridAi(engine, HybridLog()).run(AiTask.JUDGE, "p")
        assertEquals(Fallback.CODE_RULE, (out as AiOutcome.UseFallback).fallback)
    }

    @Test fun `unconfigured build makes no calls`() = runTest {
        val engine = FakeEngine(configured = false)
        AiTask.entries.forEach { HybridAi(engine, HybridLog()).run(it, "p") }
        assertTrue(engine.calls.isEmpty())
    }
}
