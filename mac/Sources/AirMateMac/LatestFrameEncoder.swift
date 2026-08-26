import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox

struct CapturedFrame: @unchecked Sendable {
    let id: UInt64
    let captureNanos: UInt64
    let pixelBuffer: CVPixelBuffer
}

final class LatestFrameEncoder: @unchecked Sendable {
    private let stateLock = NSLock()
    private let encodeQueue = DispatchQueue(label: "AirMate.Encoder", qos: .userInteractive)
    private var session: VTCompressionSession?
    private var encoding = false
    private var pending: CapturedFrame?
    private let sender: UDPSender
    private let width: Int32
    private let height: Int32
    private let hevc: Bool

    init(width: Int32, height: Int32, sender: UDPSender, preferHEVC: Bool = true) throws {
        self.width = width
        self.height = height
        self.sender = sender
        self.hevc = preferHEVC
        try createSession()
    }

    func submit(_ frame: CapturedFrame) {
        var startNow = false
        stateLock.withLock {
            if encoding {
                if pending != nil { Diagnostics.shared.mutate { $0.droppedPending += 1 } }
                pending = frame
                Diagnostics.shared.mutate { $0.pendingFrames = 1 }
            } else {
                encoding = true
                startNow = true
            }
        }
        if startNow { encodeQueue.async { [weak self] in self?.encode(frame) } }
    }

    private func createSession() throws {
        let specification: [CFString: Any] = [
            kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true,
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true
        ]
        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault, width: width, height: height,
            codecType: hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
            encoderSpecification: specification as CFDictionary,
            imageBufferAttributes: [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA] as CFDictionary,
            compressedDataAllocator: nil,
            outputCallback: compressionCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &created
        )
        guard status == noErr, let created else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        session = created
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 120 as CFNumber)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: 60 as CFNumber)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_AverageBitRate, value: 12_000_000 as CFNumber)
        VTSessionSetProperty(created, key: kVTCompressionPropertyKey_DataRateLimits, value: [1_500_000, 1] as CFArray)
        VTCompressionSessionPrepareToEncodeFrames(created)
    }

    private func encode(_ frame: CapturedFrame) {
        guard let session else { completeFrame(); return }
        let metadata = FrameMetadata(id: frame.id, captureNanos: frame.captureNanos)
        let refcon = Unmanaged.passRetained(metadata).toOpaque()
        var flags = VTEncodeInfoFlags()
        let status = VTCompressionSessionEncodeFrame(
            session, imageBuffer: frame.pixelBuffer,
            presentationTimeStamp: CMTime(value: CMTimeValue(frame.id), timescale: 60),
            duration: CMTime(value: 1, timescale: 60), frameProperties: nil,
            sourceFrameRefcon: refcon, infoFlagsOut: &flags
        )
        if status != noErr {
            Unmanaged<FrameMetadata>.fromOpaque(refcon).release()
            Diagnostics.shared.encoderLog.error("encode submission failed: \(status)")
            completeFrame()
        } else {
            Diagnostics.shared.mutate { $0.submitted += 1 }
        }
    }

    fileprivate func encoded(status: OSStatus, sampleBuffer: CMSampleBuffer?, metadata: FrameMetadata) {
        defer { completeFrame() }
        guard status == noErr, let sampleBuffer, CMSampleBufferDataIsReady(sampleBuffer),
              let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        let notSync = (CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]])?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        let keyframe = !notSync
        var annexB = Data()
        if keyframe, let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            appendParameterSets(format: format, to: &annexB)
        }
        var totalLength = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                          totalLengthOut: &totalLength, dataPointerOut: &dataPointer) == kCMBlockBufferNoErr,
              let dataPointer else { return }
        let bytes = UnsafeRawBufferPointer(start: dataPointer, count: totalLength)
        var offset = 0
        while offset + 4 <= totalLength {
            let length = bytes.loadUnaligned(fromByteOffset: offset, as: UInt32.self).bigEndian
            offset += 4
            guard length > 0, offset + Int(length) <= totalLength else { return }
            annexB.append(contentsOf: [0, 0, 0, 1])
            annexB.append(contentsOf: bytes.bindMemory(to: UInt8.self)[offset ..< offset + Int(length)])
            offset += Int(length)
        }
        sender.send(accessUnit: annexB, frameID: metadata.id, captureNanos: metadata.captureNanos, keyframe: keyframe, hevc: hevc)
        Diagnostics.shared.mutate { $0.encoded += 1 }
    }

    private func appendParameterSets(format: CMFormatDescription, to data: inout Data) {
        let setCount = hevc ? 3 : 2
        for index in 0..<setCount {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            var count = 0
            var nalHeaderLength: Int32 = 0
            let status: OSStatus
            if hevc {
                status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, parameterSetIndex: index,
                    parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalHeaderLength)
            } else {
                status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index,
                    parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalHeaderLength)
            }
            if status == noErr, let pointer {
                data.append(contentsOf: [0, 0, 0, 1])
                data.append(pointer, count: size)
            }
        }
    }

    private func completeFrame() {
        var next: CapturedFrame?
        stateLock.withLock {
            next = pending
            pending = nil
            Diagnostics.shared.mutate { $0.pendingFrames = 0 }
            if next == nil { encoding = false }
        }
        if let next { encodeQueue.async { [weak self] in self?.encode(next) } }
    }

    deinit { if let session { VTCompressionSessionInvalidate(session) } }
}

private final class FrameMetadata {
    let id: UInt64
    let captureNanos: UInt64
    init(id: UInt64, captureNanos: UInt64) { self.id = id; self.captureNanos = captureNanos }
}

private let compressionCallback: VTCompressionOutputCallback = { refcon, sourceFrameRefcon, status, _, sampleBuffer in
    guard let refcon, let sourceFrameRefcon else { return }
    let encoder = Unmanaged<LatestFrameEncoder>.fromOpaque(refcon).takeUnretainedValue()
    let metadata = Unmanaged<FrameMetadata>.fromOpaque(sourceFrameRefcon).takeRetainedValue()
    encoder.encoded(status: status, sampleBuffer: sampleBuffer, metadata: metadata)
}
