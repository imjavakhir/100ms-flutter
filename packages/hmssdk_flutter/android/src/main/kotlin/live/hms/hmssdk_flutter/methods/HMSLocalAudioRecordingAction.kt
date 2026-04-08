package live.hms.hmssdk_flutter.methods

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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
        private var micAudioRecord: AudioRecord? = null

        fun localAudioRecordingActions(
            call: MethodCall,
            result: Result,
            hmssdk: HMSSDK,
            context: Context,
            activity: Activity?,
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
                result.success(errorMap("Recording already in progress", "Stop current recording before starting a new one"))
                return
            }

            val filePath = call.argument<String>("file_path")
            if (filePath.isNullOrEmpty()) {
                result.success(errorMap("file_path is required", "Provide a valid file path for the recording"))
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                result.success(errorMap("Microphone permission not granted", "RECORD_AUDIO permission is required"))
                return
            }

            val file = File(filePath)
            file.parentFile?.mkdirs()

            startRecordingMicOnly(filePath, result, context)
        }

        private fun startRecordingMicOnly(filePath: String, result: Result, context: Context) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

                micAudioRecord = createMicAudioRecord(bufferSize)
                if (micAudioRecord == null) {
                    result.success(errorMap("Failed to initialize AudioRecord", "The microphone may be exclusively locked."))
                    return
                }

                outputFilePath = filePath
                isRecording.set(true)
                micAudioRecord!!.startRecording()

                recordingThread = Thread {
                    writeAudioDataToFile(filePath, bufferSize)
                }.apply {
                    name = "HMSAudioRecordingThread"
                    start()
                }

                Log.d(TAG, "Recording started (mic-only): $filePath")
                result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mic-only recording", e)
                cleanup()
                result.success(errorMap("Failed to start recording", e.message ?: "Unknown error"))
            }
        }

        private fun createMicAudioRecord(bufferSize: Int): AudioRecord? {
            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2
                )
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
        }

        private fun writeAudioDataToFile(filePath: String, bufferSize: Int) {
            val micBuffer = ByteArray(bufferSize)
            var totalDataBytes: Long = 0

            try {
                val fos = FileOutputStream(filePath)
                fos.write(ByteArray(44)) // WAV header placeholder

                while (isRecording.get()) {
                    val micBytesRead = micAudioRecord?.read(micBuffer, 0, bufferSize) ?: -1
                    if (micBytesRead <= 0) continue
                    fos.write(micBuffer, 0, micBytesRead)
                    totalDataBytes += micBytesRead
                }

                fos.flush()
                fos.close()
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
                recordingThread?.join(2000)
                recordingThread = null

                micAudioRecord?.stop()
                micAudioRecord?.release()
                micAudioRecord = null

                Log.d(TAG, "Recording stopped. File: $outputFilePath")
                result.success(outputFilePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop recording", e)
                cleanup()
                result.success(outputFilePath)
            }
        }

        private fun cleanup() {
            isRecording.set(false)
            micAudioRecord?.release()
            micAudioRecord = null
        }

        private fun errorMap(message: String, description: String): HashMap<String, Any> {
            val map = HashMap<String, Any>()
            map["error"] = mapOf(
                "message" to message,
                "action" to "NONE",
                "description" to description
            )
            return map
        }

        private fun writeWavHeader(filePath: String, dataSize: Long) {
            try {
                val raf = RandomAccessFile(filePath, "rw")
                val totalFileSize = dataSize + 36

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(totalFileSize.toInt())
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)
                header.putShort(1)
                header.putShort(CHANNELS)
                header.putInt(SAMPLE_RATE)
                header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
                header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
                header.putShort(BITS_PER_SAMPLE)
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