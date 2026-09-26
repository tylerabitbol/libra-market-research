import SwiftUI
import LibraKit

/// The whole iOS app is one Compose view controller. Everything above this
/// line is Kotlin; this file exists only to give it a window.
struct ComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

@main
struct LibraApplication: App {
    var body: some Scene {
        WindowGroup {
            ComposeRoot()
                .ignoresSafeArea(.all)
        }
    }
}
