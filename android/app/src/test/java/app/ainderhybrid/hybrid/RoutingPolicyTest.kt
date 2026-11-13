package app.ainderhybrid.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {

    @Test fun `every private task is pinned to ONLY_ON_DEVICE`() {
        val privateTasks = AiTask.entries.filter { it.privacy == Privacy.PRIVATE }
        assertEquals(setOf(AiTask.CARD, AiTask.BRIEF, AiTask.RERANK, AiTask.GUARD, AiTask.MEMORY), privateTasks.toSet())
        privateTasks.forEach { assertEquals(it.name, Route.ONLY_ON_DEVICE, it.route) }
    }

    @Test fun `public tasks use the design modes`() {
        assertEquals(Route.PREFER_ON_DEVICE, AiTask.QUIZ.route)
        assertEquals(Route.PREFER_IN_CLOUD, AiTask.JUDGE.route)
        assertEquals(Route.PREFER_IN_CLOUD, AiTask.LETTER.route)
    }

    @Test fun `private task never runs when on-device is not available`() {
        for (task in AiTask.entries.filter { it.privacy == Privacy.PRIVATE }) {
            for (state in OnDeviceState.entries.filter { it != OnDeviceState.AVAILABLE }) {
                val d = RoutingPolicy.decide(task, aiConfigured = true, onDevice = state)
                assertTrue("$task/$state", d is Decision.UseFallback)
                assertEquals(task.fallback, (d as Decision.UseFallback).fallback)
            }
            assertEquals(Decision.Run(Route.ONLY_ON_DEVICE), RoutingPolicy.decide(task, true, OnDeviceState.AVAILABLE))
        }
    }

    @Test fun `public task runs with its own mode whatever the on-device state`() {
        for (task in AiTask.entries.filter { it.privacy == Privacy.PUBLIC }) {
            for (state in OnDeviceState.entries) {
                assertEquals(Decision.Run(task.route), RoutingPolicy.decide(task, true, state))
            }
        }
    }

    @Test fun `nothing runs when Firebase is not configured`() {
        AiTask.entries.forEach { assertTrue(RoutingPolicy.decide(it, false, OnDeviceState.AVAILABLE) is Decision.UseFallback) }
    }

    @Test fun `private results are accepted only from the device`() {
        assertTrue(RoutingPolicy.accept(AiTask.GUARD, Source.ON_DEVICE))
        assertFalse(RoutingPolicy.accept(AiTask.GUARD, Source.IN_CLOUD))
        assertTrue(RoutingPolicy.accept(AiTask.LETTER, Source.IN_CLOUD))
        assertTrue(RoutingPolicy.accept(AiTask.LETTER, Source.ON_DEVICE))
    }

    @Test fun `cloud chars count only cloud calls`() {
        assertEquals(5, RoutingPolicy.cloudChars(Source.IN_CLOUD, "hello"))
        assertEquals(0, RoutingPolicy.cloudChars(Source.ON_DEVICE, "hello"))
    }
}
