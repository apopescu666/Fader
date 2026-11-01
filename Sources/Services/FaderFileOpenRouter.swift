import AppKit

/// The app delegate may receive Finder URLs before SwiftUI installs its views.
final class FaderFileOpenRouter {
    static let shared = FaderFileOpenRouter()
    private var pending: [[URL]] = []
    private var handler: (([URL]) -> Void)?
    func receive(_ urls: [URL]) {
        precondition(Thread.isMainThread)
        if let handler { handler(urls) } else { pending.append(urls) }
    }
    func install(_ handler: @escaping ([URL]) -> Void) {
        self.handler = handler
        let saved = pending; pending.removeAll()
        saved.forEach(handler)
    }
}

final class FaderFileOpenDelegate: NSObject, NSApplicationDelegate {
    func application(_ sender: NSApplication, openFile filename: String) -> Bool {
        FaderFileOpenRouter.shared.receive([URL(fileURLWithPath: filename, isDirectory: false)])
        return true
    }
    func application(_ application: NSApplication, open urls: [URL]) {
        FaderFileOpenRouter.shared.receive(urls)
    }
    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        FaderFileOpenRouter.shared.receive(filenames.map { URL(fileURLWithPath: $0, isDirectory: false) })
        sender.reply(toOpenOrPrint: .success)
    }
}
