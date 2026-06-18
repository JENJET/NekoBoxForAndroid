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
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var refreshKey: String = ""

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
                setStatus(context.getText(R.string.vpn_connected))
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

    fun refreshServerInfo(testProxyId: Long = -1L) {
        if (!DataStore.serviceState.connected) return
        val activity = context as MainActivity
        val currentFragment = activity.supportFragmentManager
            .findFragmentById(R.id.fragment_holder)
        val onMainPage = currentFragment is io.nekohasekai.sagernet.ui.ConfigurationFragment

        val now = System.currentTimeMillis()
        val profileId = if (testProxyId > 0) testProxyId else DataStore.selectedProxy
        val groupId = if (testProxyId > 0) {
            ProfileManager.getProfile(profileId)?.groupId ?: DataStore.selectedGroup
        } else {
            DataStore.selectedGroup
        }

        // 取消上一次未完成的请求，并清理其残留占位符
        refreshJob?.cancel()
        if (refreshKey.isNotEmpty()) {
            synchronized(geoIPCache) {
                val stale = geoIPCache[groupId]?.get(refreshKey)
                if (stale != null && stale.ip.isEmpty()) {
                    geoIPCache[groupId]?.remove(refreshKey)
                }
            }
            refreshKey = ""
        }

        if (profileId <= 0) {
            updateServerInfoUI("", "", "")
            return
        }

        val key = profileId.toString()

        var hitCache: Triple<String, String, String>? = null
        var placeholderValid = false
        synchronized(geoIPCache) {
            val cached = geoIPCache[groupId]?.get(key)
            if (cached != null && cached.ip.isNotEmpty() && now - cached.timestamp < 120_000L) {
                hitCache = Triple(cached.ip, cached.city, cached.country)
            } else if (cached != null && cached.ip.isEmpty() && now - cached.timestamp < 120_000L) {
                placeholderValid = true
            } else if (onMainPage) {
                // 写入占位符
                geoIPCache.getOrPut(groupId) { mutableMapOf() }[key] =
                    if (cached != null) cached.copy(timestamp = now) else GeoIPCache("", "", "", now)
            }
        }

        // 缓存命中或占位符有效，直接显示/跳过
        if (hitCache != null) {
            val (ip, city, country) = hitCache!!
            updateServerInfoUI(ip, city, country)
            return
        }
        if (placeholderValid || !onMainPage) return

        // 无有效缓存，若有占位符保留的旧数据则暂不清理，异步更新
        val placeholder = synchronized(geoIPCache) { geoIPCache[groupId]?.get(key) }
        if (placeholder == null || placeholder.ip.isEmpty()) {
            updateServerInfoUI("", "", "")
        }

        val requestId = profileId
        refreshKey = key

        refreshJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            val info = withContext(Dispatchers.IO) {
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

                    mapOf("ip" to ip, "city" to city, "country" to country)
                } catch (e: Exception) {
                    null
                }
            }

            ensureActive()

            var displayIp = ""
            var displayCity = ""
            var displayCountry = ""

            synchronized(geoIPCache) {
                if (info != null) {
                    geoIPCache.getOrPut(groupId) { mutableMapOf() }[key] = GeoIPCache(
                        ip = info["ip"] ?: "",
                        city = info["city"] ?: "",
                        country = info["country"] ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    val entry = geoIPCache[groupId]?.get(key)
                    if (entry != null && entry.ip.isEmpty()) {
                        geoIPCache[groupId]?.remove(key)
                    }
                }

                val displayKey = DataStore.selectedProxy.toString()
                val displayCache = geoIPCache[groupId]?.get(displayKey)

                if (displayCache != null && displayCache.ip.isNotEmpty()) {
                    displayIp = displayCache.ip
                    displayCity = displayCache.city
                    displayCountry = displayCache.country
                } else {
                    if (displayCache != null && displayCache.ip.isEmpty()) {
                        geoIPCache[groupId]?.remove(displayKey)
                    }
                }
            }

            updateServerInfoUI(displayIp, displayCity, displayCountry)
            refreshKey = ""
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
        setStatus(context.getText(R.string.connection_test_testing))
        val testProxyId = DataStore.selectedProxy
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        context.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                    refreshServerInfo(testProxyId)
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
        setStatus(context.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        context.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
