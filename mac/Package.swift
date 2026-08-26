// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AirMateMac",
    platforms: [.macOS("27.0")],
    products: [.executable(name: "AirMateMac", targets: ["AirMateMac"])],
    targets: [
        .target(
            name: "AirMatePrivateCG",
            path: "Sources/AirMatePrivateCG",
            publicHeadersPath: "include",
            cxxSettings: [.headerSearchPath("include")],
            linkerSettings: [.linkedFramework("AppKit"), .linkedFramework("CoreGraphics")]
        ),
        .executableTarget(
            name: "AirMateMac",
            dependencies: ["AirMatePrivateCG"],
            path: "Sources/AirMateMac",
            linkerSettings: [
                .linkedFramework("AppKit"), .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo"),
                .linkedFramework("IOSurface"), .linkedFramework("VideoToolbox"),
                .linkedFramework("Network")
            ]
        ),
        .testTarget(name: "AirMateMacTests", dependencies: ["AirMateMac"], path: "Tests")
    ]
)
