package com.tylerabitbol.libra.networking

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/**
 * The Ktor stand-in for Swift's `StubURLProtocol`.
 *
 * Same job: put a scripted response in front of the client so the status-code
 * mapping, the retry path and a provider's decoding can be tested without a
 * network. Kept in `commonTest` so Phase 6's provider tests reuse it rather
 * than each rolling their own.
 */
object MockHttp {

    /** Records what the client actually sent, which several tests assert on. */
    class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        val count: Int get() = requests.size
        val last: HttpRequestData? get() = requests.lastOrNull()
    }

    /** One scripted response. */
    data class Reply(
        val status: HttpStatusCode = HttpStatusCode.OK,
        val body: String = "",
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * An engine that answers every request with [reply].
     *
     * [recorder] captures the requests so a test can assert how many attempts
     * were made — the retry behaviour is otherwise invisible.
     */
    fun engine(reply: Reply, recorder: Recorder? = null): MockEngine = MockEngine { request ->
        recorder?.requests?.add(request)
        respond(
            content = ByteReadChannel(reply.body),
            status = reply.status,
            headers = headersOf(
                *reply.headers.map { (key, value) -> key to listOf(value) }.toTypedArray()
            )
        )
    }

    /** An engine that walks [replies] in order, repeating the last one. */
    fun engine(replies: List<Reply>, recorder: Recorder? = null): MockEngine {
        require(replies.isNotEmpty()) { "Need at least one reply" }
        var index = 0
        return MockEngine { request ->
            recorder?.requests?.add(request)
            val reply = replies[minOf(index, replies.size - 1)]
            index += 1
            respond(
                content = ByteReadChannel(reply.body),
                status = reply.status,
                headers = headersOf(
                    *reply.headers.map { (key, value) -> key to listOf(value) }.toTypedArray()
                )
            )
        }
    }

    /** An engine whose transport fails outright, as an offline device would. */
    fun failing(message: String = "The network is unreachable"): MockEngine = MockEngine {
        throw kotlinx.io.IOException(message)
    }
}
