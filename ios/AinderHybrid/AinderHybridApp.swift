import AinderHybridCore
import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import os
import SwiftUI

/// App Attest for TestFlight / App Store builds (App Check is required by Firebase AI Logic).
final class AttestProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? { AppAttestProvider(app: app) }
}

enum AppConfig {
    static let ainderBaseURL = URL(string: "https://ainder.ai")!
    /// Default only; the model list is checked again before release.
    static let cloudModel = "gemini-3.5-flash-lite"
    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    static let log = Logger(subsystem: "do.float.ainderhybrid", category: "app")
}

@main
struct AinderHybridApp: App {
    @State private var model: AppModel

    init() {
        let firebaseReady = Self.initFirebase()
        _model = State(initialValue: AppModel(firebaseReady: firebaseReady))
    }

    var body: some Scene {
        WindowGroup { RootView().environment(model) }
    }

    /// Firebase is optional at build time: without GoogleService-Info.plist every AI step uses its
    /// non-AI fallback (human input or a code rule) and shows "No AI".
    private static func initFirebase() -> Bool {
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return false }
        #if DEBUG
            // Debug provider: its token is registered in the Firebase console, never in this repository.
            AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        #else
            AppCheck.setAppCheckProviderFactory(AttestProviderFactory())
        #endif
        FirebaseApp.configure()
        // Anonymous Auth only identifies the install for per-user AI Logic limits. No profile data.
        Auth.auth().signInAnonymously { _, error in
            if let error { AppConfig.log.warning("anonymous auth failed: \(String(describing: type(of: error)))") }
        }
        return true
    }
}
