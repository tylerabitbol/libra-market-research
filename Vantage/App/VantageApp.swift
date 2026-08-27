import SwiftUI
import SwiftData

@main
struct VantageApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .modelContainer(AppModelContainer.shared)
    }
}
