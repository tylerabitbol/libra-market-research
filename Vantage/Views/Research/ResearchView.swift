import SwiftUI

struct ResearchView: View {
    var body: some View {
        ContentUnavailableView(
            "Research",
            systemImage: "hammer",
            description: Text("Phase 1 scaffolding. Not yet implemented.")
        )
    }
}

#Preview {
    NavigationStack { ResearchView() }
}
