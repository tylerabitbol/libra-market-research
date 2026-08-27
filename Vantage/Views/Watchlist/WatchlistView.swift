import SwiftUI

struct WatchlistView: View {
    var body: some View {
        ContentUnavailableView(
            "Watchlist",
            systemImage: "hammer",
            description: Text("Phase 1 scaffolding. Not yet implemented.")
        )
    }
}

#Preview {
    NavigationStack { WatchlistView() }
}
