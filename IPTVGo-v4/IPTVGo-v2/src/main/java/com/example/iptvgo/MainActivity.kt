package com.example.iptvgo

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@UnstableApi
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipScroll: View
    private lateinit var listWrap: View
    private lateinit var chips: ChipGroup
    private lateinit var status: TextView
    private val adapter = ChannelAdapter { play(it) }
    private var all = listOf<Channel>()
    private var current: Channel? = null
    private var userFullscreen = false

    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        toolbar = findViewById(R.id.toolbar)
        chipScroll = findViewById(R.id.chipScroll)
        listWrap = findViewById(R.id.listWrap)
        chips = findViewById(R.id.chips)
        status = findViewById(R.id.status)

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_reload -> loadPlaylist()
                R.id.action_playlist -> askUrl()
            }
            true
        }

        playerView.setFullscreenButtonClickListener { toggleFullscreen() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape()) {
                    toggleFullscreen()
                } else if (playerView.visibility == View.VISIBLE) {
                    player?.stop(); current = null
                    playerView.visibility = View.GONE
                } else finish()
            }
        })

        loadPlaylist()
    }

    // Create the player only while visible -> low RAM use on Android Go
    override fun onStart() {
        super.onStart()
        current?.let { play(it) }
    }

    override fun onStop() {
        super.onStop()
        player?.release(); player = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    // ---------- Playlist ----------

    private fun loadPlaylist() {
        val url = prefs.getString("url", "").orEmpty()
        if (url.isBlank()) { askUrl(); return }
        status.visibility = View.VISIBLE
        status.text = "Loading…"
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val list = M3uParser.parse(text)
                runOnUiThread { setChannels(list) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed to load playlist\n${e.message}" }
            }
        }.start()
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "http://192.168.1.10:5001/playlist.m3u"
            setText(prefs.getString("url", ""))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Playlist URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("url", input.text.toString().trim()).apply()
                loadPlaylist()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setChannels(list: List<Channel>) {
        all = list
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) status.text = "No channels found"

        chips.removeAllViews()
        val groups = listOf("All") + list.map { it.group }.distinct().sorted()
        groups.forEachIndexed { i, g ->
            val chip = Chip(this).apply {
                text = g; isCheckable = true; isChecked = i == 0
                setOnClickListener { filter(g) }
            }
            chips.addView(chip)
        }
        adapter.submit(list)
    }

    private fun filter(group: String) {
        adapter.submit(if (group == "All") all else all.filter { it.group == group })
    }

    // ---------- Playback ----------

    private fun play(c: Channel) {
        current = c
        playerView.visibility = View.VISIBLE
        applyLayout()

        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(c.headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android 10)")
            .setDefaultRequestProperties(c.headers)

        val item = MediaItem.Builder().setUri(c.url)
        val u = c.url.lowercase()
        when {
            u.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
            u.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(ds)

        // ---- DRM ----
        val lt = c.licenseType?.lowercase()
        val lk = c.licenseKey
        if (lt != null && lk != null) {
            if (lt.contains("clearkey")) {
                val json = clearKeyJson(lk)
                if (json != null) {
                    val drm = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(LocalMediaDrmCallback(json.toByteArray()))
                    factory.setDrmSessionManagerProvider { drm }
                    item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
                } else {
                    item.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).setLicenseUri(lk).build()
                    )
                }
            } else if (lt.contains("widevine")) {
                // Widevine: device uses L1 if available, otherwise software L3 automatically
                item.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(lk)
                        .setLicenseRequestHeaders(c.headers)
                        .setMultiSession(true)
                        .build()
                )
            }
        }

        val p = player ?: ExoPlayer.Builder(this)
            .setLoadControl(
                // small buffers = less RAM on 1-2 GB devices
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5000, 20000, 1500, 3000)
                    .build()
            )
            .build().also {
                player = it
                playerView.player = it
                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@MainActivity,
                            "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        p.setMediaSource(factory.createMediaSource(item.build()))
        p.prepare()
        p.playWhenReady = true
    }

    /** Accepts "kid:key" (hex, comma separated for multiple) or a raw JSON license. */
    private fun clearKeyJson(lk: String): String? {
        if (lk.trim().startsWith("{")) return lk
        if (!lk.contains(":") || lk.startsWith("http")) return null
        fun b64(hex: String): String {
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        return runCatching {
            val keys = JSONArray()
            lk.split(",").forEach { pair ->
                val (kid, key) = pair.trim().split(":")
                keys.put(JSONObject().put("kty", "oct").put("k", b64(key)).put("kid", b64(kid)))
            }
            JSONObject().put("keys", keys).put("type", "temporary").toString()
        }.getOrNull()
    }

    // ---------- Fullscreen / layout ----------

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleFullscreen() {
        userFullscreen = !isLandscape()
        requestedOrientation = if (userFullscreen)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // hand control back to the sensor after the switch
        window.decorView.postDelayed({
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }, 1500)
    }

    private fun applyLayout() {
        val land = isLandscape() && playerView.visibility == View.VISIBLE
        val lp = playerView.layoutParams
        lp.height = if (land) ViewGroup.LayoutParams.MATCH_PARENT
        else resources.displayMetrics.widthPixels * 9 / 16
        playerView.layoutParams = lp

        val vis = if (land) View.GONE else View.VISIBLE
        toolbar.visibility = vis
        chipScroll.visibility = vis
        listWrap.visibility = vis

        WindowCompat.setDecorFitsSystemWindows(window, !land)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (land) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- List ----------

    private class ChannelAdapter(val onClick: (Channel) -> Unit) :
        RecyclerView.Adapter<ChannelAdapter.VH>() {
        private var items = listOf<Channel>()

        fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val title: TextView = v.findViewById(R.id.title)
            val sub: TextView = v.findViewById(R.id.sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(h: VH, i: Int) {
            val c = items[i]
            h.title.text = c.name
            h.sub.text = c.group
            h.thumb.load(c.logo) { crossfade(false) }
            h.itemView.setOnClickListener { onClick(c) }
        }

        override fun getItemCount() = items.size
    }
}
