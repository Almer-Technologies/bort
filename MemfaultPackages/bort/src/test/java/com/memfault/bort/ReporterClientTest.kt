package com.memfault.bort

import com.memfault.bort.shared.VersionRequest
import com.memfault.bort.shared.VersionResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReporterClientTest {
    lateinit var mockConnection: ReporterServiceConnection
    lateinit var client: ReporterClient

    @BeforeEach
    fun setUp() {
        mockConnection = mockk()
        client = ReporterClient(mockConnection, mockk())
    }

    @Test
    fun getVersion() {
        runBlocking {
            coEvery {
                mockConnection.sendAndReceive(VersionRequest())
            } coAnswers {
                VersionResponse(123)
            }
            for (x in 1..3) {
                assertEquals(123, client.getVersion())
            }
            // Version is cached for subsequent getVersion() calls:
            coVerify(exactly = 1) { mockConnection.sendAndReceive(VersionRequest()) }
        }
    }

    @Test
    fun unsupportedVersion() {
        runBlocking {
            coEvery {
                mockConnection.sendAndReceive(VersionRequest())
            } coAnswers {
                VersionResponse(0)
            }
            assertEquals(false, client.dropBoxSetTagFilter(emptyList()))
        }
    }
}
