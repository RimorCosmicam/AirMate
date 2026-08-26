import Foundation
import CoreGraphics
import CoreMedia
import CoreVideo
import ScreenCaptureKit

final class DisplayCapture: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    private let queue = DispatchQueue(label: "AirMate.Capture", qos: .userInteractive)
    private let displayID: CGDirectDisplayID
    private let width: Int
    private let height: Int
    private let encoder: LatestFrameEncoder
    private var stream: SCStream?
    private var nextFrameID: UInt64 = 1

    init(displayID: CGDirectDisplayID, width: Int, height: Int, encoder: LatestFrameEncoder) {
        self.displayID = displayID
        self.width = width
        self.height = height
        self.encoder = encoder
        super.init()
    }

    func start() async throws {
        let content = try await SCShareableContent.excludingDesktopWindows(
            false,
            onScreenWindowsOnly: false
        )
        guard let display = content.displays.first(where: { $0.displayID == displayID }) else {
            throw DisplayError.creation("ScreenCaptureKit could not find AirMate Display \(displayID)")
        }

        let configuration = SCStreamConfiguration()
        configuration.width = width
        configuration.height = height
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        // ScreenCaptureKit's minimum supported queue depth is three. The
        // downstream encoder remains latest-frame-only, so stale frames still
        // cannot accumulate beyond the capture handoff.
        configuration.queueDepth = 3
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.showsCursor = true
        configuration.capturesAudio = false

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let created = SCStream(filter: filter, configuration: configuration, delegate: self)
        try created.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        stream = created
        try await created.startCapture()
    }

    func stop() {
        guard let active = stream else { return }
        stream = nil
        active.stopCapture { error in
            if let error {
                Diagnostics.shared.displayLog.error("ScreenCaptureKit stop failed: \(error.localizedDescription)")
            }
        }
    }

    func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of outputType: SCStreamOutputType
    ) {
        guard outputType == .screen,
              sampleBuffer.isValid,
              let pixelBuffer = sampleBuffer.imageBuffer else { return }

        let id = nextFrameID
        nextFrameID &+= 1
        let nanos = DispatchTime.now().uptimeNanoseconds
        Diagnostics.shared.mutate { $0.captured += 1 }
        encoder.submit(CapturedFrame(id: id, captureNanos: nanos, pixelBuffer: pixelBuffer))
    }

    func stream(_ stream: SCStream, didStopWithError error: any Error) {
        Diagnostics.shared.displayLog.error("ScreenCaptureKit stopped: \(error.localizedDescription)")
    }

    deinit { stop() }
}
