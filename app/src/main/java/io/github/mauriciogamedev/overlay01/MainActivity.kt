package io.github.mauriciogamedev.overlay01

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.mauriciogamedev.overlay01.service.CaptureService

class MainActivity : Activity() {
    private lateinit var overlayUrl: EditText
    private lateinit var rtmpServer: EditText
    private lateinit var streamKey: EditText
    private lateinit var overlayPreview: WebView
    private lateinit var statusText: TextView
    private lateinit var loadButton: Button
    private lateinit var lockOverlay: CheckBox
    private lateinit var useMicrophone: CheckBox
    private var pendingLiveStart = false

    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()
        val savedOverlayUrl = preferences.getString(PREF_OVERLAY_URL, "").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(16, 16, 18))
        }

        val title = TextView(this).apply {
            text = "Overlay01"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        statusText = TextView(this).apply {
            text = "v0.1 · URL overlay + RTMP"
            setTextColor(Color.LTGRAY)
        }

        overlayUrl = EditText(this).apply {
            hint = "Overlay URL (TikFinity etc.)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(savedOverlayUrl)
        }
        loadButton = Button(this).apply {
            text = "Load overlay"
            setOnClickListener { loadOverlay() }
        }
        lockOverlay = CheckBox(this).apply {
            text = "Fix overlay (lock editing)"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean(PREF_OVERLAY_LOCKED, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(PREF_OVERLAY_LOCKED, checked).apply()
                applyOverlayLock(checked, true)
            }
        }

        rtmpServer = EditText(this).apply {
            hint = "RTMP/RTMPS server URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(preferences.getString(PREF_RTMP_SERVER, ""))
        }
        streamKey = EditText(this).apply {
            hint = "Stream key (optional if URL already complete)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(preferences.getString(PREF_STREAM_KEY, ""))
        }
        useMicrophone = CheckBox(this).apply {
            text = "Use microphone instead of game audio"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean(PREF_USE_MIC, false)
        }

        val startButton = Button(this).apply {
            text = "Start live"
            setOnClickListener { beginLive() }
        }
        val stopButton = Button(this).apply {
            text = "Stop live"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                statusText.text = "Live stopped"
            }
        }

        overlayPreview = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(overlayUrl)
        root.addView(loadButton)
        root.addView(lockOverlay)
        root.addView(rtmpServer)
        root.addView(streamKey)
        root.addView(useMicrophone)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(overlayPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        applyOverlayLock(lockOverlay.isChecked, false)
        if (savedOverlayUrl.isNotBlank()) overlayPreview.loadUrl(savedOverlayUrl)
        return root
    }

    private fun applyOverlayLock(locked: Boolean, announce: Boolean) {
        overlayUrl.isEnabled = !locked
        loadButton.isEnabled = !locked
        if (announce) statusText.text = if (locked) "Overlay fixed" else "Overlay editing enabled"
    }

    private fun loadOverlay() {
        if (lockOverlay.isChecked) return
        val raw = overlayUrl.text.toString().trim()
        if (!isHttpUrl(raw)) {
            Toast.makeText(this, "Use a valid HTTP/HTTPS overlay URL", Toast.LENGTH_SHORT).show()
            return
        }
        preferences.edit().putString(PREF_OVERLAY_URL, raw).apply()
        overlayPreview.loadUrl(raw)
        statusText.text = "Overlay loaded"
    }

    private fun beginLive() {
        val server = rtmpServer.text.toString().trim()
        val key = streamKey.text.toString().trim()
        val endpoint = buildEndpoint(server, key)
        if (!(endpoint.startsWith("rtmp://") || endpoint.startsWith("rtmps://"))) {
            Toast.makeText(this, "Enter a valid RTMP/RTMPS server", Toast.LENGTH_SHORT).show()
            return
        }
        preferences.edit()
            .putString(PREF_RTMP_SERVER, server)
            .putString(PREF_STREAM_KEY, key)
            .putBoolean(PREF_USE_MIC, useMicrophone.isChecked)
            .apply()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLiveStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else {
            requestCapturePermission()
        }
    }

    private fun requestCapturePermission() {
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        statusText.text = "Waiting for screen capture permission…"
    }

    @Deprecated("Kept dependency-free for the minimal native UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            statusText.text = "Screen capture permission denied"
            return
        }

        val endpoint = buildEndpoint(rtmpServer.text.toString().trim(), streamKey.text.toString().trim())
        val savedOverlayUrl = preferences.getString(PREF_OVERLAY_URL, "").orEmpty()
        val serviceIntent = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_OVERLAY_URL, savedOverlayUrl)
            .putExtra(CaptureService.EXTRA_RTMP_ENDPOINT, endpoint)
            .putExtra(CaptureService.EXTRA_USE_MICROPHONE, useMicrophone.isChecked)

        startForegroundService(serviceIntent)
        statusText.text = "Live pipeline starting…"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && pendingLiveStart) requestCapturePermission()
        else statusText.text = "Audio permission is required for live"
        pendingLiveStart = false
    }

    override fun onDestroy() {
        overlayPreview.destroy()
        super.onDestroy()
    }

    private fun buildEndpoint(server: String, key: String): String {
        if (key.isBlank()) return server
        return server.trimEnd('/') + "/" + key.trimStart('/')
    }

    private fun isHttpUrl(raw: String): Boolean {
        val scheme = runCatching { Uri.parse(raw).scheme?.lowercase() }.getOrNull()
        return raw.isNotBlank() && (scheme == "https" || scheme == "http")
    }

    private companion object {
        const val REQUEST_CAPTURE = 1001
        const val REQUEST_AUDIO = 1002
        const val PREFS_NAME = "overlay01_settings"
        const val PREF_OVERLAY_URL = "overlay_url"
        const val PREF_OVERLAY_LOCKED = "overlay_locked"
        const val PREF_RTMP_SERVER = "rtmp_server"
        const val PREF_STREAM_KEY = "stream_key"
        const val PREF_USE_MIC = "use_microphone"
    }
}
