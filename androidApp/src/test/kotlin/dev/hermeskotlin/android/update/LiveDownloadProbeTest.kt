package dev.hermeskotlin.android.update

import java.io.File
import kotlin.test.Test

/** Scratch: the real release asset over this network, which resets it. Deleted after the run. */
class LiveDownloadProbeTest {

    @Test
    fun againstThePublishedAsset() {
        val target = File(System.getProperty("java.io.tmpdir"), "herald-probe.apk").apply { delete() }
        val t0 = System.currentTimeMillis()
        val result = runCatching {
            downloadApk(
                "https://github.com/kshishtovsky/kyllene/releases/download/v1.0.1/kyllene-1.0.1_arm64-v8a.apk",
                target,
                budget = 300_000,
            )
        }
        println("PROBE result=${result.isSuccess} bytes=${if (target.exists()) target.length() else 0} ms=${System.currentTimeMillis() - t0} err=${result.exceptionOrNull()?.message}")
        target.delete()
    }
}
