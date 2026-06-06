package app.ballbox.machineadssync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.ballbox.machineadssync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.manifestUrlInput.setText(DEFAULT_MANIFEST_URL)
        binding.targetRootInput.setText(DEFAULT_TARGET_ROOT)
        binding.logText.text = "Ready"

        binding.syncButton.setOnClickListener {
            val manifestUrl = binding.manifestUrlInput.text?.toString()?.trim().orEmpty()
            val targetRoot = binding.targetRootInput.text?.toString()?.trim().orEmpty()

            if (manifestUrl.isEmpty() || targetRoot.isEmpty()) {
                binding.statusText.text = "Manifest URL and target root are required"
                return@setOnClickListener
            }

            binding.syncButton.isEnabled = false
            binding.statusText.text = "Syncing..."
            binding.logText.text = "Starting sync..."

            Thread {
                val result = runCatching {
                    MachineAdsSyncEngine().sync(
                        manifestUrl = manifestUrl,
                        targetRoot = targetRoot,
                        logger = { line -> runOnUiThread { appendLog(line) } }
                    )
                }

                runOnUiThread {
                    binding.syncButton.isEnabled = true
                    result.onSuccess {
                        binding.statusText.text = "Sync OK · restart policy ${it.restartPolicy}"
                        appendLog("Done. Applied ${it.appliedCount} changed files.")
                    }.onFailure {
                        binding.statusText.text = "Sync failed"
                        appendLog("ERROR: ${it.message}")
                    }
                }
            }.start()
        }
    }

    private fun appendLog(line: String) {
        val previous = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (previous.isBlank()) line else "$previous\n$line"
    }

    companion object {
        private const val DEFAULT_MANIFEST_URL = "https://ballbox.app/api/machines/2601070188/ads-manifest"
        private const val DEFAULT_TARGET_ROOT = "/sdcard/TcnFoldercopy"
    }
}
