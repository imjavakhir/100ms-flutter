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
    private static let sampleRate: Double = 44100
    private static let channels: AVAudioChannelCount = 1

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

        do {
            outputFilePath = filePath

            // Create parent directories if needed
            let fileURL = URL(fileURLWithPath: filePath)
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )

            // Configure audio session for recording + playback
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
            try audioSession.setActive(true)

            // Setup AVAudioEngine to capture mixed audio (local mic + remote playback)
            let engine = AVAudioEngine()
            audioEngine = engine

            let inputNode = engine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)

            // Create output WAV file
            let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: inputFormat.sampleRate,
                channels: AVAudioChannelCount(channels),
                interleaved: true
            )!

            audioFile = try AVAudioFile(
                forWriting: fileURL,
                settings: outputFormat.settings,
                commonFormat: .pcmFormatInt16,
                interleaved: true
            )

            // Install tap on input node (captures microphone = local audio)
            inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { buffer, _ in
                guard isRecording, let file = audioFile else { return }

                do {
                    // Convert to mono 16-bit if needed
                    if let convertedBuffer = convertBuffer(buffer, to: outputFormat) {
                        try file.write(from: convertedBuffer)
                    } else {
                        try file.write(from: buffer)
                    }
                } catch {
                    print("[HMSAudioRecording] Error writing audio: \(error)")
                }
            }

            try engine.start()
            isRecording = true
            inputTapInstalled = true

            print("[HMSAudioRecording] Started recording to: \(filePath)")
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

    private static func convertBuffer(_ buffer: AVAudioPCMBuffer, to outputFormat: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard let converter = AVAudioConverter(from: buffer.format, to: outputFormat) else {
            return nil
        }

        let frameCapacity = AVAudioFrameCount(
            Double(buffer.frameLength) * outputFormat.sampleRate / buffer.format.sampleRate
        )

        guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCapacity) else {
            return nil
        }

        var error: NSError?
        let status = converter.convert(to: convertedBuffer, error: &error) { inNumPackets, outStatus in
            outStatus.pointee = .haveData
            return buffer
        }

        if status == .error || error != nil {
            return nil
        }

        return convertedBuffer
    }
}