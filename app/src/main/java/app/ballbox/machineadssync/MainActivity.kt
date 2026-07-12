package app.ballbox.machineadssync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.syncButton.setOnClickListener {
            val manifestUrl = binding.manifestUrlInput.text?.toString()?.trim().orEmpty()
            val targetRoot = binding.targetRootInput.text?.toString()?.trim().orEmpty()

            if (manifestUrl.isEmpty() || targetRoot.isEmpty()) {
                binding.statusText.text = "Manifest URL and target root are required"
                return@setOnClickListener
            }

            if (!hasStoragePermission()) {
                binding.statusText.text = "Storage permission required"
                appendLog("Requesting storage permission...")
                ActivityCompat.requestPermissions(this, STORAGE_PERMISSIONS, REQUEST_STORAGE_PERMISSIONS)
                return@setOnClickListener
            }

            startSync(manifestUrl, targetRoot)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE_PERMISSIONS) return

        if (hasStoragePermission()) {
            binding.statusText.text = "Permission granted"
            appendLog("Storage permission granted. Tap Sync now again.")
        } else {
            binding.statusText.text = "Storage permission denied"
            appendLog("Storage permission denied.")
        }
    }

    private fun startSync(manifestUrl: String, targetRoot: String) {
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
                    appendLog("Done. Applied ${it.appliedCount} changed files. Pruned ${it.prunedCount} stale files.")
                }.onFailure {
                    binding.statusText.text = "Sync failed"
                    appendLog("ERROR ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }.start()
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return STORAGE_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun appendLog(line: String) {
        val previous = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (previous.isBlank()) line else "$previous\n$line"
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSIONS = 1001
        private val STORAGE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        private const val DEFAULT_MANIFEST_URL = "https://ballbox.app/api/machines/2601070188/ads-manifest"
        private const val DEFAULT_TARGET_ROOT = "/sdcard/TcnFolder"
    }
}
