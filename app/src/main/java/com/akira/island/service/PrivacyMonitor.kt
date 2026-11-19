package com.akira.island.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

data class PrivacyState(val camera: Boolean = false, val mic: Boolean = false)

/**
 * Whether the camera or the microphone is being used by some app right now. Only the public callbacks are used:
 * Android doesn't tell an ordinary app which app is responsible, or when location is used.
 */
object PrivacyMonitor {
    val state = MutableStateFlow(PrivacyState())

    /** Starts watching; close the result to stop. */
    fun start(context: Context): AutoCloseable {
        val handler = Handler(Looper.getMainLooper())
        val busyCameras = HashSet<String>()
        val camera = context.getSystemService(CameraManager::class.java)
        val cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { busyCameras += cameraId; state.value = state.value.copy(camera = true) }
            override fun onCameraAvailable(cameraId: String) { busyCameras -= cameraId; state.value = state.value.copy(camera = busyCameras.isNotEmpty()) }
        }
        runCatching { camera?.registerAvailabilityCallback(cameraCallback, handler) }

        val audio = context.getSystemService(AudioManager::class.java)
        fun recording(configs: List<AudioRecordingConfiguration>) =
            configs.any { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !it.isClientSilenced }
        val audioCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                state.value = state.value.copy(mic = recording(configs))
            }
        }
        runCatching { audio?.registerAudioRecordingCallback(audioCallback, handler) }
        runCatching { state.value = state.value.copy(mic = recording(audio?.activeRecordingConfigurations.orEmpty())) }

        return AutoCloseable {
            runCatching { camera?.unregisterAvailabilityCallback(cameraCallback) }
            runCatching { audio?.unregisterAudioRecordingCallback(audioCallback) }
            state.value = PrivacyState()
        }
    }
}
