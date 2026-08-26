import Foundation
import CoreGraphics
import CoreVideo
import IOSurface

final class DisplayCapture: @unchecked Sendable {
    private let queue = DispatchQueue(label: "AirMate.Capture", qos: .userInteractive)
    private let encoder: LatestFrameEncoder
    private var stream: CGDisplayStream?
    private var nextFrameID: UInt64 = 1

    init(displayID: CGDirectDisplayID, width: Int, height: Int, encoder: LatestFrameEncoder) throws {
        self.encoder = encoder
        let properties: [CFString: Any] = [
            CGDisplayStream.showCursor: true,
            CGDisplayStream.minimumFrameTime: 1.0 / 60.0,
            CGDisplayStream.queueDepth: 1
        ]
        guard let created = CGDisplayStream(dispatchQueueDisplay: displayID,
                                            outputWidth: width, outputHeight: height,
                                            pixelFormat: Int32(kCVPixelFormatType_32BGRA),
                                            properties: properties as CFDictionary,
                                            queue: queue,
                                            handler: { [weak self] status, _, surface, _ in
                                                guard status == .frameComplete, let surface else { return }
                                                self?.consume(surface)
                                            }) else {
            throw DisplayError.creation("CGDisplayStream creation failed for display \(displayID)")
        }
        stream = created
    }

    func start() throws {
        guard let stream else { return }
        let result = stream.start()
        guard result == .success else { throw DisplayError.creation("CGDisplayStream start failed: \(result.rawValue)") }
    }

    func stop() { _ = stream?.stop(); stream = nil }

    private func consume(_ surface: IOSurface) {
        var unmanagedBuffer: Unmanaged<CVPixelBuffer>?
        guard CVPixelBufferCreateWithIOSurface(kCFAllocatorDefault, surface, nil, &unmanagedBuffer) == kCVReturnSuccess,
              let unmanagedBuffer else { return }
        let buffer = unmanagedBuffer.takeRetainedValue()
        let id = nextFrameID
        nextFrameID &+= 1
        let nanos = DispatchTime.now().uptimeNanoseconds
        Diagnostics.shared.mutate { $0.captured += 1 }
        encoder.submit(CapturedFrame(id: id, captureNanos: nanos, pixelBuffer: buffer))
    }

    deinit { stop() }
}
