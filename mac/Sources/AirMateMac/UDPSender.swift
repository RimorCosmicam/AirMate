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
        var bufferSize: Int32 = 256 * 1024
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

    func sendStatus(running: Bool, hiDPI: Bool, width: Int, height: Int, encodedFrames: UInt64) {
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
            width: width,
            height: height,
            encodedFrames: encodedFrames
        )
        _ = send(packet, to: &address)
    }

    func send(accessUnit: Data, frameID: UInt64, captureNanos: UInt64, keyframe: Bool, hevc: Bool) {
        let target: sockaddr_in? = lock.withLock { fd >= 0 && hasDestination ? destination : nil }
        guard var target else { return }
        let count = (accessUnit.count + VideoPacket.maximumPayloadBytes - 1) / VideoPacket.maximumPayloadBytes
        guard count > 0, count <= Int(UInt16.max) else { return }
        var flags: UInt8 = (keyframe ? 1 : 0) | (hevc ? 4 : 0)
        if keyframe { flags |= 2 }
        for index in 0..<count {
            let start = index * VideoPacket.maximumPayloadBytes
            let end = min(start + VideoPacket.maximumPayloadBytes, accessUnit.count)
            let packet = VideoPacket.datagram(sessionID: sessionID, frameID: frameID,
                                              captureNanos: captureNanos,
                                              fragmentIndex: UInt16(index), fragmentCount: UInt16(count),
                                              flags: flags, payload: accessUnit[start..<end])
            if send(packet, to: &target) != packet.count {
                Diagnostics.shared.mutate { $0.droppedNetwork += 1 }
                return
            }
        }
    }

    private func send(_ packet: Data, to target: inout sockaddr_in) -> Int {
        lock.withLock {
            guard fd >= 0 else { return -1 }
            return packet.withUnsafeBytes { bytes in
                withUnsafePointer(to: &target) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

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
