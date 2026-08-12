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
            val url = file.getString("url")
            val expectedSha = file.getString("sha256").lowercase()
            val outputFile = resolveOutputFile(targetRoot, target)
            managedTargets += relativePath(targetRoot, outputFile)
            logger("TARGET $target")
            logger("DEST ${outputFile.absolutePath}")

            val outputDirectory = requireNotNull(outputFile.parentFile)
            if (!outputDirectory.exists()) {
                logger("MKDIR ${outputDirectory.absolutePath}")
                if (!outputDirectory.mkdirs() && !outputDirectory.exists()) {
                    throw IOException("could not create directory for $target")
                }
            }

            if (outputFile.exists() && sha256(outputFile) == expectedSha) {
                logger("SKIP $target already current")
                continue
            }

            logger("DOWNLOAD $url")
            val tempFile = File.createTempFile(".${outputFile.name}.", ".tmp", outputDirectory)
            logger("WRITE TMP ${tempFile.absolutePath}")
            try {
                val download = downloadToFile(url, tempFile, logger)
                logger("SHA expected=$expectedSha")
                logger("SHA actual=${download.sha256}")
                if (download.sha256 != expectedSha) {
                    throw IllegalStateException(
                        "sha mismatch for $target: expected $expectedSha, got ${download.sha256}"
                    )
                }

                logger("ATOMIC REPLACE TMP -> DEST")
                replaceFile(tempFile, outputFile)
                appliedCount += 1
                logger("WROTE $target (${download.byteCount} bytes)")
            } finally {
                if (tempFile.exists()) {
                    logger("CLEANUP TMP ${tempFile.absolutePath}")
                    tempFile.delete()
                }
            }
        }

        val prunedCount = pruneManagedDirectories(targetRoot, managedTargets, logger)
        return SyncResult(restartPolicy, appliedCount, prunedCount)
    }

    private fun fetchJson(url: String): JSONObject = JSONObject(fetchText(url))

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(url: String, destination: File, logger: (String) -> Unit): DownloadResult {
        val connection = openConnection(url)
        val digest = MessageDigest.getInstance("SHA-256")
        val contentLength = connection.contentLengthLong
        if (contentLength > 0) logger("CONTENT LENGTH $contentLength bytes")
        var byteCount = 0L
        var nextLogAt = 1024L * 1024L

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
                        if (byteCount >= nextLogAt) {
                            if (contentLength > 0) {
                                val percent = (byteCount * 100 / contentLength).coerceAtMost(100)
                                logger("DOWNLOAD PROGRESS $byteCount/$contentLength bytes ($percent%)")
                            } else {
                                logger("DOWNLOAD PROGRESS $byteCount bytes")
                            }
                            nextLogAt += 1024L * 1024L
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        logger("DOWNLOAD COMPLETE $byteCount bytes")
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
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pruneManagedDirectories(
        targetRoot: String,
        managedTargets: Set<String>,
        logger: (String) -> Unit,
    ): Int {
        val managedDirs = managedTargets
            .mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotBlank) }
            .toSortedSet()
        var prunedCount = 0

        for (dir in managedDirs) {
            val dirFile = resolveOutputFile(targetRoot, "$dir/.managed-directory").parentFile!!
            if (!dirFile.isDirectory) {
                logger("PRUNE SKIP missing dir ${dirFile.absolutePath}")
                continue
            }
            logger("PRUNE DIR ${dirFile.absolutePath}")
            dirFile.listFiles()?.forEach { child ->
                if (!child.isFile) return@forEach
                val relative = relativePath(targetRoot, child.canonicalFile)
                if (relative in managedTargets) return@forEach
                logger("PRUNE DELETE ${child.absolutePath}")
                if (!child.delete()) throw IOException("prune delete failed for ${child.absolutePath}")
                prunedCount += 1
            }
        }
        logger("PRUNE DONE $prunedCount files")
        return prunedCount
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

        private fun relativePath(targetRoot: String, file: File): String =
            file.relativeTo(File(targetRoot).canonicalFile).path.replace(File.separatorChar, '/')

        private fun ByteArray.toHex(): String =
            joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

private data class DownloadResult(val sha256: String, val byteCount: Long)

data class SyncResult(
    val restartPolicy: String,
    val appliedCount: Int,
    val prunedCount: Int,
)
