---
name: api-call
description: Adding or changing a call to the Briareus server's /api/v1 end to end: the Routes table, the ApiClient test that pins it, the Store method, the route-catalog gating with store.can, and the screen. Use when a change needs new data from the server or a new action on it, or when the server's API changed.
---

# Adding or changing a server call

The server's contract is `docs/api-v1.md` in [nadinyamaui/briareus](https://github.com/nadinyamaui/briareus) and its
`GET /api/v1/openapi.json`. The web dashboard is the reference for behaviour and wording.

1. **Name it in `core/.../Routes.kt`.** Add an `ApiRoute("name", "METHOD", "path/{arg}")` in the right group. A `{arg}`
   in the path is filled from the argument of that name; other arguments go in the query of a GET or DELETE and in the
   JSON body otherwise. `set = "flag"` sends a body flag always true; `filter`/`list` cut a list down client-side when the
   route has no such parameter (see `sessions`). Names are what `store.can` and the UI use, so pick a short verb or noun.

2. **Pin it in `ApiClientTest`.** `server.reply(...)`, then `client.call("name", args("x" to 1))`, then assert
   `server.requests.last()`'s method, URL and `server.bodies.last()`. Uploads and transcription have their own methods
   on `ApiClient`; everything else goes through `call`.

3. **Model the answer in `Models.kt`** if it is more than a field or two, as a data class with a `parse(JsonObject)`
   companion and a `ModelsTest` case. Keep the JSON helpers (`str`, `obj`, `objects`, `strings`, `args`) rather than
   touching `JsonElement` casts in screens.

4. **Expose it on `Store`** (or `Conversation` for a per-session stream). A write goes through `store.mutate("name",
   args(...))`, which reports an `ApiError` to the user and returns null; a read uses `client!!.call` inside
   `runCatching` like `runtimes` and `branches`. State that other windows need becomes a `StateFlow` on `Store`.

5. **Gate the control with `store.can("name")`.** The server may lack the route (older server) or the token may be
   read-only; a hidden control is the contract, not a disabled one. Nothing in `app/` mentions a path.

6. **Say it in the README's feature list** if it is user-visible, in the same voice, and in `CONTRIBUTING.md` only if it
   changes how to build or test.

Server-sent events (`events`, `session_events`) are parsed by `Sse.kt` and resumed from the last `id` seen; a new event
kind is handled in `Models.kt` and `Store.upsert`/`Conversation`, with an `SseTest` or `ModelsTest` case.
