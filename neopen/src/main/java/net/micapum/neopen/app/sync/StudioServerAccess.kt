package net.micapum.neopen.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.micapum.neopen.core.data.AppMode
import net.micapum.neopen.core.data.AppSettings
import net.micapum.neopen.core.data.normalizedServerUrl

/** Permiso efímero: nunca se restaura un true de disco ni se habilita desde ajustes locales. */
data class StudioVerification(val serverUrl: String = "", val verified: Boolean = false, val enabled: Boolean = false, val revision: Long = 0)
class StudioServerAccess {
    private val current = MutableStateFlow(StudioVerification())
    val state = current.asStateFlow()
    private var verifiedPassword: String? = null

    fun allowedFor(settings: AppSettings): Boolean = settings.appMode != AppMode.LOCAL && settings.configured &&
        current.value.verified && current.value.enabled &&
        current.value.serverUrl == normalizedServerUrl(settings.serverUrl) && verifiedPassword == settings.password

    internal fun confirmed(settings: AppSettings, enabled: Boolean) {
        verifiedPassword = settings.password
        current.value = StudioVerification(normalizedServerUrl(settings.serverUrl), true, enabled, current.value.revision + 1)
    }
    fun invalidate() {
        verifiedPassword = null
        current.value = StudioVerification(revision = current.value.revision + 1)
    }
}
