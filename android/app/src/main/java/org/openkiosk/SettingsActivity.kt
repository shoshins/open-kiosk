package org.openkiosk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.openkiosk.mqtt.MqttKiosk
import org.openkiosk.security.Hashing
import org.openkiosk.security.SecretStore
import org.openkiosk.security.Throttle

/**
 * PIN-protected settings. Reached from the kiosk by five taps in the
 * top-left corner. Closes itself after two minutes without interaction.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private val handler = Handler(Looper.getMainLooper())
    private val sessionTimeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the settings screen out of screenshots and the recents preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = Settings(this)
        secrets = SecretStore(this)
        if (settings.pinHash == null) showCreatePin() else showPinPrompt()
        touch()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun touch() {
        handler.removeCallbacks(sessionTimeout)
        handler.postDelayed(sessionTimeout, SESSION_MS)
    }

    // PIN

    private fun showCreatePin() {
        val first = pinField(R.string.pin_new)
        val second = pinField(R.string.pin_repeat)
        val error = TextView(this)
        page(getString(R.string.pin_create_title), first, second, error, button(R.string.pin_save) {
            val pin = first.text.toString()
            when {
                !PIN.matches(pin) -> error.setText(R.string.pin_rules)
                pin != second.text.toString() -> error.setText(R.string.pin_mismatch)
                else -> {
                    settings.pinHash = Hashing.hash(pin)
                    showForm()
                }
            }
        })
    }

    private fun showPinPrompt() {
        val field = pinField(R.string.pin_enter)
        val error = TextView(this)
        page(getString(R.string.settings_title), field, error, button(R.string.pin_unlock) {
            val wait = throttle.remainingLockMs()
            if (wait > 0) {
                error.text = getString(R.string.pin_locked, (wait + 999) / 1000)
                return@button
            }
            if (Hashing.verify(field.text.toString(), settings.pinHash)) {
                throttle.recordSuccess()
                showForm()
            } else {
                throttle.recordFailure()
                field.text.clear()
                error.setText(R.string.pin_wrong)
            }
        })
    }

    // The form

    private fun showForm() {
        val startUrl = text(settings.startUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val keepOn = check(R.string.keep_screen_on, settings.keepScreenOn)

        val modes = ScreensaverMode.entries
        val saverMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.id })
            setSelection(modes.indexOf(settings.screensaverMode))
        }
        val saverTimeout = number(settings.screensaverTimeoutMinutes)
        val saverDim = number(settings.screensaverDimBrightness)
        val saverUrl = text(settings.screensaverUrl, InputType.TYPE_TEXT_VARIATION_URI)

        val restOn = check(R.string.rest_enable, settings.restEnabled)
        val restLan = check(R.string.rest_lan, settings.restLan)
        val restPort = number(settings.restPort)

        val mqttOn = check(R.string.mqtt_enable, settings.mqttEnabled)
        val mqttHost = text(settings.mqttHost, InputType.TYPE_TEXT_VARIATION_URI)
        val mqttPort = number(settings.mqttPort)
        val mqttTls = check(R.string.mqtt_tls, settings.mqttTls)
        val mqttUser = text(settings.mqttUsername, InputType.TYPE_TEXT_VARIATION_NORMAL)
        val mqttPass = text(null, InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setHint(R.string.mqtt_password_hint) }
        val mqttDiscovery = check(R.string.mqtt_discovery, settings.mqttDiscovery)

        val bridgeOn = check(R.string.bridge_enable, settings.bridgeEnabled)
        val secondaryOn = check(R.string.secondary_enable, settings.secondaryScreenEnabled)
        val allowlist = text(settings.launchAllowlist.sorted().joinToString("\n"), InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            isSingleLine = false
        }
        val error = TextView(this)

        val save = button(R.string.settings_save) {
            val problem = validate(startUrl, saverTimeout, saverDim, saverUrl, restPort, mqttOn, mqttHost, mqttPort, allowlist)
            if (problem != null) {
                error.text = problem
                return@button
            }
            settings.startUrl = startUrl.value()
            settings.keepScreenOn = keepOn.isChecked
            settings.screensaverMode = modes[saverMode.selectedItemPosition]
            settings.screensaverTimeoutMinutes = saverTimeout.value().toInt()
            settings.screensaverDimBrightness = saverDim.value().toInt()
            settings.screensaverUrl = saverUrl.value().ifEmpty { null }
            settings.restEnabled = restOn.isChecked
            settings.restLan = restLan.isChecked
            settings.restPort = restPort.value().toInt()
            settings.mqttEnabled = mqttOn.isChecked
            settings.mqttHost = mqttHost.value().ifEmpty { null }
            settings.mqttPort = mqttPort.value().toInt()
            settings.mqttTls = mqttTls.isChecked
            settings.mqttUsername = mqttUser.value().ifEmpty { null }
            if (mqttPass.value().isNotEmpty()) secrets.put(MqttKiosk.PASSWORD_SECRET, mqttPass.value())
            settings.mqttDiscovery = mqttDiscovery.isChecked
            settings.bridgeEnabled = bridgeOn.isChecked
            settings.secondaryScreenEnabled = secondaryOn.isChecked
            settings.launchAllowlist = packages(allowlist.value())
            KioskActivity.settingsChanged = true
            finish()
        }

        page(
            getString(R.string.settings_title),
            section(R.string.section_dashboard), label(R.string.start_url), startUrl, keepOn,
            section(R.string.section_screensaver), label(R.string.saver_mode), saverMode,
            label(R.string.saver_timeout), saverTimeout, label(R.string.saver_dim), saverDim,
            label(R.string.saver_url), saverUrl,
            section(R.string.section_rest), restOn, restLan, label(R.string.rest_port), restPort,
            button(R.string.rest_new_token) { newToken() },
            section(R.string.section_mqtt), mqttOn, label(R.string.mqtt_host), mqttHost, label(R.string.mqtt_port), mqttPort,
            mqttTls, label(R.string.mqtt_username), mqttUser, label(R.string.mqtt_password), mqttPass, mqttDiscovery,
            section(R.string.section_bridge), bridgeOn, secondaryOn,
            section(R.string.section_apps), label(R.string.launch_allowlist), allowlist,
            section(R.string.section_security), button(R.string.pin_change) { showCreatePin() },
            button(R.string.open_android_settings) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) },
            section(R.string.section_device), TextView(this).apply { text = Telemetry.device(this@SettingsActivity).toString(2) },
            error, save,
        )
    }

    private fun validate(
        startUrl: EditText, saverTimeout: EditText, saverDim: EditText, saverUrl: EditText,
        restPort: EditText, mqttOn: CheckBox, mqttHost: EditText, mqttPort: EditText, allowlist: EditText,
    ): String? {
        val start = startUrl.value()
        return when {
            !UrlPolicy.isValidStartUrl(start) -> getString(R.string.setup_invalid)
            saverTimeout.value().toIntOrNull() !in 0..1440 -> getString(R.string.invalid_timeout)
            saverDim.value().toIntOrNull() !in 0..100 -> getString(R.string.invalid_dim)
            saverUrl.value().isNotEmpty() && !UrlPolicy.isAllowed(start, saverUrl.value()) -> getString(R.string.invalid_saver_url)
            restPort.value().toIntOrNull() !in 1024..65535 -> getString(R.string.invalid_rest_port)
            mqttOn.isChecked && mqttHost.value().isEmpty() -> getString(R.string.invalid_mqtt_host)
            mqttPort.value().toIntOrNull() !in 1..65535 -> getString(R.string.invalid_mqtt_port)
            packages(allowlist.value()).any { !PACKAGE.matches(it) } -> getString(R.string.invalid_packages)
            else -> null
        }
    }

    private fun newToken() {
        val token = Hashing.newToken()
        settings.restTokenHash = Hashing.tokenHash(token)
        KioskActivity.settingsChanged = true
        AlertDialog.Builder(this)
            .setTitle(R.string.rest_token_title)
            .setMessage(getString(R.string.rest_token_message, token))
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    // Small view helpers

    private fun page(title: String, vararg views: View) {
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 24f })
            views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun section(res: Int) = TextView(this).apply {
        setText(res)
        textSize = 18f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun label(res: Int) = TextView(this).apply { setText(res) }

    private fun text(value: String?, variation: Int) = EditText(this).apply {
        setText(value.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT or variation
        if (variation != InputType.TYPE_TEXT_FLAG_MULTI_LINE) setSingleLine()
    }

    private fun number(value: Int) = EditText(this).apply {
        // ASCII digits regardless of locale, so the value parses back.
        setText(String.format(java.util.Locale.ROOT, "%d", value))
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
    }

    private fun check(res: Int, value: Boolean) = CheckBox(this).apply {
        setText(res)
        isChecked = value
    }

    private fun pinField(hint: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine()
    }

    private fun button(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        setOnClickListener { onClick() }
    }

    private fun EditText.value() = text.toString().trim()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun packages(text: String) = text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    companion object {
        private const val SESSION_MS = 2 * 60_000L
        private val PIN = Regex("^[0-9]{4,12}$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        /** Shared across visits, so closing and reopening the screen does not reset it. */
        private val throttle = Throttle()
    }
}
