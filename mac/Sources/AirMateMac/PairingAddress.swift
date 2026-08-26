import Foundation
import Darwin

enum PairingAddress {
    static func url(port: UInt16) -> String? {
        guard let host = localIPv4Address() else { return nil }
        return "airmate://pair?host=\(host)&port=\(port)"
    }

    private static func localIPv4Address() -> String? {
        var firstAddress: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&firstAddress) == 0, let firstAddress else { return nil }
        defer { freeifaddrs(firstAddress) }

        var candidates: [(priority: Int, address: String)] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddress
        while let interface = cursor?.pointee {
            defer { cursor = interface.ifa_next }
            guard let address = interface.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }

            let name = String(cString: interface.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                address,
                socklen_t(address.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            if result == 0 {
                let priority = name == "en0" ? 0 : (name.hasPrefix("en") ? 1 : 2)
                candidates.append((priority, String(cString: host)))
            }
        }
        return candidates.sorted { $0.priority < $1.priority }.first?.address
    }
}
