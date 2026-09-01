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
    // Kept so a frame can be asked for later, on the same terms the stream captures on.
    private var filter: SCContentFilter?
    private var configuration: SCStreamConfiguration?

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
        self.filter = filter
        self.configuration = configuration
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

    /// Fetch the display's current contents, rather than waiting for it to change.
    ///
    /// ScreenCaptureKit only produces a frame when something moves, so a display nobody is touching
    /// sends nothing — and a client that has just connected, or just had its session replaced, has
    /// nothing to show but whatever it was holding before. Nudging the cursor would provoke a frame,
    /// but it would also fight the pointer the user is actually using; asking for the contents costs
    /// nobody anything.
    func captureStill() async {
        guard let filter, let configuration else { return }
        do {
            let sample = try await SCScreenshotManager.captureSampleBuffer(
                contentFilter: filter,
                configuration: configuration
            )
            guard sample.isValid, let pixelBuffer = sample.imageBuffer else { return }
            // Submitted on the capture queue like every other frame, so the frame counter has one
            // writer rather than two.
            queue.async { [weak self] in
                guard let self else { return }
                let id = self.nextFrameID
                self.nextFrameID &+= 1
                Diagnostics.shared.mutate { $0.captured += 1 }
                self.encoder.submit(
                    CapturedFrame(
                        id: id,
                        captureNanos: DispatchTime.now().uptimeNanoseconds,
                        pixelBuffer: pixelBuffer
                    )
                )
            }
        } catch {
            Diagnostics.shared.displayLog.error("Still capture failed: \(error.localizedDescription)")
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
