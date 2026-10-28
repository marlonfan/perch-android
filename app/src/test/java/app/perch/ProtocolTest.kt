package app.perch

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun serverValidationRejectsEmbeddedSecretsAndNonHttpSchemes() {
        assertNull(Protocol.server("file:///tmp/a"))
        assertNull(Protocol.server("https://name:secret@example.com"))
        assertNull(Protocol.server("https://example.com?token=secret"))
        assertEquals("https://example.com/prefix", Protocol.server(" https://example.com/prefix/ "))
    }
    @Test fun acceptsOnlyValidTopicPaths() {
        assertTrue(Protocol.topic("personal_123-private"))
        listOf("", "../x", "a,b", "topic/name", "docs", "has space").forEach { assertFalse(it, Protocol.topic(it)) }
    }
    @Test fun handlesNtfyStreamControlAndMessageEvents() {
        assertNull(Protocol.parse("{\"event\":\"keepalive\"}", "https://example.com"))
        assertNull(Protocol.parse("{\"event\":\"open\"}", "https://example.com"))
        assertNull(Protocol.parse("{\"event\":\"message\"}", "https://example.com"))
        val message = Protocol.parse("{\"event\":\"message\",\"id\":\"abc\",\"topic\":\"build\",\"message\":\"**done**\",\"time\":100,\"priority\":5,\"markdown\":true}", "https://example.com")!!
        assertEquals("abc", message.id); assertEquals(5, message.priority); assertTrue(message.markdown)
        assertEquals("https://example.com", message.server); assertEquals("新消息", message.title)
    }
    @Test fun previewRemovesMarkdownWithoutShowingCodeBodies() {
        assertEquals("Build passed [代码]", Protocol.preview("## Build **passed**\n```bash\nsecret command\n```"))
        assertEquals("Read docs", Protocol.preview("Read [docs](https://example.com)"))
    }
    @Test fun recognizesNtfyMarkdownContentType() {
        val m = Protocol.parse("{\"event\":\"message\",\"id\":\"md\",\"topic\":\"test\",\"content_type\":\"text/markdown; charset=utf-8\",\"message\":\"# Hello\"}", "https://example.com")!!
        assertTrue(m.markdown)
    }
}
