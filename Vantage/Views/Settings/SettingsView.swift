import SwiftUI

struct SettingsView: View {
    var body: some View {
        ContentUnavailableView(
            "Settings",
            systemImage: "hammer",
            description: Text("Phase 1 scaffolding. Not yet implemented.")
        )
    }
}

#Preview {
    NavigationStack { SettingsView() }
}
