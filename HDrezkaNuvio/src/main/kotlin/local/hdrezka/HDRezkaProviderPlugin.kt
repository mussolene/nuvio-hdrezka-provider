package local.hdrezka

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDRezkaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        HDRezkaSession.attach(context)
        HDRezkaLoginDialog.showIfNeeded(context)
        registerMainAPI(HDRezkaProvider())
    }
}
