package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    private val openApi = BriareusJson.parseToJsonElement(
        """{"paths":{
            "/sessions":{"get":{"x-briareus-access":"read"},"post":{"x-briareus-access":"manage"}},
            "/sessions/{id}":{"get":{"x-briareus-access":"read"},"delete":{"x-briareus-access":"manage"}},
            "/sessions/{id}/messages":{"post":{"x-briareus-access":"manage"}},
            "/settings/projects":{"get":{}},
            "/pulls/{number}/findings":{"get":{"x-briareus-access":"read"}},
            "/pulls/merged":{"get":{"x-briareus-access":"admin"}}
        }}""",
    ) as JsonObject

    @Test fun readsAccessFromTheOpenApiDocument() {
        val catalog = RouteCatalog.fromOpenApi(openApi)!!
        assertTrue(catalog.allows("sessions", Permission.READ))
        assertFalse(catalog.allows("start_session", Permission.READ))
        assertTrue(catalog.allows("start_session", Permission.MANAGE))
        assertTrue(catalog.allows("message", Permission.ADMIN))
        // A parameter named differently on each side still matches.
        assertTrue(catalog.allows("findings", Permission.READ))
        // A route the server does not list is unavailable to everyone.
        assertFalse(catalog.allows("transcribe", Permission.ADMIN))
        // Without x-briareus-access a route is the operator's.
        assertFalse(catalog.allows("GET", "settings/projects", Permission.MANAGE))
        assertTrue(catalog.allows("GET", "settings/projects", Permission.ADMIN))
        // An unknown permission may do nothing.
        assertFalse(catalog.allows("sessions", Permission.of("owner")))
    }

    @Test fun aLiteralSegmentWinsOverAParameter() {
        val catalog = RouteCatalog.fromOpenApi(
            BriareusJson.parseToJsonElement("""{"paths":{"/pulls/{number}":{"get":{"x-briareus-access":"read"}},"/pulls/merged":{"get":{"x-briareus-access":"admin"}}}}""") as JsonObject,
        )!!
        assertFalse(catalog.allows("GET", "pulls/merged", Permission.READ))
        assertTrue(catalog.allows("GET", "pulls/12", Permission.READ))
    }

    @Test fun roundTripsASavedList() {
        val catalog = RouteCatalog.fromOpenApi(openApi)!!
        val saved = RouteCatalog.fromSaved(catalog.toJson())
        assertNotNull(saved)
        assertEquals(catalog.routes, saved!!.routes)
    }

    @Test fun everyCallHasARoute() {
        for (r in Routes.all) assertEquals(r, Routes[r.name])
        assertEquals(Routes.all.size, Routes.all.map { it.name }.toSet().size)
    }
}
