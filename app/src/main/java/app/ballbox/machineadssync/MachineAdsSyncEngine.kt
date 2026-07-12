package app.ballbox.machineadssync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MachineAdsSyncEngine {
    fun sync(manifestUrl: String, targetRoot: String, logger: (String) -> Unit): SyncResult {
        logger("TARGET ROOT $targetRoot")
        logger("GET $manifestUrl")
        val manifestPayload = fetchJson(manifestUrl)
        if (!manifestPayload.optBoolean("ok")) {
            throw IllegalStateException(manifestPayload.optString("message", "manifest request failed"))
        }

        val manifest = manifestPayload.getJSONObject("manifest")
        val restartPolicy = manifest.optString("restartPolicy", "app")
        val files = manifest.getJSONArray("files")
        val managedTargets = mutableSetOf<String>()
        var appliedCount = 0

        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val target = file.getString("target")
            managedTargets += normalizeRelativePath(target)
            val url = file.getString("url")
            val expectedSha = file.getString("sha256")
            val outputFile = File(targetRoot, target)
            logger("TARGET $target")
            logger("DEST ${outputFile.absolutePath}")

            val parentDir = outputFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                logger("MKDIR ${parentDir.absolutePath}")
                if (!parentDir.mkdirs() && !parentDir.exists()) {
                    throw IllegalStateException("mkdir failed for ${parentDir.absolutePath}")
                }
            }

            if (outputFile.exists()) {
                val existingSha = sha256(outputFile)
                if (existingSha == expectedSha) {
                    logger("SKIP $target already current")
                    continue
                }
            }

            logger("DOWNLOAD $url")
            val bytes = fetchBytes(url, logger)
            val downloadedSha = sha256(bytes)
            logger("SHA expected=$expectedSha")
            logger("SHA actual=$downloadedSha")
            if (downloadedSha != expectedSha) {
                throw IllegalStateException("sha mismatch for $target")
            }

            val tempFile = File(outputFile.absolutePath + ".tmp")
            logger("WRITE TMP ${tempFile.absolutePath}")
            FileOutputStream(tempFile).use { it.write(bytes) }
            if (outputFile.exists()) {
                logger("DELETE OLD ${outputFile.absolutePath}")
                if (!outputFile.delete()) {
                    throw IllegalStateException("delete failed for ${outputFile.absolutePath}")
                }
            }
            logger("RENAME TMP -> DEST")
            if (!tempFile.renameTo(outputFile)) {
                throw IllegalStateException("rename failed from ${tempFile.absolutePath} to ${outputFile.absolutePath}")
            }
            appliedCount += 1
            logger("WROTE $target (${bytes.size} bytes)")
        }

        val prunedCount = pruneManagedDirectories(targetRoot, managedTargets, logger)
        return SyncResult(restartPolicy = restartPolicy, appliedCount = appliedCount, prunedCount = prunedCount)
    }

    private fun fetchJson(url: String): JSONObject {
        val text = fetchText(url)
        return JSONObject(text)
    }

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun fetchBytes(url: String, logger: (String) -> Unit): ByteArray {
        val connection = openConnection(url)
        val contentLength = connection.contentLengthLong
        if (contentLength > 0) {
            logger("CONTENT LENGTH $contentLength bytes")
        }
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalRead = 0L
            var nextLogAt = 1L * 1024 * 1024
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
                totalRead += count
                if (totalRead >= nextLogAt) {
                    if (contentLength > 0) {
                        val percent = (totalRead * 100 / contentLength).coerceAtMost(100)
                        logger("DOWNLOAD PROGRESS $totalRead/$contentLength bytes (${percent}%)")
                    } else {
                        logger("DOWNLOAD PROGRESS $totalRead bytes")
                    }
                    nextLogAt += 1L * 1024 * 1024
                }
            }
            logger("DOWNLOAD COMPLETE $totalRead bytes")
            output.toByteArray()
        }
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

    private fun pruneManagedDirectories(targetRoot: String, managedTargets: Set<String>, logger: (String) -> Unit): Int {
        val managedDirs = managedTargets
            .mapNotNull { target -> target.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotBlank() } }
            .toSortedSet()

        var prunedCount = 0

        for (dir in managedDirs) {
            val dirFile = File(targetRoot, dir)
            if (!dirFile.exists() || !dirFile.isDirectory) {
                logger("PRUNE SKIP missing dir ${dirFile.absolutePath}")
                continue
            }

            logger("PRUNE DIR ${dirFile.absolutePath}")
            dirFile.listFiles()?.forEach { child ->
                if (!child.isFile) return@forEach
                val relativePath = normalizeRelativePath(child.absolutePath.removePrefix(File(targetRoot).absolutePath).trimStart('/'))
                if (relativePath in managedTargets) return@forEach
                logger("PRUNE DELETE ${child.absolutePath}")
                if (!child.delete()) {
                    throw IllegalStateException("prune delete failed for ${child.absolutePath}")
                }
                prunedCount += 1
            }
        }

        logger("PRUNE DONE $prunedCount files")
        return prunedCount
    }

    private fun normalizeRelativePath(value: String): String = value.replace('\\', '/').trimStart('/')

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

data class SyncResult(
    val restartPolicy: String,
    val appliedCount: Int,
    val prunedCount: Int,
)
