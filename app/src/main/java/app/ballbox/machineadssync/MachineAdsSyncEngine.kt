package app.ballbox.machineadssync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class MachineAdsSyncEngine {
    fun sync(manifestUrl: String, targetRoot: String, logger: (String) -> Unit): SyncResult {
        logger("GET $manifestUrl")
        val manifestPayload = fetchJson(manifestUrl)
        if (!manifestPayload.optBoolean("ok")) {
            throw IllegalStateException(manifestPayload.optString("message", "manifest request failed"))
        }

        val manifest = manifestPayload.getJSONObject("manifest")
        val restartPolicy = manifest.optString("restartPolicy", "app")
        val files = manifest.getJSONArray("files")
        var appliedCount = 0

        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val target = file.getString("target")
            val url = file.getString("url")
            val expectedSha = file.getString("sha256").lowercase()
            val outputFile = resolveOutputFile(targetRoot, target)

            val outputDirectory = requireNotNull(outputFile.parentFile)
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                throw IOException("could not create directory for $target")
            }

            if (outputFile.exists()) {
                val existingSha = sha256(outputFile)
                if (existingSha == expectedSha) {
                    logger("SKIP $target already current")
                    continue
                }
            }

            logger("DOWNLOAD $url")
            val tempFile = File.createTempFile(".${outputFile.name}.", ".tmp", outputDirectory)
            try {
                val download = downloadToFile(url, tempFile)
                if (download.sha256 != expectedSha) {
                    throw IllegalStateException(
                        "sha mismatch for $target: expected $expectedSha, got ${download.sha256}"
                    )
                }

                replaceFile(tempFile, outputFile)
                appliedCount += 1
                logger("WROTE $target (${download.byteCount} bytes)")
            } finally {
                tempFile.delete()
            }
        }

        return SyncResult(restartPolicy = restartPolicy, appliedCount = appliedCount)
    }

    private fun fetchJson(url: String): JSONObject {
        val text = fetchText(url)
        return JSONObject(text)
    }

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, destination: File): DownloadResult {
        val connection = openConnection(url)
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L

        try {
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        byteCount += count
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        return DownloadResult(digest.digest().toHex(), byteCount)
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "BallboxMachineAdsSync/0.1")
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("request failed: $status for $url")
        }
        return connection
    }

    private fun replaceFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    companion object {
        internal fun resolveOutputFile(targetRoot: String, target: String): File {
            require(target.isNotBlank()) { "manifest target must not be blank" }
            require(!File(target).isAbsolute) { "manifest target must be relative: $target" }

            val root = File(targetRoot).canonicalFile
            val output = File(root, target).canonicalFile
            require(output.path.startsWith(root.path + File.separator)) {
                "manifest target escapes target root: $target"
            }
            return output
        }

        private fun ByteArray.toHex(): String =
            joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private data class DownloadResult(
    val sha256: String,
    val byteCount: Long,
)

data class SyncResult(
    val restartPolicy: String,
    val appliedCount: Int,
)
