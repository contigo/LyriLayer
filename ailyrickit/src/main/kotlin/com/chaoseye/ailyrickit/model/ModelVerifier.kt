package com.chaoseye.ailyrickit.model

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

object ModelVerifier {
    const val SMALL_Q5_1_BYTES = 190085487L
    const val FILE_NAME = "ggml-small-q5_1.bin"
    const val MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/0b364b566045a405be7225ee1e415a073e04da77/ggml-small-q5_1.bin"
    /** expectedSha256 MUST come from a trusted published model revision, not the file being verified. */
    fun verify(file: File, expectedSha256: String = MODEL_SHA256, cancelled: () -> Boolean = { false }): File {
        require(file.isFile && file.canRead() && file.length() == SMALL_Q5_1_BYTES) { "Invalid Small Q5_1 file size" }
        verifyDigest(file, expectedSha256, cancelled)
        return file
    }
    internal fun verifyDigest(file: File, expected: String, cancelled: () -> Boolean = { false }) {
        require(expected.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected trusted SHA-256 required" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(256 * 1024)
            while (true) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Model verification cancelled")
                val n = input.read(bytes); if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual.equals(expected, ignoreCase = true)) { "Model SHA-256 mismatch" }
    }
}
