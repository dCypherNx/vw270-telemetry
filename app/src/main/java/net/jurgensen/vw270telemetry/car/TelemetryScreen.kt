package net.jurgensen.vw270telemetry.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import net.jurgensen.vw270telemetry.Runtime

class TelemetryScreen(carContext: CarContext) : Screen(carContext) {
    private val listener: (net.jurgensen.vw270telemetry.data.TelemetryEvent) -> Unit = {
        if (it.source == "car" || it.source == "aa") invalidate()
    }

    init {
        Runtime.hub.addListener(listener)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Runtime.hub.removeListener(listener)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val speed = Runtime.hub.get("car", "speed_display_mps")?.value as? Number
        val odo = Runtime.hub.get("car", "odometer_m")?.value as? Number
        val fuel = Runtime.hub.get("car", "fuel_percent")?.value as? Number
        val api = Runtime.hub.get("car", "session")?.attributes?.get("car_app_api_level")

        val text = buildString {
            append("API host: ${api ?: "?"}")
            speed?.let { append(" • %.1f km/h".format(it.toDouble() * 3.6)) }
            odo?.let { append(" • %.1f km".format(it.toDouble() / 1000.0)) }
            fuel?.let { append(" • %.0f%%".format(it.toDouble())) }
        }
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Coleta ativa")
                    .addText(text)
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Modo somente leitura")
                    .addText("Nenhum comando é enviado ao veículo.")
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle("VW270 Probe")
            .build()
    }

}
