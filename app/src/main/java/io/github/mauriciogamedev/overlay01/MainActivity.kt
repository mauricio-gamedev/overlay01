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

    private data class SlotUi(
        val index: Int,
        val enabled: CheckBox,
        val url: EditText,
        val scaleLabel: TextView,
        val scale: SeekBar,
        val xLabel: TextView,
        val x: SeekBar,
        val yLabel: TextView,
        val y: SeekBar,
        val lock: CheckBox,
        val reset: Button
    )

    private val preferences by lazy {
        getSharedPreferences(OverlayService.PREFS_NAME, MODE_PRIVATE)
    }

    private val slotUis = mutableListOf<SlotUi>()
    private lateinit var permissionButton: Button
    private lateinit var statusText: TextView
    private var startAfterOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLegacySettings()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateUiState()

        if (startAfterOverlayPermission && Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = false
            applyAllOverlays()
        }
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val gap = (10 * density).toInt()

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
            text = "Agora você pode usar 2 overlays independentes, redimensionar e posicionar cada uma."
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

        content.addView(buildSlotSection(1, gap))
        content.addView(buildSlotSection(2, gap))

        content.addView(TextView(this).apply {
            text = "Touch-through continua sempre ativo: as duas overlays aparecem por cima, mas seus toques continuam indo para o jogo."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, gap, 0, gap)
        })

        permissionButton = Button(this).apply {
            text = "Permitir sobre outros apps"
            setOnClickListener { requestOverlayPermission() }
        }
        content.addView(permissionButton)

        content.addView(Button(this).apply {
            text = "Aplicar overlays"
            setOnClickListener { applyAllOverlays() }
        })

        content.addView(Button(this).apply {
            text = "Desativar todas"
            setOnClickListener { stopAllOverlays() }
        })

        content.addView(TextView(this).apply {
            text = "O app usa uma única janela touch-through com até duas WebViews. Mover ou redimensionar não recarrega os layouts."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, gap, 0, 0)
        })

        updateUiState()

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(14, 14, 16))
            addView(content)
        }
    }

    private fun buildSlotSection(index: Int, gap: Int): View {
        val savedScale = preferences
            .getInt(OverlayService.prefScale(index), OverlayService.DEFAULT_SCALE_PERCENT)
            .coerceIn(OverlayService.MIN_SCALE_PERCENT, OverlayService.MAX_SCALE_PERCENT)
        val savedX = preferences
            .getInt(OverlayService.prefX(index), OverlayService.DEFAULT_POSITION_PERCENT)
            .coerceIn(OverlayService.MIN_POSITION_PERCENT, OverlayService.MAX_POSITION_PERCENT)
        val savedY = preferences
            .getInt(OverlayService.prefY(index), OverlayService.DEFAULT_POSITION_PERCENT)
            .coerceIn(OverlayService.MIN_POSITION_PERCENT, OverlayService.MAX_POSITION_PERCENT)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, gap, 0, gap * 2)
        }

        section.addView(TextView(this).apply {
            text = "Overlay $index"
            textSize = 20f
            setTextColor(Color.WHITE)
        })

        val enabled = CheckBox(this).apply {
            text = "Mostrar Overlay $index"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean(OverlayService.prefEnabled(index), false)
        }
        section.addView(enabled)

        val url = EditText(this).apply {
            hint = "https://... link da Overlay $index"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(preferences.getString(OverlayService.prefUrl(index), ""))
        }
        section.addView(url)

        val scaleLabel = TextView(this).apply {
            text = "Tamanho: $savedScale%"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, gap, 0, 0)
        }
        section.addView(scaleLabel)

        val scale = SeekBar(this).apply {
            max = OverlayService.MAX_SCALE_PERCENT - OverlayService.MIN_SCALE_PERCENT
            progress = savedScale - OverlayService.MIN_SCALE_PERCENT
        }
        section.addView(scale)

        val xLabel = TextView(this).apply {
            text = "Posição X: ${formatPosition(savedX)}"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, gap, 0, 0)
        }
        section.addView(xLabel)

        val x = SeekBar(this).apply {
            max = OverlayService.MAX_POSITION_PERCENT - OverlayService.MIN_POSITION_PERCENT
            progress = savedX - OverlayService.MIN_POSITION_PERCENT
        }
        section.addView(x)

        val yLabel = TextView(this).apply {
            text = "Posição Y: ${formatPosition(savedY)}"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, gap, 0, 0)
        }
        section.addView(yLabel)

        val y = SeekBar(this).apply {
            max = OverlayService.MAX_POSITION_PERCENT - OverlayService.MIN_POSITION_PERCENT
            progress = savedY - OverlayService.MIN_POSITION_PERCENT
        }
        section.addView(y)

        val reset = Button(this).apply {
            text = "Centralizar e restaurar tamanho"
        }
        section.addView(reset)

        val lock = CheckBox(this).apply {
            text = "Fixar Overlay $index"
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean(OverlayService.prefLocked(index), false)
        }
        section.addView(lock)

        val slot = SlotUi(index, enabled, url, scaleLabel, scale, xLabel, x, yLabel, y, lock, reset)
        slotUis += slot

        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + OverlayService.MIN_SCALE_PERCENT
                scaleLabel.text = "Tamanho: $value%"
                if (fromUser) saveSlot(slot)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = applyLiveIfRunning(slot)
        })

        x.setOnSeekBarChangeListener(positionListener(slot, xLabel, true))
        y.setOnSeekBarChangeListener(positionListener(slot, yLabel, false))

        enabled.setOnCheckedChangeListener { _, _ -> saveSlot(slot) }

        lock.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(OverlayService.prefLocked(index), checked).apply()
            applyLockState(slot, checked)
        }

        reset.setOnClickListener {
            if (lock.isChecked) return@setOnClickListener
            scale.progress = OverlayService.MAX_SCALE_PERCENT - OverlayService.MIN_SCALE_PERCENT
            x.progress = -OverlayService.MIN_POSITION_PERCENT
            y.progress = -OverlayService.MIN_POSITION_PERCENT
            saveSlot(slot)
            applyLiveIfRunning(slot)
        }

        applyLockState(slot, lock.isChecked)
        return section
    }

    private fun positionListener(
        slot: SlotUi,
        label: TextView,
        isX: Boolean
    ): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + OverlayService.MIN_POSITION_PERCENT
                label.text = if (isX) {
                    "Posição X: ${formatPosition(value)}"
                } else {
                    "Posição Y: ${formatPosition(value)}"
                }
                if (fromUser) saveSlot(slot)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = applyLiveIfRunning(slot)
        }
    }

    private fun applyLockState(slot: SlotUi, locked: Boolean) {
        val alpha = if (locked) 0.6f else 1f
        listOf<View>(slot.enabled, slot.url, slot.scale, slot.x, slot.y, slot.reset).forEach {
            it.isEnabled = !locked
            it.alpha = alpha
        }
    }

    private fun formatPosition(value: Int): String {
        return when {
            value > 0 -> "+$value%"
            else -> "$value%"
        }
    }

    private fun scalePercent(slot: SlotUi): Int {
        return (slot.scale.progress + OverlayService.MIN_SCALE_PERCENT)
            .coerceIn(OverlayService.MIN_SCALE_PERCENT, OverlayService.MAX_SCALE_PERCENT)
    }

    private fun positionPercent(seekBar: SeekBar): Int {
        return (seekBar.progress + OverlayService.MIN_POSITION_PERCENT)
            .coerceIn(OverlayService.MIN_POSITION_PERCENT, OverlayService.MAX_POSITION_PERCENT)
    }

    private fun saveSlot(slot: SlotUi) {
        preferences.edit()
            .putBoolean(OverlayService.prefEnabled(slot.index), slot.enabled.isChecked)
            .putString(OverlayService.prefUrl(slot.index), slot.url.text.toString().trim())
            .putBoolean(OverlayService.prefLocked(slot.index), slot.lock.isChecked)
            .putInt(OverlayService.prefScale(slot.index), scalePercent(slot))
            .putInt(OverlayService.prefX(slot.index), positionPercent(slot.x))
            .putInt(OverlayService.prefY(slot.index), positionPercent(slot.y))
            .apply()
    }

    private fun saveAllSlots() {
        slotUis.forEach(::saveSlot)
    }

    private fun applyAllOverlays() {
        saveAllSlots()

        for (slot in slotUis) {
            if (slot.enabled.isChecked && !isValidHttpsUrl(slot.url.text.toString())) {
                Toast.makeText(
                    this,
                    "Overlay ${slot.index}: cole um link HTTPS válido",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        if (slotUis.none { it.enabled.isChecked }) {
            stopAllOverlays()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            startAfterOverlayPermission = true
            requestOverlayPermission()
            return
        }

        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_APPLY)
        )
        updateUiState(activeOverride = slotUis.count { it.enabled.isChecked })
    }

    private fun applyLiveIfRunning(slot: SlotUi) {
        if (slot.lock.isChecked) return
        saveSlot(slot)
        if (!Settings.canDrawOverlays(this)) return
        if (slotUis.none { it.enabled.isChecked }) return
        if (slot.enabled.isChecked && !isValidHttpsUrl(slot.url.text.toString())) return

        startForegroundService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_APPLY)
        )
    }

    private fun stopAllOverlays() {
        slotUis.forEach {
            it.enabled.isChecked = false
            saveSlot(it)
        }

        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_STOP)
        )
        statusText.text = "Overlays desativadas"
    }

    private fun updateUiState(activeOverride: Int? = null) {
        if (!::permissionButton.isInitialized || !::statusText.isInitialized) return

        val allowed = Settings.canDrawOverlays(this)
        val activeCount = activeOverride ?: (1..2).count {
            preferences.getBoolean(OverlayService.prefEnabled(it), false)
        }

        permissionButton.isEnabled = !allowed
        permissionButton.text = if (allowed) {
            "Permissão concedida ✓"
        } else {
            "Permitir sobre outros apps"
        }

        statusText.text = when {
            !allowed -> "Falta apenas a permissão de sobreposição"
            activeCount == 2 -> "2 overlays configuradas · pode abrir o jogo"
            activeCount == 1 -> "1 overlay configurada · pode abrir o jogo"
            else -> "Pronto para configurar"
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

    private fun isValidHttpsUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }

    private fun migrateLegacySettings() {
        if (preferences.getBoolean(OverlayService.PREF_MIGRATED_V4, false)) return

        val editor = preferences.edit()
        if (!preferences.contains(OverlayService.prefUrl(1))) {
            editor.putString(
                OverlayService.prefUrl(1),
                preferences.getString(OverlayService.LEGACY_PREF_URL, "") ?: ""
            )
            editor.putBoolean(
                OverlayService.prefEnabled(1),
                preferences.getBoolean(OverlayService.LEGACY_PREF_VISIBLE, false)
            )
            editor.putBoolean(
                OverlayService.prefLocked(1),
                preferences.getBoolean(OverlayService.LEGACY_PREF_LOCKED, false)
            )
            editor.putInt(
                OverlayService.prefScale(1),
                preferences.getInt(
                    OverlayService.LEGACY_PREF_SCALE,
                    OverlayService.DEFAULT_SCALE_PERCENT
                )
            )
            editor.putInt(
                OverlayService.prefX(1),
                OverlayService.DEFAULT_POSITION_PERCENT
            )
            editor.putInt(
                OverlayService.prefY(1),
                OverlayService.DEFAULT_POSITION_PERCENT
            )
        }

        if (!preferences.contains(OverlayService.prefUrl(2))) {
            editor.putString(OverlayService.prefUrl(2), "")
            editor.putBoolean(OverlayService.prefEnabled(2), false)
            editor.putBoolean(OverlayService.prefLocked(2), false)
            editor.putInt(OverlayService.prefScale(2), OverlayService.DEFAULT_SCALE_PERCENT)
            editor.putInt(OverlayService.prefX(2), OverlayService.DEFAULT_POSITION_PERCENT)
            editor.putInt(OverlayService.prefY(2), OverlayService.DEFAULT_POSITION_PERCENT)
        }

        editor.putBoolean(OverlayService.PREF_MIGRATED_V4, true).apply()
    }
}
