package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var serverInfoIP: TextView
    private lateinit var serverInfoCity: TextView
    private lateinit var serverInfoCountry: TextView
    private lateinit var behavior: StatsBarBehavior

    var allowShow = true

    companion object {
        private val geoIPCache = java.util.Collections.synchronizedMap(mutableMapOf<Long, MutableMap<String, GeoIPCache>>())

        fun clearGeoIPCacheForGroup(groupId: Long) {
            synchronized(geoIPCache) {
                geoIPCache.remove(groupId)
            }
        }
    }

    private data class GeoIPCache(
        val ip: String,
        val city: String,
        val country: String,
        val timestamp: Long
    )

    override fun getBehavior(): StatsBarBehavior {
        if (!this::behavior.isInitialized) behavior = StatsBarBehavior { allowShow }
        return behavior
    }

    class StatsBarBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        serverInfoIP = findViewById(R.id.serverInfoIP)
        serverInfoCity = findViewById(R.id.serverInfoCity)
        serverInfoCountry = findViewById(R.id.serverInfoCountry)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
                refreshServerInfo()
            }
        } else {
            postWhenStarted {
                performHide()
                serverInfoIP.visibility = View.GONE
                serverInfoCity.visibility = View.GONE
                serverInfoCountry.visibility = View.GONE
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun refreshServerInfo() {
        val activity = context as MainActivity
        val currentFragment = activity.supportFragmentManager
            .findFragmentById(R.id.fragment_holder)
        val onMainPage = currentFragment is io.nekohasekai.sagernet.ui.ConfigurationFragment

        val now = System.currentTimeMillis()
        val groupId = DataStore.selectedGroup
        val profileId = DataStore.currentProfile

        val proxy = io.nekohasekai.sagernet.database.ProfileManager.getProfile(profileId)
        val bean = proxy?.requireBean()
        val cacheKey = "${bean?.serverAddress}:${bean?.serverPort}"

        val cached = synchronized(geoIPCache) { geoIPCache[groupId]?.get(cacheKey) }
        if (cached != null && cached.ip.isNotEmpty()) {
            updateServerInfoUI(cached.ip, cached.city, cached.country)
            if (now - cached.timestamp < 120_000L) return
        }

        if (!onMainPage) return

        synchronized(geoIPCache) {
            val existing = geoIPCache[groupId]?.get(cacheKey)
            if (existing != null) {
                if (existing.ip.isEmpty() && now - existing.timestamp < 120_000L) return
                geoIPCache.getOrPut(groupId) { mutableMapOf() }[cacheKey] = existing.copy(timestamp = now)
            } else {
                geoIPCache.getOrPut(groupId) { mutableMapOf() }[cacheKey] = GeoIPCache("", "", "", now)
            }
        }

        activity.lifecycleScope.launch(Dispatchers.Main) {
            val info = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val apiUrl = DataStore.geoipAPI
                    val json = queryGeoIp(apiUrl) ?: return@withContext null

                    val ip = shortenIPv6(json.optString("ip", ""))
                    val country: String
                    val city: String

                    if (apiUrl.contains("ipapi")) {
                        val location = json.optJSONObject("location")
                        country = location?.optString("country_code", "") ?: ""
                        city = location?.optString("city", "") ?: ""
                    } else if (apiUrl.contains("country.is")) {
                        country = json.optString("country", "")
                        city = ""
                    } else {
                        country = json.optString("country_code", "")
                        city = json.optString("city", "")
                    }

                    mapOf(
                        "ip" to ip,
                        "city" to city,
                        "country" to country
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (info != null) {
                synchronized(geoIPCache) {
                    geoIPCache.getOrPut(groupId) { mutableMapOf() }[cacheKey] = GeoIPCache(
                        ip = info["ip"] ?: "",
                        city = info["city"] ?: "",
                        country = info["country"] ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                }
                updateServerInfoUI(info["ip"], info["city"], info["country"])
            } else {
                synchronized(geoIPCache) {
                    val entry = geoIPCache[groupId]?.get(cacheKey)
                    if (entry != null && entry.ip.isEmpty()) {
                        geoIPCache[groupId]?.remove(cacheKey)
                    }
                }
            }
        }
    }

    private fun shortenIPv6(ip: String): String {
        if (!ip.contains(":")) return ip
        return try {
            java.net.InetAddress.getByName(ip).hostAddress
        } catch (_: Exception) { ip }
    }

    private fun countryCodeToFlag(countryCode: String): String {
        return countryCode.uppercase().map { char ->
            Character.codePointAt(char.toString(), 0) + 0x1F1E6 - Character.codePointAt("A", 0)
        }.joinToString("") { Character.toChars(it).concatToString() }
    }

    private fun updateServerInfoUI(ip: String?, city: String?, country: String?) {
        serverInfoIP.text = ip
        serverInfoIP.visibility = if (ip?.isNotEmpty() == true) View.VISIBLE else View.GONE

        serverInfoCity.text = city
        serverInfoCity.visibility = if (city?.isNotEmpty() == true) View.VISIBLE else View.GONE

        serverInfoCountry.text = if (country?.isNotEmpty() == true) {
            "$country ${countryCodeToFlag(country)}"
        } else ""
        serverInfoCountry.visibility = if (country?.isNotEmpty() == true) View.VISIBLE else View.GONE
    }

    private fun queryGeoIp(apiUrl: String): JSONObject? {
        return try {
            val client = libcore.Libcore.newHttpClient().apply {
                trySocks5(io.nekohasekai.sagernet.database.DataStore.mixedPort)
                setTimeout(10)
            }
            val response = client.newRequest().apply {
                setURL(apiUrl)
                setUserAgent("Mozilla/5.0")
            }.execute()
            val content = moe.matsuri.nb4a.utils.Util.getStringBox(response.contentString) ?: return null
            JSONObject(content)
        } catch (e: Exception) {
            null
        }
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                    refreshServerInfo()
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
