package net.micapum.neopen.live.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import net.micapum.neopen.core.pen.PenState
import net.micapum.neopen.core.pen.PenStatus
import net.micapum.neopen.live.R
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(vm: LiveViewModel) {
    val pen by vm.penState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val modelBusy by vm.modelBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshModel() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("neopen Live", style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Estado del boli ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(statusText(pen), style = MaterialTheme.typography.titleMedium)
                    pen.settings.battery?.let { b ->
                        if (pen.connected) Text(
                            "Batería $b%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!pen.connected) {
                        Button(onClick = vm::connect, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pen_connect))
                        }
                    } else {
                        if (pen.settings.pressureSupported) {
                            Text(stringResource(R.string.pen_sensitivity_title), style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val current = pen.settings.sensitivity ?: 2
                                (0..4).forEach { level ->
                                    FilterChip(
                                        selected = current == level,
                                        onClick = { vm.setSensitivity(level) },
                                        label = { Text(stringResource(R.string.pen_sensitivity_level, level + 1)) },
                                    )
                                }
                            }
                        }
                        OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pen_disconnect))
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Idioma ---
            Text(stringResource(R.string.live_language), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("es" to "Español", "ca" to "Català", "en" to "English").forEach { (value, label) ->
                    FilterChip(settings.liveLanguage == value, { vm.setLanguage(value) }, label = { Text(label) })
                }
            }

            // --- Modelo ---
            Text(
                stringResource(
                    when {
                        modelBusy -> R.string.live_model_busy
                        modelReady == true -> R.string.live_model_ready
                        else -> R.string.live_model_missing
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::downloadModel, enabled = !modelBusy && modelReady != true) {
                    Text(stringResource(R.string.live_model_download))
                }
                OutlinedButton(onClick = vm::deleteModel, enabled = !modelBusy && modelReady == true) {
                    Text(stringResource(R.string.live_model_delete))
                }
            }

            HorizontalDivider()

            // --- Presión como grosor ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pressure_width), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.pressure_width_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(settings.pressureWidthEnabled, { vm.setPressureWidthEnabled(it) })
            }
            if (settings.pressureWidthEnabled) {
                val contrast = settings.pressureWidthContrast.coerceIn(0.5f, 3f)
                Text(
                    stringResource(R.string.pressure_width_contrast, ((contrast * 10f).roundToInt() / 10f).toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = contrast,
                    onValueChange = vm::setPressureWidthContrast,
                    valueRange = 0.5f..3f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            // --- Bolis conocidos ---
            Text(stringResource(R.string.known_pens), style = MaterialTheme.typography.titleSmall)
            if (settings.knownPens.isEmpty()) {
                Text(
                    stringResource(R.string.known_pens_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                settings.knownPens.forEach { kp ->
                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(kp.alias, style = MaterialTheme.typography.bodyLarge)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.lock_to_pen), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(R.string.lock_to_pen_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(settings.lockedPenMac == kp.mac, { on -> vm.setLockedPen(if (on) kp.mac else null) })
                            }
                            OutlinedButton(onClick = { vm.forgetPen(kp.mac) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.pen_forget))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Saltos de línea ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_newlines), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.live_newlines_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(settings.liveNewlines, { vm.setNewlines(it) })
            }

            HorizontalDivider()

            // --- Activar teclado ---
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.live_enable_keyboard)) }
            Text(
                stringResource(R.string.live_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusText(pen: PenState): String = stringResource(
    when (pen.status) {
        PenStatus.SCANNING -> R.string.pen_status_scanning
        PenStatus.CONNECTING -> R.string.pen_status_connecting
        PenStatus.AUTHORIZED -> R.string.pen_status_connected
        PenStatus.LOCKED -> R.string.pen_status_locked
        PenStatus.DISCONNECTED -> R.string.pen_status_disconnected
        PenStatus.IDLE -> R.string.pen_status_idle
    },
)
