package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
    @Test fun acceptsAnOriginOrItsApiBase() {
        for (input in listOf("https://Briareus.Example.com", " https://briareus.example.com/ ", "https://briareus.example.com/api/v1", "HTTPS://briareus.example.com/api/v1/")) {
            val a = ServerAddress.parse(input)!!
            assertEquals("https://briareus.example.com", a.origin)
            assertEquals("https://briareus.example.com/api/v1/", a.baseUrl)
        }
    }

    @Test fun keepsAPortButDropsTheDefaultOne() {
        assertEquals("https://h.example:8443", ServerAddress.parse("https://h.example:8443")!!.origin)
        assertEquals("https://h.example", ServerAddress.parse("https://h.example:443/api/v1")!!.origin)
        assertEquals("https://[::1]:9000", ServerAddress.parse("https://[::1]:9000")!!.origin)
    }

    @Test fun refusesAnythingElse() {
        for (input in listOf(
            "http://h.example", "h.example", "https://", "https://user:pw@h.example", "https://h.example/other",
            "https://h.example/api/v1?x=1", "https://h.example#a", "https://h.example:0", "https://h.example:70000",
            "https://h.example:abc", "https://h ex.example", "https://[::1", "https://[::1]x",
        )) assertNull(input, ServerAddress.parse(input))
    }

    @Test fun tokenShape() {
        assertTrue(Token.valid("brm_" + "a".repeat(40) + "-_Z"))
        assertFalse(Token.valid("brm_" + "a".repeat(42)))
        assertFalse(Token.valid("xyz_" + "a".repeat(43)))
        assertFalse(Token.valid("brm_" + "a".repeat(42) + "!"))
        assertFalse(Token.valid("brm_" + "a".repeat(42) + "é"))
    }
}
