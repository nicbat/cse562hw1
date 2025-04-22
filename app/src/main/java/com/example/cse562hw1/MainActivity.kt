package com.example.cse562hw1

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.cse562hw1.ui.theme.CSE562HW1Theme
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt


class MainActivity : ComponentActivity() {

    companion object {
        const val SAMPLING_HZ = 50
        private const val DEFAULT_ALPHA = 0.9f
    }

    private val samplingPeriodUs = (1_000_000f / SAMPLING_HZ).toInt()
    private val radToDeg = 180f / Math.PI.toFloat()

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private val accState = mutableStateOf(FloatArray(3))
    private val gyrState = mutableStateOf(FloatArray(3))
    private val oriState = mutableStateOf(FloatArray(3))
    private val accBiasState = mutableStateOf(FloatArray(3))
    private val gyrBiasState = mutableStateOf(FloatArray(3))
    private val alphaState = mutableFloatStateOf(DEFAULT_ALPHA)

    private var accBias = FloatArray(3)
    private var gyrBias = FloatArray(3)

    private var yaw = 0f; private var pitch = 0f; private var roll = 0f
    private var yawOffset = 0f; private var pitchOffset = 0f; private var rollOffset = 0f
    private var lastGyroTimestamp = 0L

    private val maxSamples = SAMPLING_HZ * 60
    private val yawSamples = mutableStateListOf<Float>()
    private val pitchSamples = mutableStateListOf<Float>()
    private val rollSamples = mutableStateListOf<Float>()

