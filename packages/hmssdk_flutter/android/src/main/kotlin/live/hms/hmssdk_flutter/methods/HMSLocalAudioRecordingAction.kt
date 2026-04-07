package live.hms.hmssdk_flutter.methods

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import live.hms.video.sdk.HMSSDK
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class HMSLocalAudioRecordingAction {

    companion object {
        private const val TAG = "HMSAudioRecording"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNELS: Short = 1
        private const val BITS_PER_SAMPLE: Short = 16

        private var isRecording = AtomicBoolean(false)
        private var outputFilePath: String? = null
        private var recordingThread: Thread? = null
        private var audioRecord: AudioRecord? = null

        fun localAudioRecordingActions(
            call: MethodCall,
            result: Result,
            hmssdk: HMSSDK,
            context: Context,
        ) {
            when (call.method) {
                "start_local_audio_recording" -> {
                    startLocalAudioRecording(call, result, context)
                }
                "stop_local_audio_recording" -> {
                    stopLocalAudioRecording(result)
                }
                "is_local_audio_recording" -> {
                    result.success(isRecording.get())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }

        private fun startLocalAudioRecording(
            call: MethodCall,
            result: Result,
            context: Context,
        ) {
            if (isRecording.get()) {
                val map = HashMap<String, Any>()
                map["error"] = mapOf(
                    "message" to "Recording already in progress",
                    "action" to "NONE",
                    "description" to "Stop current recording before starting a new one"
                )
                result.success(map)
                return
            }

            val filePath = call.argument<String>("file_path")
            if (filePath.isNullOrEmpty()) {
                val map = HashMap<String, Any>()
                map["error"] = mapOf(
                    "message" to "file_path is required",
                    "action" to "NONE",
                    "description" to "Provide a valid file path for the recording"
                )
                result.success(map)
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val map = HashMap<String, Any>()
                map["error"] = mapOf(
                    "message" to "Microphone permission not granted",
                    "action" to "NONE",
                    "description" to "RECORD_AUDIO permission is required"
                )
                result.success(map)
                return
            }

            try {
                outputFilePath = filePath

                // Create parent directories if needed
                val file = File(filePath)
                file.parentFile?.mkdirs()

                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

                // Use VOICE_COMMUNICATION source — this is the same source WebRTC uses,
                // and on Android 10+ multiple AudioRecord instances can share the mic.
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    // Fallback to MIC source if VOICE_COMMUNICATION fails
                    audioRecord?.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize * 2
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioRecord = null
                    val map = HashMap<String, Any>()
                    map["error"] = mapOf(
                        "message" to "Failed to initialize AudioRecord",
                        "action" to "NONE",
                        "description" to "Could not create audio recorder. The microphone may be exclusively locked."
                    )
                    result.success(map)
                    return
                }

                isRecording.set(true)
                audioRecord!!.startRecording()

                // Start recording thread
                recordingThread = Thread {
                    writeAudioDataToFile(filePath, bufferSize)
                }.apply {
                    name = "HMSAudioRecordingThread"
                    start()
                }

                Log.d(TAG, "Local audio recording started: $filePath")
                result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                isRecording.set(false)
                audioRecord?.release()
                audioRecord = null
                val map = HashMap<String, Any>()
                map["error"] = mapOf(
                    "message" to "Failed to start recording",
                    "action" to "NONE",
                    "description" to (e.message ?: "Unknown error")
                )
                result.success(map)
            }
        }

        private fun writeAudioDataToFile(filePath: String, bufferSize: Int) {
            val buffer = ByteArray(bufferSize)
            var totalDataBytes: Long = 0

            try {
                val fos = FileOutputStream(filePath)

                // Write WAV header placeholder (44 bytes)
                fos.write(ByteArray(44))

                while (isRecording.get()) {
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                        totalDataBytes += bytesRead
                    }
                }

                fos.flush()
                fos.close()

                // Write WAV header with correct sizes
                writeWavHeader(filePath, totalDataBytes)

                Log.d(TAG, "Recording saved: $filePath, bytes: $totalDataBytes")
            } catch (e: Exception) {
                Log.e(TAG, "Error during recording", e)
            }
        }

        private fun stopLocalAudioRecording(result: Result) {
            if (!isRecording.get()) {
                result.success(outputFilePath)
                return
            }

            isRecording.set(false)

            try {
                // Wait for recording thread to finish
                recordingThread?.join(2000)
                recordingThread = null

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                Log.d(TAG, "Local audio recording stopped. File: $outputFilePath")
                result.success(outputFilePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop recording", e)
                audioRecord?.release()
                audioRecord = null
                result.success(outputFilePath)
            }
        }

        private fun writeWavHeader(filePath: String, dataSize: Long) {
            try {
                val raf = RandomAccessFile(filePath, "rw")
                val totalFileSize = dataSize + 36 // 44 - 8

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                header.put("RIFF".toByteArray())
                header.putInt(totalFileSize.toInt())
                header.put("WAVE".toByteArray())

                // fmt sub-chunk
                header.put("fmt ".toByteArray())
                header.putInt(16) // Sub-chunk size (PCM)
                header.putShort(1) // Audio format (PCM = 1)
                header.putShort(CHANNELS)
                header.putInt(SAMPLE_RATE)
                header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8) // Byte rate
                header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // Block align
                header.putShort(BITS_PER_SAMPLE)

                // data sub-chunk
                header.put("data".toByteArray())
                header.putInt(dataSize.toInt())

                raf.seek(0)
                raf.write(header.array())
                raf.close()

                Log.d(TAG, "WAV header written successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write WAV header", e)
            }
        }
    }
}