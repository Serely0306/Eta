package io.github.mangi.eta.data.db

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelUsageDaoTest {
    @Test
    fun periodQueriesKeepOlderProvidersAndAllRowsBeyondThePreviousReportCap() = runBlocking(Dispatchers.IO) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), EtaDatabase::class.java,
        ).build()
        try {
            val dao = database.modelUsageDao()
            dao.insertAll((1L..20_001L).map { time ->
                ModelUsageRecordEntity(
                    createdAt = time, durationMs = 0, source = "test", runId = "run-$time",
                    purpose = "chat", subagentIndex = null,
                    providerId = if (time == 1L) "older-provider" else "busy-provider",
                    providerName = "Test", model = "test", modelDisplayName = "Test",
                    endpoint = "responses", reasoningEffort = "default",
                    status = ModelUsageRecordEntity.STATUS_OK, hasUsage = true,
                    promptTokens = 10, cachedTokens = 0, cacheWriteTokens = 0,
                    outputTokens = 1, reasoningTokens = 0,
                )
            })
            val records = dao.between(0, 20_002)
            assertEquals(20_001, records.size)
            assertEquals("older-provider", records.last().providerId)
            assertEquals(220_011L, records.sumOf { it.promptTokens + it.outputTokens })
            assertEquals(records, dao.betweenFlow(0, 20_002).first())
            assertEquals(1, dao.between(20_001, 20_002).size)
        } finally {
            database.close()
        }
    }
}