    private val accBuf = mutableListOf<FloatArray>()
    private val gyrBuf = mutableListOf<FloatArray>()
    private var collectingBias = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        setContent {
            CSE562HW1Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    SensorScreen(Modifier.padding(pad))
                }
            }
        }
    }

    override fun onResume() { super.onResume(); registerSensors() }
    override fun onPause() { super.onPause(); sensorManager.unregisterListener(listener) }

    private fun registerSensors() {
        accelerometer?.also { sensorManager.registerListener(listener, it, samplingPeriodUs) }
        gyroscope?.also { sensorManager.registerListener(listener, it, samplingPeriodUs) }
    }

    private val listener by lazy {
        object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> handleGyro(e.values, e.timestamp)
                    Sensor.TYPE_ACCELEROMETER -> handleAccel(e.values)
                }

                if (collectingBias) when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accBuf.add(e.values.clone())
                    Sensor.TYPE_GYROSCOPE -> gyrBuf.add(e.values.clone())
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    private fun handleGyro(raw: FloatArray, timestamp: Long) {
        gyrState.value = raw.clone()

        if (lastGyroTimestamp != 0L) {
            val dt = (timestamp - lastGyroTimestamp) * 1e-9f
            yaw += raw[2] * dt * radToDeg
            pitch += raw[0] * dt * radToDeg
            roll += raw[1] * dt * radToDeg
        }
        lastGyroTimestamp = timestamp
        updateOrientation()
    }

    private fun handleAccel(raw: FloatArray) {
        accState.value = raw.clone()

        val pitchAcc = atan2(-raw[0].toDouble(), sqrt(raw[1] * raw[1] + raw[2] * raw[2].toDouble())).toFloat() * radToDeg
        val rollAcc = atan2(raw[1], raw[2]).toFloat() * radToDeg

        val α = alphaState.floatValue
        pitch = α * pitch + (1 - α) * pitchAcc
        roll = α * roll + (1 - α) * rollAcc

        updateOrientation()
    }

    private fun updateOrientation() {
        // Maintain graph buffers
        if (yawSamples.size >= maxSamples) { yawSamples.removeAt(0); pitchSamples.removeAt(0); rollSamples.removeAt(0) }
        yawSamples.add(wrapAngle(yaw - yawOffset))
        pitchSamples.add(wrapAngle(pitch - pitchOffset))
        rollSamples.add(wrapAngle(roll - rollOffset))

        oriState.value = floatArrayOf(
            wrapAngle(yaw - yawOffset),
            wrapAngle(pitch - pitchOffset),
            wrapAngle(roll - rollOffset)
        )
    }

    private fun computeBias(buf: List<FloatArray>, expectGravity: Boolean = false): FloatArray {
        if (buf.isEmpty()) return FloatArray(3)
        val trim = (buf.size * 0.1).toInt()
        fun axisAvg(index: Int) = buf.map { it[index] }.sorted().subList(trim, buf.size - trim).average().toFloat()
        val bias = floatArrayOf(axisAvg(0), axisAvg(1), axisAvg(2))
        if (expectGravity) {
            val g = 9.81f
            val mag = sqrt(bias[0].pow(2) + bias[1].pow(2) + bias[2].pow(2))
            bias[2] -= (mag - g)
        }
        return bias
    }

    private fun wrapAngle(a: Float): Float {
        var x = a; while (x > 180f) x -= 360f; while (x < -180f) x += 360f; return x
    }

    @Composable
    fun SensorScreen(modifier: Modifier = Modifier) {
        var status by remember { mutableStateOf("Idle") }

        DisposableEffect(Unit) { registerSensors(); onDispose { sensorManager.unregisterListener(listener) } }

        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val alpha = alphaState.floatValue
            val mode = when { alpha < 0.05f -> "Accel‑only"; alpha > 0.95f -> "Gyro‑only"; else -> "Complementary" }
            Text("Mode: $mode (α = ${"%.2f".format(alpha)})", style = MaterialTheme.typography.bodyLarge)
            Slider(value = alpha, onValueChange = { alphaState.floatValue = it }, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth(0.8f))

            Spacer(Modifier.height(12.dp))
            Text("Sampling: ${SAMPLING_HZ} Hz", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                accBuf.clear(); gyrBuf.clear(); collectingBias = true; status = "Collecting…"
                Handler(Looper.getMainLooper()).postDelayed({
                    collectingBias = false; status = "Calculating…"
                    accBias = computeBias(accBuf, true); gyrBias = computeBias(gyrBuf)
                    accBiasState.value = accBias.clone(); gyrBiasState.value = gyrBias.clone()
                    status = "Bias computed (${accBuf.size}/${gyrBuf.size})"
                }, 2000)
            }, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Capture Bias") }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { yawOffset = yaw; pitchOffset = pitch; rollOffset = roll; yawSamples.clear(); pitchSamples.clear(); rollSamples.clear() }, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Reset Orientation & Graph") }

            Spacer(Modifier.height(20.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Yaw: ${"%.2f".format(oriState.value[0])}", color = Color.Red, style = MaterialTheme.typography.headlineSmall)
                Text("Pitch: ${"%.2f".format(oriState.value[1])}", color = Color.Green, style = MaterialTheme.typography.headlineSmall)
                Text("Roll: ${"%.2f".format(oriState.value[2])}", color = Color.Blue, style = MaterialTheme.typography.headlineSmall)
            }

            Spacer(Modifier.height(12.dp))

            OrientationGraph(yawSamples, pitchSamples, rollSamples, 150.dp)

            Spacer(Modifier.height(20.dp))

            Text("Accelerometer (raw) X / Y / Z", style = MaterialTheme.typography.headlineSmall)
            Text("${"%.2f".format(accState.value[0])} / ${"%.2f".format(accState.value[1])} / ${"%.2f".format(accState.value[2])}")
            Spacer(Modifier.height(10.dp))
            Text("Gyroscope (raw) X / Y / Z", style = MaterialTheme.typography.headlineSmall)
            Text("${"%.2f".format(gyrState.value[0])} / ${"%.2f".format(gyrState.value[1])} / ${"%.2f".format(gyrState.value[2])}")

            Spacer(Modifier.height(24.dp))

            Text("Accelerometer Bias", style = MaterialTheme.typography.headlineSmall)
            Text("${"%.4f".format(accBiasState.value[0])} / ${"%.4f".format(accBiasState.value[1])} / ${"%.4f".format(accBiasState.value[2])}")
            Spacer(Modifier.height(6.dp))
            Text("Gyroscope Bias", style = MaterialTheme.typography.headlineSmall)
            Text("${"%.4f".format(gyrBiasState.value[0])} / ${"%.4f".format(gyrBiasState.value[1])} / ${"%.4f".format(gyrBiasState.value[2])}")

            Spacer(Modifier.height(20.dp))
        }
    }

    @Composable
    fun OrientationGraph(yaw: List<Float>, pitch: List<Float>, roll: List<Float>, height: Dp) {
        val maxAbs = listOf(yaw, pitch, roll).flatMap { it }.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1f) ?: 1f
        Canvas(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(height)
                .background(Color.Black.copy(alpha = 0.1f))
        ) {
            val n = maxSamples
            val dx = size.width / (n - 1)
            fun drawLine(samples: List<Float>, color: Color) {
                val path = Path()
                samples.takeLast(n).forEachIndexed { i, v ->
                    val x = i * dx
                    val y = size.height / 2f - (v / maxAbs) * (size.height / 2f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.5f))
            }
            drawLine(yaw.takeLast(n), Color.Red)
            drawLine(pitch.takeLast(n), Color.Green)
            drawLine(roll.takeLast(n), Color.Blue)
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun PreviewScreen() {
        CSE562HW1Theme { Text("Preview requires device sensors") }
    }
}
