//
//  HMSLocalAudioRecordingAction.swift
//  hmssdk_flutter
//

import Foundation
import HMSSDK
import AVFoundation

class HMSLocalAudioRecordingAction {

    private static var isRecording = false
    private static var outputFilePath: String?
    private static var audioFile: AVAudioFile?
    private static var audioEngine: AVAudioEngine?
    private static var inputTapInstalled = false

    static func localAudioRecordingActions(_ call: FlutterMethodCall, _ result: @escaping FlutterResult, _ hmsSDK: HMSSDK?) {
        switch call.method {
        case "start_local_audio_recording":
            startLocalAudioRecording(call, result, hmsSDK)
        case "stop_local_audio_recording":
            stopLocalAudioRecording(result)
        case "is_local_audio_recording":
            result(isRecording)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private static func startLocalAudioRecording(_ call: FlutterMethodCall, _ result: @escaping FlutterResult, _ hmsSDK: HMSSDK?) {
        if isRecording {
            result(HMSErrorExtension.getError("Recording already in progress"))
            return
        }

        guard let arguments = call.arguments as? [AnyHashable: Any],
              let filePath = arguments["file_path"] as? String else {
            result(HMSErrorExtension.getError("file_path is required"))
            return
        }

        // Note: `include_remote_audio` flag is accepted but on iOS the AVAudioEngine
        // inputNode in voiceChat mode captures both mic and remote audio together.
        // AVAudioRecorder cannot be used during an active HMS session (audio session conflict).
        // So we always use AVAudioEngine on iOS regardless of the flag.

        do {
            // Replace .wav with .m4a for iOS native compatibility
            let m4aPath = (filePath as NSString).deletingPathExtension + ".m4a"
            outputFilePath = m4aPath

            let fileURL = URL(fileURLWithPath: m4aPath)
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )

            // Remove existing file if any
            if FileManager.default.fileExists(atPath: m4aPath) {
                try FileManager.default.removeItem(atPath: m4aPath)
            }

            let engine = AVAudioEngine()
            audioEngine = engine

            let inputNode = engine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)

            // Write as AAC m4a — natively supported by iOS AVPlayer
            let outputSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: inputFormat.sampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
                AVEncoderBitRateKey: 128000,
            ]

            audioFile = try AVAudioFile(
                forWriting: fileURL,
                settings: outputSettings
            )

            // Install tap — write input audio directly
            inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { buffer, _ in
                guard isRecording, let file = audioFile else { return }
                do {
                    try file.write(from: buffer)
                } catch {
                    print("[HMSAudioRecording] Error writing audio: \(error)")
                }
            }

            try engine.start()
            isRecording = true
            inputTapInstalled = true

            print("[HMSAudioRecording] Started recording to: \(m4aPath)")
            result(true)
        } catch {
            print("[HMSAudioRecording] Failed to start recording: \(error)")
            cleanup()
            result(HMSErrorExtension.getError("Failed to start recording: \(error.localizedDescription)"))
        }
    }

    private static func stopLocalAudioRecording(_ result: @escaping FlutterResult) {
        if !isRecording {
            result(outputFilePath)
            return
        }

        isRecording = false
        cleanup()

        print("[HMSAudioRecording] Stopped recording. File: \(outputFilePath ?? "nil")")
        result(outputFilePath)
    }

    private static func cleanup() {
        if inputTapInstalled {
            audioEngine?.inputNode.removeTap(onBus: 0)
            inputTapInstalled = false
        }

        audioEngine?.stop()
        audioEngine = nil
        audioFile = nil
    }
}