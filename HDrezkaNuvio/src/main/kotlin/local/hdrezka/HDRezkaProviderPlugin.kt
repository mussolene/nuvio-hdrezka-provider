package local.hdrezka

import android.content.Context
import android.app.Activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDRezkaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        HDRezkaSession.attach(context)
        openSettings = { settingsContext ->
            (settingsContext as? Activity)?.let(HDRezkaLoginDialog::show)
        }
        HDRezkaLoginDialog.showIfNeeded(context)
        registerMainAPI(HDRezkaProvider())
    }
}
