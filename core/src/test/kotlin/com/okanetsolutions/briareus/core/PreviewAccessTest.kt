package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAccessTest {
    @Test fun goesOnlyToPreviewHosts() {
        val suffix = "preview.example.com"
        assertTrue(PreviewAccess.applies("https://8123.preview.example.com", suffix))
        assertTrue(PreviewAccess.applies("https://8123.preview.example.com/app?x=1#y", suffix))
        assertTrue(PreviewAccess.applies("https://a.b.PREVIEW.example.com:8443/", suffix))
        assertTrue(PreviewAccess.applies("https://8123.preview.example.com./", suffix))
        assertTrue(PreviewAccess.applies("HTTPS://8123.preview.example.com", ".preview.example.com"))
        // Not over plain HTTP, not the suffix itself, not a host that merely ends in the same letters, not another site.
        assertFalse(PreviewAccess.applies("http://8123.preview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://preview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://.preview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://evilpreview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://8123.preview.example.com.evil.net", suffix))
        assertFalse(PreviewAccess.applies("https://evil.net/8123.preview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://evil.net?h=8123.preview.example.com", suffix))
        assertFalse(PreviewAccess.applies("https://8123.preview.example.com@evil.net", suffix))
        assertFalse(PreviewAccess.applies("https://8123.preview.example.com", ""))
    }

    @Test fun parsesAndSendsTheHeaders() {
        val access = PreviewAccess.parse(json("""{"clientId":"id.access","clientSecret":"s3cret","hostSuffix":"preview.example.com"}"""))!!
        assertEquals(mapOf("CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "s3cret"), access.headersFor("https://3000.preview.example.com/"))
        assertEquals(emptyMap<String, String>(), access.headersFor("https://github.com/o/a"))
        assertNull(PreviewAccess.parse(json("""{"clientId":"id","clientSecret":"","hostSuffix":"preview.example.com"}""")))
        assertNull(PreviewAccess.parse(json("""{"clientId":"id","clientSecret":"s","hostSuffix":"."}""")))
    }
}
