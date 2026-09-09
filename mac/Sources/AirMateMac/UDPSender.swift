import Foundation
import Darwin

final class UDPSender: @unchecked Sendable {
    private let lock = NSLock()
    private let receiveQueue = DispatchQueue(label: "AirMate.Network.Hello", qos: .userInteractive)
    private var fd: Int32 = -1
    private var destination = sockaddr_in()
    private var hasDestination = false
    private let sessionID = UInt64.random(in: 1 ... UInt64.max)

    /// A command from the paired client. Delivered on the main queue.
    ///
    /// Pairing is the authorisation: the device receiving the video is the device that may change
    /// it. There is no second consent step, and no authentication either — see `docs/SECURITY.md`.
    var onCommand: ((ControlPacket.Command) -> Void)?
    /// A different client has become the video destination. Delivered on the main queue.
    var onClientChanged: (() -> Void)?

    init(port: UInt16 = 48620) throws {
        fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw POSIXError(.ENOTSOCK) }
        var one: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout.size(ofValue: one)))
        // Big enough to hold a keyframe and the frames either side of it. At 256 KB the buffer
        // filled part-way through an ordinary frame, and the rest of that frame was discarded.
        var bufferSize: Int32 = 1024 * 1024
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufferSize, socklen_t(MemoryLayout.size(ofValue: bufferSize)))
        fcntl(fd, F_SETFL, O_NONBLOCK)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = INADDR_ANY.bigEndian
        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else {
            let error = POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
            Darwin.close(fd)
            fd = -1
            throw error
        }
        receiveQueue.async { [weak self] in self?.receiveHellos() }
    }

    private func receiveHellos() {
        var buffer = [UInt8](repeating: 0, count: 256)
        while true {
            var peer = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let result: (count: Int, error: Int32)? = lock.withLock {
                guard fd >= 0 else { return nil }
                let count = withUnsafeMutablePointer(to: &peer) { peerPointer in
                    peerPointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { address in
                        recvfrom(fd, &buffer, buffer.count, 0, address, &length)
                    }
                }
                return (count, errno)
            }
            guard let result else { break }

            if result.count == 8, String(bytes: buffer[0..<8], encoding: .ascii) == "AMHELLO1" {
                guard adopt(peer) else { break }
            } else if result.count > 0, let command = ControlPacket.parse(buffer, count: result.count) {
                guard handle(command, from: peer) else { break }
            } else if result.count < 0 && result.error != EAGAIN && result.error != EWOULDBLOCK {
                Diagnostics.shared.networkLog.error("recvfrom failed: \(result.error)")
            }
            usleep(2_000)
        }
    }

    /// Take this peer as the video destination. Always allowed: it only says where to send video,
    /// which the broadcast hello already does.
    private func adopt(_ peer: sockaddr_in) -> Bool {
        // The hello repeats once a second; only a genuinely new client is worth reacting to.
        let outcome: (accepted: Bool, changed: Bool) = lock.withLock {
            guard fd >= 0 else { return (false, false) }
            let changed = !hasDestination
                || destination.sin_addr.s_addr != peer.sin_addr.s_addr
                || destination.sin_port != peer.sin_port
            destination = peer
            hasDestination = true
            return (true, changed)
        }
        guard outcome.accepted else { return false }
        Diagnostics.shared.mutate { $0.lastClientHelloNanos = DispatchTime.now().uptimeNanoseconds }
        if outcome.changed {
            Diagnostics.shared.networkLog.info("Android client selected")
            DispatchQueue.main.async { [weak self] in self?.onClientChanged?() }
        }
        return true
    }

    private func handle(_ command: ControlPacket.Command, from peer: sockaddr_in) -> Bool {
        guard command.changesState else { return adopt(peer) }
        // The paired client — the one already being sent video — is the one that may change it.
        let paired = lock.withLock {
            hasDestination && destination.sin_addr.s_addr == peer.sin_addr.s_addr
        }
        if paired { DispatchQueue.main.async { [weak self] in self?.onCommand?(command) } }
        return true
    }

    func sendStatus(running: Bool, hiDPI: Bool, videoMode: Bool, width: Int, height: Int, encodedFrames: UInt64) {
        let target: (address: sockaddr_in, authorised: Bool)? = lock.withLock {
            guard fd >= 0, hasDestination else { return nil }
            return (destination, true)
        }
        guard let target else { return }
        var address = target.address
        let packet = StatusPacket.datagram(
            running: running,
            hiDPI: hiDPI,
            authorised: target.authorised,
            videoMode: videoMode,
            width: width,
            height: height,
            encodedFrames: encodedFrames
        )
        _ = send(packet, to: &address).count
    }

    func send(accessUnit: Data, frameID: UInt64, captureNanos: UInt64, keyframe: Bool, hevc: Bool) {
        let target: sockaddr_in? = lock.withLock { fd >= 0 && hasDestination ? destination : nil }
        guard var target else { return }
        let count = (accessUnit.count + VideoPacket.maximumPayloadBytes - 1) / VideoPacket.maximumPayloadBytes
        guard count > 0, count <= Int(UInt16.max) else { return }
        var flags: UInt8 = (keyframe ? 1 : 0) | (hevc ? 4 : 0)
        if keyframe { flags |= 2 }
        let budget = keyframe ? keyframeBudgetNanos : accessUnitBudgetNanos
        let deadline = DispatchTime.now().uptimeNanoseconds + budget
        for index in 0..<count {
            let start = index * VideoPacket.maximumPayloadBytes
            let end = min(start + VideoPacket.maximumPayloadBytes, accessUnit.count)
            let packet = VideoPacket.datagram(sessionID: sessionID, frameID: frameID,
                                              captureNanos: captureNanos,
                                              fragmentIndex: UInt16(index), fragmentCount: UInt16(count),
                                              flags: flags, payload: accessUnit[start..<end])
            if !sendWhole(packet, to: &target, deadline: deadline) {
                Diagnostics.shared.mutate { $0.droppedNetwork += 1 }
                return
            }
        }
    }

    /**
     Put one fragment on the wire, waiting for room rather than giving up on the frame.

     A full send buffer is not an error, it is the socket saying the link has not drained yet. The
     old code read it as a reason to abandon the rest of the access unit, which meant every frame
     large enough to fill the buffer arrived at the tablet cut off at exactly the point the buffer
     filled — and a frame missing its tail is not a worse picture, it is none: the client abandons
     it, and every frame after it refers to a picture the decoder never received. Measured on a
     Galaxy Tab A7, every single lost frame was a clean truncation and not one was a scattered gap,
     which is this and nothing to do with the network.

     Waiting also paces us. The encoder holds only the newest frame, so time spent here costs at
     worst a frame that was about to be replaced anyway.
     */
    private func sendWhole(_ packet: Data, to target: inout sockaddr_in, deadline: UInt64) -> Bool {
        while true {
            let result = send(packet, to: &target)
            if result.count == packet.count { return true }
            guard result.count < 0,
                  result.error == EAGAIN || result.error == EWOULDBLOCK else { return false }
            if DispatchTime.now().uptimeNanoseconds >= deadline { return false }
            // Short enough that a buffer draining at line rate is noticed almost at once, long
            // enough that this is not a spin.
            usleep(200)
        }
    }

    private func send(_ packet: Data, to target: inout sockaddr_in) -> (count: Int, error: Int32) {
        lock.withLock {
            guard fd >= 0 else { return (-1, EBADF) }
            // Read inside the lock: unlocking is a call of its own and may leave errno as its own.
            let count = packet.withUnsafeBytes { bytes in
                withUnsafePointer(to: &target) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            return (count, errno)
        }
    }

    /**
     How long one access unit may spend waiting for the socket before its tail is given up on.

     This has to be longer than the frame takes to physically leave. At the bitrates AirMate uses a
     100 KB frame is roughly forty milliseconds of wire time, so a budget of twenty guaranteed a
     truncation on every frame above about fifty kilobytes — the client saw a stream where almost
     every loss was a clean cut rather than a scattered gap, which is what that looks like from the
     far end.

     Reading still keeps it modest: a frame that cannot get out in this long is stale, and the frame
     behind it is the better picture. Video is far more patient, because there the missing frame is
     the motion.
     */
    var accessUnitBudgetNanos: UInt64 = 120_000_000

    /**
     What a keyframe gets instead, which is as much as it needs.

     A keyframe is several times the size of the frames around it and is therefore the one most
     likely to run out of budget — and it is also the frame the client is waiting on to start
     drawing again after a loss. Truncating it does not cost one picture, it extends the stall until
     the next one is asked for and sent, which is how a single lost fragment became a second of
     held picture. It is never worth giving up on.
     */
    var keyframeBudgetNanos: UInt64 = 1_000_000_000

    func close() {
        let descriptor = lock.withLock {
            let descriptor = fd
            fd = -1
            hasDestination = false
            return descriptor
        }
        guard descriptor >= 0 else { return }
        Darwin.close(descriptor)
        receiveQueue.sync {}
    }

    deinit { close() }
}
