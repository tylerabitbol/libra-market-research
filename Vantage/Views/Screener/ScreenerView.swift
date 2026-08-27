import SwiftUI

struct ScreenerView: View {
    var body: some View {
        ContentUnavailableView(
            "Screener",
            systemImage: "hammer",
            description: Text("Phase 1 scaffolding. Not yet implemented.")
        )
    }
}

#Preview {
    NavigationStack { ScreenerView() }
}
