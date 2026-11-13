// swift-tools-version:5.10
// Platform-neutral core of the iOS app: ainder.ai client, hybrid routing rules, agent steps and
// privacy checks. No Firebase here, so `swift test` runs on a Mac without a simulator.
import PackageDescription

let package = Package(
    name: "AinderHybridCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AinderHybridCore", targets: ["AinderHybridCore"]),
    ],
    targets: [
        .target(name: "AinderHybridCore"),
        .testTarget(name: "AinderHybridCoreTests", dependencies: ["AinderHybridCore"]),
    ]
)
