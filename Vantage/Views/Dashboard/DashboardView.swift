import SwiftUI

struct DashboardView: View {
    var body: some View {
        ContentUnavailableView(
            "Dashboard",
            systemImage: "hammer",
            description: Text("Phase 1 scaffolding. Not yet implemented.")
        )
    }
}

#Preview {
    NavigationStack { DashboardView() }
}
