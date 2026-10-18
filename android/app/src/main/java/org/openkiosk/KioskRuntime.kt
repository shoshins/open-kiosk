package org.openkiosk

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.openkiosk.mqtt.MqttKiosk
import org.openkiosk.rest.RestServer

/**
 * The services that run alongside the kiosk screen: REST and MQTT. Started
 * and stopped with the kiosk activity, and restarted when settings change.
 */
object KioskRuntime {
    @Volatile private var stateProvider: (() -> KioskState)? = null
    // Both hold the application context only, which lives as long as the
    // process, so keeping them here cannot leak an activity.
    @SuppressLint("StaticFieldLeak") private var rest: RestServer? = null
    @SuppressLint("StaticFieldLeak") private var mqtt: MqttKiosk? = null

    fun state(): KioskState? = stateProvider?.invoke()

    fun start(context: Context, state: () -> KioskState) {
        stop()
        stateProvider = state
        val app = context.applicationContext
        val settings = Settings(app)
        if (settings.restEnabled && settings.restTokenHash != null) {
            rest = runCatching { RestServer(app, settings).also { it.start() } }
                .onFailure { Log.w("OpenKiosk", "REST API could not start", it) }
                .getOrNull()
        }
        if (settings.mqttEnabled) {
            mqtt = MqttKiosk(app, settings).also { it.start() }
        }
    }

    fun stop() {
        rest?.stop()
        rest = null
        mqtt?.stop()
        mqtt = null
        stateProvider = null
    }

    fun stateChanged() {
        mqtt?.stateChanged()
    }
}
