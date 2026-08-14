package com.yunjue.echo.mind.me

import com.yunjue.echo.mind.intelligence.ProviderCredentialStore
import com.yunjue.echo.mind.intelligence.ProviderType
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 13.1 §103 — Me 状态装配矩阵：
 * provider / permission / sensing / microphone / memory / data rights / support。
 */
class MeStateAssemblyTest {

    private fun memory(type: MemoryType, confidence: Float, content: String = "记忆") = EchoMemory(
        id = "id-${type.name}-$confidence",
        userId = "user-1",
        type = type,
        content = content,
        source = "test",
        confidence = confidence,
        createdAt = 1L,
        lastConfirmedAt = 1L,
        importance = 50,
        retentionClass = com.yunjue.echo.mind.memory.RetentionClass.LONG_TERM,
        provenance = "test",
    )

    private fun meInputs(
        sensingEnabled: Boolean = true,
        micEnabled: Boolean = false,
        pendingCount: Int = 0,
        memories: List<EchoMemory> = emptyList(),
        message: String? = null,
        showSupportConfirm: Boolean = false,
        sync: MeSyncInputs = MeSyncInputs(),
        presence: MePresenceSummary = MePresenceSummary(),
        storedProvider: ProviderCredentialStore.Stored? = null,
    ) = MeAssemblyInputs(
        sensingEnabled = sensingEnabled,
        micEnabled = micEnabled,
        pendingCount = pendingCount,
        memories = memories,
        message = message,
        showSupportConfirm = showSupportConfirm,
        sync = sync,
        presence = presence,
        storedProvider = storedProvider,
    )

    // ===== §103 provider =====

    @Test
    fun providerSummaryReflectsStoredConfig() {
        val none = assembleMeUiState(meInputs())
        assertFalse(none.intelligence.providerConfigured)
        assertNull(none.intelligence.model)

        val withProvider = assembleMeUiState(
            meInputs(
                storedProvider = ProviderCredentialStore.Stored(
                    type = ProviderType.OPENAI_COMPATIBLE,
                    displayName = "My AI",
                    baseUrl = "https://api.openai.com",
                    model = "gpt-test",
                    apiKey = "sk-test",
                )
            )
        )
        assertTrue(withProvider.intelligence.providerConfigured)
        assertEquals("gpt-test", withProvider.intelligence.model)
        assertEquals("https://api.openai.com", withProvider.intelligence.baseUrl)
    }

    // ===== §103 memory =====

    @Test
    fun memorySummaryCountsCategories() {
        val memories = listOf(
            memory(MemoryType.CORRECTION, 0.9f),
            memory(MemoryType.USER_CONFIRMED, 0.9f),
            memory(MemoryType.CONTEXT, 0.3f), // 低置信 → uncertain
            memory(MemoryType.OBSERVATION, 0.9f),
        )
        val state = assembleMeUiState(meInputs(memories = memories))
        assertEquals(4, state.memory.total)
        assertEquals(1, state.memory.corrections)
        assertEquals(1, state.memory.uncertain)
        assertEquals(2, state.memory.confirmed)
    }

    @Test
    fun memoryGroupFilterWorks() {
        val memories = listOf(
            memory(MemoryType.CORRECTION, 0.9f, "纠正"),
            memory(MemoryType.CONTEXT, 0.8f, "出差"),
            memory(MemoryType.OBSERVATION, 0.3f, "低置信观察"),
        )
        val all = groupMemories(memories, null)
        assertEquals(3, all.corrections.size + all.confirmed.size + all.uncertain.size)
        val onlyContext = groupMemories(memories, MemoryType.CONTEXT)
        assertEquals(1, onlyContext.confirmed.size)
        assertEquals("出差", onlyContext.confirmed[0].content)
        val onlyCorrections = groupMemories(memories, MemoryType.CORRECTION)
        assertEquals(1, onlyCorrections.corrections.size)
    }

    // ===== §103 sensing / microphone / data rights =====

    @Test
    fun dataAndSensingStateCarriesSensingMicAndRights() {
        val caps = mapOf(
            SensingCapability.SENSOR to CapabilityState.AVAILABLE,
            SensingCapability.USAGE to CapabilityState.DENIED,
            SensingCapability.NOTIFICATION to CapabilityState.UNAVAILABLE,
        )
        val state = assembleDataAndSensingUiState(
            DataAndSensingAssemblyInputs(
                sensing = SensingUiInputs(enabled = true, reEnabling = true),
                mic = MicUiInputs(enabled = false, showConfirm = true),
                eveningReminderEnabled = true,
                capabilityStates = caps,
                pendingCount = 3,
                sync = MeSyncInputs(
                    networkAvailable = false, retrying = true,
                    lastCollectionTs = 111L, lastSyncTs = 222L, lastPartialSyncTs = 333L,
                    lastPersistenceFailureTs = 444L, consecutiveFailures = 2,
                ),
                rights = DataRightsInputs(
                    localWindows = 5, localPortraits = 7, localMode = true,
                    institutionCode = "", userId = "user-1",
                ),
                message = "已停止",
            )
        )
        assertTrue(state.sensingEnabled)
        assertTrue(state.reEnabling)
        assertTrue(state.showMicConfirm)
        assertFalse(state.micEnabled)
        assertTrue(state.eveningReminderEnabled)
        assertEquals(CapabilityState.DENIED, state.capabilityStates[SensingCapability.USAGE])
        assertFalse(state.online)
        assertTrue(state.syncLabel.isNotBlank())
        assertEquals(2, state.consecutiveFailures)
        assertEquals(5, state.localWindows)
        assertEquals(7, state.localPortraits)
        assertTrue(state.localMode)
        assertEquals("user-1", state.userId)
        assertEquals("已停止", state.message)
    }

    // ===== §103 support =====

    @Test
    fun supportSummaryCarriesConfirmStateAndMessage() {
        val state = assembleMeUiState(
            meInputs(sensingEnabled = false, pendingCount = 2, message = "支持请求已保存", showSupportConfirm = true)
        )
        assertTrue(state.showSupportConfirm)
        assertEquals("支持请求已保存", state.message)
        assertEquals(2, state.sync.pendingCount)
        assertFalse(state.sensingEnabled)
    }

    // ===== §103 presence summary =====

    @Test
    fun presenceSummaryCarriesVisualPreferences() {
        val state = assembleMeUiState(
            meInputs(
                presence = MePresenceSummary(
                    motionLevel = "LIVELY", nightMode = true, reduceMotion = true, suggestionsEnabled = false
                )
            )
        )
        assertEquals("LIVELY", state.presence.motionLevel)
        assertTrue(state.presence.nightMode)
        assertTrue(state.presence.reduceMotion)
        assertFalse(state.presence.suggestionsEnabled)
    }
}
