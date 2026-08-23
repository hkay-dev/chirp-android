package dev.chirpboard.app.feature.llm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class LlmResponseGuardsTest {
    @Test
    fun `whole call timeout is not retried`() {
        // OkHttp's callTimeout expiry: a bare InterruptedIOException with message "timeout".
        assertFalse(LlmResponseGuards.isRetriableIoFailure(InterruptedIOException("timeout")))
    }

    @Test
    fun `single socket stall is retried`() {
        assertTrue(LlmResponseGuards.isRetriableIoFailure(SocketTimeoutException("connect timed out")))
        assertTrue(LlmResponseGuards.isRetriableIoFailure(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun `ordinary transport failures stay retriable`() {
        assertTrue(LlmResponseGuards.isRetriableIoFailure(ConnectException("Connection refused")))
        assertTrue(LlmResponseGuards.isRetriableIoFailure(EOFException()))
        assertTrue(LlmResponseGuards.isRetriableIoFailure(IOException("unexpected end of stream")))
    }

    @Test
    fun `anthropic truncation is detected only on max_tokens`() {
        assertTrue(LlmResponseGuards.isAnthropicTruncated("max_tokens"))
        assertFalse(LlmResponseGuards.isAnthropicTruncated("end_turn"))
        assertFalse(LlmResponseGuards.isAnthropicTruncated("stop_sequence"))
        assertFalse(LlmResponseGuards.isAnthropicTruncated(null))
    }

    @Test
    fun `openai truncation is detected only on length`() {
        assertTrue(LlmResponseGuards.isOpenAiTruncated("length"))
        assertFalse(LlmResponseGuards.isOpenAiTruncated("stop"))
        assertFalse(LlmResponseGuards.isOpenAiTruncated(null))
    }

    @Test
    fun `gemini accepts only STOP or an absent reason`() {
        assertFalse(LlmResponseGuards.isGeminiIncomplete("STOP"))
        assertFalse(LlmResponseGuards.isGeminiIncomplete(null))
        assertFalse(LlmResponseGuards.isGeminiIncomplete(""))
        assertTrue(LlmResponseGuards.isGeminiIncomplete("MAX_TOKENS"))
        assertTrue(LlmResponseGuards.isGeminiIncomplete("SAFETY"))
        assertTrue(LlmResponseGuards.isGeminiIncomplete("RECITATION"))
        assertTrue(LlmResponseGuards.isGeminiIncomplete("OTHER"))
    }

    @Test
    fun `output cap is large enough for a long transcript enhancement`() {
        assertEquals(16_384, LlmChatService.MAX_OUTPUT_TOKENS)
    }
}
