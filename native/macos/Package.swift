// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CopyMore",
    platforms: [.macOS("15.0")],
    products: [.executable(name: "CopyMore", targets: ["CopyMore"]),
               .executable(name: "CopyMoreHarness", targets: ["CopyMoreHarness"])],
    targets: [
        .target(name: "CopyMoreCore"),
        .executableTarget(name: "CopyMore", dependencies: ["CopyMoreCore"]),
        .executableTarget(name: "CopyMoreHarness", dependencies: ["CopyMoreCore"]),
        .testTarget(name: "CopyMoreCoreTests", dependencies: ["CopyMoreCore"])
    ]
)
