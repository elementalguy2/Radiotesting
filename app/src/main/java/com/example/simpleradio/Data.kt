package com.example.simpleradio

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Station(
    val name: String,
    val url: String,
    val iconUrl: String? = null,
    val info: String? = null,
)

/** Search the free community Radio Browser directory (radio-browser.info). */
object RadioApi {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    suspend fun search(query: String): List<Station> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        var last: Exception? = null
        for (server in servers) {
            try {
                val conn = URL(
                    "$server/json/stations/search?name=$q&limit=40&hidebroken=true&order=clickcount&reverse=true"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "SimpleRadio/1.0")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                return@withContext (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    val name = o.optString("name").trim()
                    if (url.isBlank() || name.isBlank()) {
                        null
                    } else {
                        val tags = o.optString("tags").split(",")
                            .map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
                        val info = listOf(o.optString("countrycode"), tags)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        Station(
                            name = name,
                            url = url,
                            iconUrl = o.optString("favicon").ifBlank { null },
                            info = info.ifBlank { null },
                        )
                    }
                }.distinctBy { it.url }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No directory server reachable")
    }
}

/** Favorites persisted as JSON in SharedPreferences. */
class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun load(): List<Station> = try {
        val arr = JSONArray(prefs.getString("favs", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Station(
                name = o.getString("name"),
                url = o.getString("url"),
                iconUrl = o.optString("icon").ifBlank { null },
                info = o.optString("info").ifBlank { null },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Station>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.url)
                    .put("icon", it.iconUrl ?: "")
                    .put("info", it.info ?: "")
            )
        }
        prefs.edit().putString("favs", arr.toString()).apply()
    }
}

/** Process-wide sleep timer; keeps counting while the playback service is alive. */
object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    var player: Player? = null

    private val _remaining = MutableStateFlow<Int?>(null)
    val remaining: StateFlow<Int?> = _remaining

    fun start(minutes: Int) {
        cancel()
        job = scope.launch {
            var left = minutes * 60
            while (left > 0) {
                _remaining.value = left
                delay(1000)
                left--
            }
            _remaining.value = null
            player?.pause()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remaining.value = null
    }
}
