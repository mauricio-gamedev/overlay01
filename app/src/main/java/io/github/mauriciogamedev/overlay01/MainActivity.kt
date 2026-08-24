package io.github.mauriciogamedev.overlay01

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.mauriciogamedev.overlay01.service.OverlayService

class MainActivity : Activity() {

    private lateinit var overlayUrl: EditText
    private lateinit var lockOverlay: CheckBox
    private lateinit var permissionButton: Button
    private lateinit var statusText: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var sizeSeekBar: SeekBar

    private val preferences by lazy {
        getSharedPreferences(OverlayService.PREFS_NAME, MODE_PRIVATE)
    }

    private var startAfterOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateUiState()

        if (startAfterOverlayPermission && Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = false
            startOrUpdateOverlay()
        }
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val gap = (10 * density).toInt()
        val savedScale = preferences
            .getInt(OverlayService.PREF_SCALE_PERCENT, OverlayService.DEFAULT_SCALE_PERCENT)
            .coerceIn(OverlayService.MIN_SCALE_PERCENT, OverlayService.MAX_SCALE_PERCENT)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(14, 14, 16))
        }

        content.addView(TextView(this).apply {
            text = "Overlay01"
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        content.addView(TextView(this).apply {
            text = "Cole o link da overlay e ative. Ela fica por cima do jogo sem bloquear nenhum toque."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, gap, 0, gap)
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(180, 220, 180))
            setPadding(0, 0, 0, gap)
        }
        content.addView(statusText)

        overlayUrl = EditText(this).apply {
            hint = "https://... link do TikFinity"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(preferences.getString(OverlayService.PREF_URL, ""))
        }
        content.addView(overlayUrl)

        sizeLabel = TextView(this).apply {
            text = "Tamanho da overlay: $savedScale%"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, gap, 0, 0)
        }
        content.addView(sizeLabel)

        sizeSeekBar = SeekBar(this).apply {
            max = OverlayService.MAX_SCALE_PERCENT - OverlayService.MIN_SCALE_PERCENT
            progress = savedScale - OverlayService.MIN_SCALE_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = progress + OverlayService.MIN_SCALE_PERCENT
                    sizeLabel.text = "Tamanho da overlay: $percent%"
                    if (fromUser) {
                        preferences.edit().putInt(OverlayService.PREF_SCALE_PERCENT, percent).apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!lockOverlay.isChecked) applyResizeToActiveOverlay()
                }
            })
        }
        content.addView(sizeSeekBar)

        content.addView(Button(this).apply {
            text = "Restaurar tamanho 100%"
            setOnClickListener {
                if (lockOverlay.isChecked) return@setOnClickListener
                sizeSeekBar.progress = OverlayService.MAX_SCALE_PERCENT - OverlayService.MIN_SCALE_PERCENT
                preferences.edit()
                    .putInt(OverlayService.PREF_SCALE_PERCENT, OverlayService.MAX_SCALE_PERCENT)
                    .apply()
                applyResizeToActiveOverlay()
            }
        })

        lockOverlay = CheckBox(this).apply {
            text = "Fixar configuração"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean(OverlayService.PREF_LOCKED, false)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit().putBoolean(OverlayService.PREF_LOCKED, checked).apply()
                applyLockState(checked)
            }
        }
        content.addView(lockOverlay)

        content.addView(TextView(this).apply {
            text = "Touch-through fica sempre ativo: tocar em cima da overlay continua controlando o jogo."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, gap)
        })

        permissionButton = Button(this).apply {
            text = "Permitir sobre outros apps"
            setOnClickListener { requestOverlayPermission() }
        }
        content.addView(permissionButton)

        content.addView(Button(this).apply {
            text = "Ativar / atualizar overlay"
            setOnClickListener { startOrUpdateOverlay() }
        })

        content.addView(Button(this).apply {
            text = "Desativar overlay"
            setOnClickListener { stopOverlay() }
        })

        content.addView(TextView(this).apply {
            text = "O app mantém apenas uma WebView transparente e um serviço persistente. Mudanças de tamanho não recarregam o layout."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, gap, 0, 0)
        })

        applyLockState(lockOverlay.isChecked)
        updateUiState()

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(14, 14, 16))
            addView(content)
        }
    }

    private fun applyLockState(locked: Boolean) {
        overlayUrl.isEnabled = !locked
        overlayUrl.alpha = if (locked) 0.65f else 1f
        sizeSeekBar.isEnabled = !locked
        sizeSeekBar.alpha = if (locked) 0.65f else 1f
    }

    private fun updateUiState() {
        val allowed = Settings.canDrawOverlays(this)
        val active = preferences.getBoolean(OverlayService.PREF_VISIBLE, false)

        permissionButton.isEnabled = !allowed
        permissionButton.text = if (allowed) {
            "Permissão concedida ✓"
        } else {
            "Permitir sobre outros apps"
        }

        statusText.text = when {
            !allowed -> "Falta apenas a permissão de sobreposição"
            active -> "Overlay ativa · pode abrir o jogo"
            else -> "Pronto para ativar"
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            updateUiState()
            return
        }

        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun currentScalePercent(): Int {
        return (sizeSeekBar.progress + OverlayService.MIN_SCALE_PERCENT)
            .coerceIn(OverlayService.MIN_SCALE_PERCENT, OverlayService.MAX_SCALE_PERCENT)
    }

    private fun startOrUpdateOverlay() {
        val raw = overlayUrl.text.toString().trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()

        if (raw.isBlank() || uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Cole um link HTTPS válido da overlay",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val scalePercent = currentScalePercent()
        preferences.edit()
            .putString(OverlayService.PREF_URL, raw)
            .putInt(OverlayService.PREF_SCALE_PERCENT, scalePercent)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = true
            requestOverlayPermission()
            return
        }

        val action = if (preferences.getBoolean(OverlayService.PREF_VISIBLE, false)) {
            OverlayService.ACTION_UPDATE
        } else {
            OverlayService.ACTION_SHOW
        }

        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(action)
                .putExtra(OverlayService.EXTRA_URL, raw)
                .putExtra(OverlayService.EXTRA_SCALE_PERCENT, scalePercent)
        )

        preferences.edit().putBoolean(OverlayService.PREF_VISIBLE, true).apply()
        statusText.text = "Overlay ativa · pode abrir o jogo"
    }

    private fun applyResizeToActiveOverlay() {
        val scalePercent = currentScalePercent()
        preferences.edit().putInt(OverlayService.PREF_SCALE_PERCENT, scalePercent).apply()

        if (!Settings.canDrawOverlays(this) ||
            !preferences.getBoolean(OverlayService.PREF_VISIBLE, false)
        ) {
            return
        }

        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_RESIZE)
                .putExtra(OverlayService.EXTRA_SCALE_PERCENT, scalePercent)
        )
    }

    private fun stopOverlay() {
        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_STOP)
        )
        preferences.edit().putBoolean(OverlayService.PREF_VISIBLE, false).apply()
        statusText.text = "Overlay desativada"
    }
}
