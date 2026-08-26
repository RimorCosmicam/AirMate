import Foundation
import Darwin

final class UDPSender: @unchecked Sendable {
    private let lock = NSLock()
    private var fd: Int32 = -1
    private var destination = sockaddr_in()
    private var hasDestination = false
    private let sessionID = UInt64.random(in: 1 ... UInt64.max)

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
        guard result == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        DispatchQueue(label: "AirMate.Network.Hello", qos: .userInteractive).async { [weak self] in self?.receiveHellos() }
    }

    private func receiveHellos() {
        var buffer = [UInt8](repeating: 0, count: 64)
        while fd >= 0 {
            var peer = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &peer) { peerPointer in
                peerPointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { address in
                    recvfrom(fd, &buffer, buffer.count, 0, address, &length)
                }
            }
            if count == 8, String(bytes: buffer[0..<8], encoding: .ascii) == "AMHELLO1" {
                lock.withLock { destination = peer; hasDestination = true }
                Diagnostics.shared.networkLog.info("Android client selected")
            } else if count < 0 && errno != EAGAIN && errno != EWOULDBLOCK {
                Diagnostics.shared.networkLog.error("recvfrom failed: \(errno)")
            }
            usleep(2_000)
        }
    }

    func send(accessUnit: Data, frameID: UInt64, captureNanos: UInt64, keyframe: Bool, hevc: Bool) {
        let target: sockaddr_in? = lock.withLock { hasDestination ? destination : nil }
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
            let sent = packet.withUnsafeBytes { bytes in
                withUnsafePointer(to: &target) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            if sent != packet.count {
                Diagnostics.shared.mutate { $0.droppedNetwork += 1 }
                return
            }
        }
    }

    deinit { if fd >= 0 { Darwin.close(fd) } }
}

