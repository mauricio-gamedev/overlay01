package io.github.mauriciogamedev.overlay01

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
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
    private lateinit var overlayPreview: WebView
    private lateinit var statusText: TextView
    private lateinit var loadButton: Button
    private lateinit var lockOverlay: CheckBox

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

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
            text = "Stage 3 · 720×1280 GPU compositor"
            setTextColor(Color.LTGRAY)
        }

        overlayUrl = EditText(this).apply {
            hint = "https://... overlay URL"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(preferences.getString(PREF_OVERLAY_URL, ""))
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
                applyOverlayLock(checked, announce = true)
            }
        }

        val captureButton = Button(this).apply {
            text = "Start screen capture"
            setOnClickListener {
                startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    REQUEST_CAPTURE
                )
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop capture"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_STOP)
                )
                statusText.text = "Capture stopped"
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
        root.addView(captureButton)
        root.addView(stopButton)
        root.addView(
            overlayPreview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        applyOverlayLock(lockOverlay.isChecked, announce = false)

        return root
    }

    private fun applyOverlayLock(locked: Boolean, announce: Boolean) {
        overlayUrl.isEnabled = !locked
        loadButton.isEnabled = !locked

        if (announce) {
            statusText.text = if (locked) {
                "Overlay fixed · editing locked"
            } else {
                "Overlay unlocked · editing enabled"
            }
        }
    }

    private fun loadOverlay() {
        if (lockOverlay.isChecked) return

        val raw = overlayUrl.text.toString().trim()
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()

        if (raw.isBlank() || (scheme != "https" && scheme != "http")) {
            Toast.makeText(this, "Use a valid HTTP/HTTPS overlay URL", Toast.LENGTH_SHORT).show()
            return
        }

        preferences.edit().putString(PREF_OVERLAY_URL, raw).apply()
        overlayPreview.loadUrl(raw)
        statusText.text = "Overlay loaded"
    }

    @Deprecated("Deprecated in Android, kept here to avoid an extra AndroidX dependency in the minimal core")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            statusText.text = "Screen capture permission denied"
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)

        startForegroundService(serviceIntent)
        statusText.text = "Capture session active · 9:16 compositor running"
    }

    override fun onDestroy() {
        overlayPreview.destroy()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CAPTURE = 1001
        const val PREFS_NAME = "overlay01_settings"
        const val PREF_OVERLAY_URL = "overlay_url"
        const val PREF_OVERLAY_LOCKED = "overlay_locked"
    }
}
