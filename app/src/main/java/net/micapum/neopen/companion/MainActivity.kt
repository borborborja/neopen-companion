package net.micapum.neopen.companion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import net.micapum.neopen.companion.ui.MainScreen
import net.micapum.neopen.companion.ui.MainViewModel
import net.micapum.neopen.companion.ui.theme.NeopenTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent { NeopenTheme { MainScreen(vm) } }
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
