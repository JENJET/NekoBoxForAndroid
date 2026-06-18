package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet

enum class PluginEntry(
    val pluginId: String,
    val packageName: String, // for play and f-droid page
    val downloadSource: DownloadSource = DownloadSource()
) {
    TrojanGo(
        "trojan-go-plugin",
        "io.nekohasekai.sagernet.plugin.trojan_go"
    ),
    MieruProxy(
        "mieru-plugin",
        "moe.matsuri.exe.mieru",
        DownloadSource(
            playStore = false,
            fdroid = false,
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=mieru"
        )
    ),
    NaiveProxy(
        "naive-plugin",
        "moe.matsuri.exe.naive",
        DownloadSource(
            playStore = false,
            fdroid = false,
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=naive"
        )
    ),
    Hysteria(
        "hysteria-plugin",
        "moe.matsuri.exe.hysteria",
        DownloadSource(
            playStore = false,
            fdroid = false,
            downloadLink = "https://github.com/MatsuriDayo/plugins/releases?q=Hysteria"
        )
    ),
    ;

    fun displayName(): String {
        return SagerNet.application.getString(when (this) {
            TrojanGo -> R.string.action_trojan_go
            MieruProxy -> R.string.action_mieru
            NaiveProxy -> R.string.action_naive
            Hysteria -> R.string.action_hysteria
        })
    }

    data class DownloadSource(
        val playStore: Boolean = true,
        val fdroid: Boolean = true,
        val downloadLink: String = "https://matsuridayo.github.io/"
    )

    companion object {

        fun find(name: String): PluginEntry? {
            for (pluginEntry in enumValues<PluginEntry>()) {
                if (name == pluginEntry.pluginId) {
                    return pluginEntry
                }
            }
            return null
        }

    }

}