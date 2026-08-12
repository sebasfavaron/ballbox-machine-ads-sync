package app.ballbox.machineadssync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.ballbox.machineadssync.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            binding.statusText.text = if (granted) {
                "Storage access granted. Tap Sync now."
            } else {
                "Storage access is required for the selected target root"
            }
            appendLog(if (granted) "Storage permission granted." else "Storage permission denied.")
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val granted = hasStorageAccess()
            binding.statusText.text = if (granted) {
                "Storage access granted. Tap Sync now."
            } else {
                "Storage access is required for the selected target root"
            }
            appendLog(if (granted) "All-files access granted." else "All-files access not granted.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.manifestUrlInput.setText(DEFAULT_MANIFEST_URL)
        binding.targetRootInput.setText(DEFAULT_TARGET_ROOT)
        binding.logText.text = "Ready"

        binding.backButton.setOnClickListener { finish() }

        binding.syncButton.setOnClickListener {
            val manifestUrl = binding.manifestUrlInput.text?.toString()?.trim().orEmpty()
            val targetRoot = binding.targetRootInput.text?.toString()?.trim().orEmpty()

            if (manifestUrl.isEmpty() || targetRoot.isEmpty()) {
                binding.statusText.text = "Manifest URL and target root are required"
                return@setOnClickListener
            }

            if (!hasStorageAccess()) {
                appendLog("Requesting storage access...")
                requestStorageAccess()
                return@setOnClickListener
            }

            startSync(manifestUrl, targetRoot)
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

    private fun appendLog(line: String) {
        val previous = binding.logText.text?.toString().orEmpty()
        binding.logText.text = if (previous.isBlank()) line else "$previous\n$line"
    }

    private fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun requestStorageAccess() {
        binding.statusText.text = "Grant storage access, then return to this app"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val fallbackSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            allFilesAccessLauncher.launch(
                if (appSettings.resolveActivity(packageManager) != null) appSettings else fallbackSettings
            )
        } else {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    companion object {
        private const val DEFAULT_MANIFEST_URL = "https://ballbox.app/api/machines/2601070188/ads-manifest"
        private const val DEFAULT_TARGET_ROOT = "/sdcard/TcnFolder"
    }
}
