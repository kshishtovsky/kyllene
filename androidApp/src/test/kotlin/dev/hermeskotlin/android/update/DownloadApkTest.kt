package dev.hermeskotlin.android.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A release asset host that drops the connection mid-body: the download must pick up where it stopped rather than
 * start over or give up, and it must never hand back a short file.
 */
class DownloadApkTest {

    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    private fun server(cutAfter: Int, alwaysCut: Boolean = false): Pair<HttpServer, MutableList<String>> {
        val ranges = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range").orEmpty()
            ranges += range
            // Every other request dies after cutAfter bytes, the way a reset transfer does.
            val dies = alwaysCut || ranges.size % 2 == 1
            val from = range.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
            val body = payload.copyOfRange(from, payload.size)
            exchange.responseHeaders.add("Content-Length", body.size.toString())
            if (from > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
            }
            val sent = if (dies && body.size > cutAfter) cutAfter else body.size
            exchange.responseBody.use { it.write(body, 0, sent) }
            // Closing the body early truncates the response: the client sees a reset, not a short read.
            exchange.close()
        }
        server.start()
        return server to ranges
    }

    @Test
    fun aDroppedTransferIsResumed() {
        val (server, ranges) = server(cutAfter = 100_000)
        try {
            val target = File.createTempFile("herald", ".apk").apply { delete() }
            downloadApk("http://127.0.0.1:${server.address.port}/apk", target, attempts = 6)
            assertContentEquals(payload, target.readBytes(), "the whole file, byte for byte")
            assertTrue(ranges.any { it.startsWith("bytes=") }, "a retry asked for the rest: $ranges")
            target.delete()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aLinkThatNeverFinishesLeavesNoFile() {
        val (server, _) = server(cutAfter = 1_000, alwaysCut = true)
        try {
            val target = File.createTempFile("herald", ".apk").apply { delete() }
            val failed = runCatching { downloadApk("http://127.0.0.1:${server.address.port}/apk", target, attempts = 2) }
            assertTrue(failed.isFailure, "two truncated attempts are a failure")
            assertTrue(!target.exists(), "no half APK is left for the next update")
        } finally {
            server.stop(0)
        }
    }
}
