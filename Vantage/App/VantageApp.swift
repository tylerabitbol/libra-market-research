import SwiftUI
import SwiftData

@main
struct VantageApp: App {
    init() {
        // Verification hook, not a product feature: lets the real signed app be
        // checked from the command line, where the Keychain actually behaves
        // like it does in production.
        if SelfTest.isRequested {
            SelfTest.run(secrets: KeychainSecretsStore())
        }
        // Debug-only, and only when explicitly asked for by launch argument.
        DeveloperOptions.seedSecretsFromEnvironment(into: KeychainSecretsStore())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .modelContainer(AppModelContainer.shared)
    }
}
