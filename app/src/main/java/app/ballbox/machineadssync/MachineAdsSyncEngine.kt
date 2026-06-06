package app.ballbox.machineadssync

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
            val expectedSha = file.getString("sha256")
            val outputFile = File(targetRoot, target)

            outputFile.parentFile?.mkdirs()

            if (outputFile.exists()) {
                val existingSha = sha256(outputFile)
                if (existingSha == expectedSha) {
                    logger("SKIP $target already current")
                    continue
                }
            }

            logger("DOWNLOAD $url")
            val bytes = fetchBytes(url)
            val downloadedSha = sha256(bytes)
            if (downloadedSha != expectedSha) {
                throw IllegalStateException("sha mismatch for $target")
            }

            val tempFile = File(outputFile.absolutePath + ".tmp")
            FileOutputStream(tempFile).use { it.write(bytes) }
            if (outputFile.exists()) {
                outputFile.delete()
            }
            tempFile.renameTo(outputFile)
            appliedCount += 1
            logger("WROTE $target (${bytes.size} bytes)")
        }

        return SyncResult(restartPolicy = restartPolicy, appliedCount = appliedCount)
    }

    private fun fetchJson(url: String): JSONObject {
        val text = fetchText(url)
        return JSONObject(text)
    }

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun fetchBytes(url: String): ByteArray {
        val connection = openConnection(url)
        return connection.inputStream.use { it.readBytes() }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "BallboxMachineAdsSync/0.1")
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IllegalStateException("request failed: $status for $url")
        }
        return connection
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

data class SyncResult(
    val restartPolicy: String,
    val appliedCount: Int,
)
