import SwiftUI
import UIKit
import OpenFuguShared

@main
struct OpenFuguApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.keyboard) // Compose has its own keyboard handling
        }
    }
}

/// Hosts the Compose Multiplatform UI; everything else lives in the shared module.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
