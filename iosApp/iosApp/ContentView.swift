import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            externalPluginsEnabled: !BuildChannel.isAppStore
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private enum BuildChannel {
#if KELMA_APP_STORE
    static let isAppStore = true
#else
    static let isAppStore = false
#endif
}
