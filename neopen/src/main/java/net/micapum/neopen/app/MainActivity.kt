package net.micapum.neopen.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import net.micapum.neopen.app.ui.AppRoot
import net.micapum.neopen.app.widget.EXTRA_OPEN_NOTEBOOK
import net.micapum.neopen.app.widget.EXTRA_OPEN_TAB

class MainActivity : ComponentActivity() {
    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        // Deep-link desde los widgets: abrir en una pestaña / cuaderno concreto.
        val openTab = intent?.getStringExtra(EXTRA_OPEN_TAB)
        val openNotebook = intent?.getStringExtra(EXTRA_OPEN_NOTEBOOK)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(initialTab = openTab, initialNotebookId = openNotebook)
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permsLauncher.launch(perms)
    }
}
