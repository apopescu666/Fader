import SwiftUI
import AppKit

@main struct PresentationTests {
    @MainActor static func pump(_ seconds: Double) {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005)) }
    }
    @MainActor static func wait(_ condition: () -> Bool) {
        let end = Date().addingTimeInterval(20)
        while !condition() && Date() < end { pump(0.01) }
        precondition(condition(), "Timed out")
    }
    @MainActor static func image(_ window: NSWindow, _ url: URL) throws {
        guard let view = window.contentView else { fatalError() }
        view.layoutSubtreeIfNeeded()
        guard let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { fatalError() }
        view.cacheDisplay(in: view.bounds, to: bitmap)
        guard let data = bitmap.representation(using: .png, properties: [:]) else { fatalError() }
        try data.write(to: url)
    }
    @MainActor static func main() throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let a = URL(fileURLWithPath: CommandLine.arguments[2])
        let b = URL(fileURLWithPath: CommandLine.arguments[3])
        let session = root.appendingPathComponent("Session.json")
        let first = Library.makeTrack(url: a)
        let snapshot = PlaybackSessionStore.Snapshot(queue: [first], folderTracks: [], isFolderPlayback: false, currentIndex: 0, shuffle: false, repeatMode: "off")
        try JSONEncoder().encode(snapshot.makeSession()).write(to: session)
        let player = Player(sessionURL: session, deferSessionRestoration: true)
        player.volume = 0
        let presentation = FaderPresentationController(player: player)
        FaderFileOpenRouter.shared.receive([b])
        wait { player.isPlaying && player.currentTrack?.normalizedPath == b.standardizedFileURL.path }
        precondition(presentation.isLite && presentation.mainWindow == nil && player.queue.isEmpty)
        precondition(presentation.liteWindow?.isVisible == true)
        let elapsed = player.elapsed
        FaderStorage.defaults.set("light", forKey: "Fader.appearance.mode")
        pump(0.2)
        try image(presentation.liteWindow!, root.appendingPathComponent("lite-light.png"))
        FaderStorage.defaults.set("dark", forKey: "Fader.appearance.mode")
        pump(0.2)
        try image(presentation.liteWindow!, root.appendingPathComponent("lite-dark.png"))
        presentation.showFull()
        wait { !player.isRestoringSession }
        pump(0.3)
        precondition(presentation.mainWindow?.isVisible == true && presentation.liteWindow?.isVisible == false)
        precondition(player.currentTrack?.normalizedPath == b.standardizedFileURL.path && player.isPlaying && player.elapsed >= elapsed)
        let main = presentation.mainWindow!
        presentation.showLite()
        precondition(presentation.mainWindow === main && !main.isVisible)
        var decisions = 0
        presentation.queueDecision = { existing, new in
            precondition(existing == 1 && new == 1)
            decisions += 1
            return .cancel
        }
        var delivered = false
        FaderLiteDropReader.read([NSItemProvider(contentsOf: b)!]) { urls in
            precondition(urls.map(\.standardizedFileURL) == [b.standardizedFileURL])
            delivered = true
            presentation.addFromLite(urls)
        }
        wait { delivered && !presentation.hasPendingImports }
        precondition(decisions == 1 && player.queue.count == 1 && !presentation.isLite)
        presentation.queueDecision = { _,_ in .append }
        presentation.addFromLite([b]); wait { !presentation.hasPendingImports }
        precondition(player.queue.map(\.normalizedPath) == [a.standardizedFileURL.path,b.standardizedFileURL.path])
        presentation.queueDecision = { _,_ in .replace }
        presentation.addFromLite([b]); wait { !presentation.hasPendingImports }
        precondition(player.queue.map(\.normalizedPath) == [b.standardizedFileURL.path])
        precondition(player.currentTrack?.normalizedPath == b.standardizedFileURL.path && player.isPlaying)
        player.stop(); player.flushPendingSession()
        main.close(); presentation.liteWindow?.close()
        print("PASS native presentation: cold Finder single creates only Lite; full expansion preserves player; main window reused; native NSItemProvider drop delivers ordered file URL; confirmation waits for saved queue; cancel/append/replace; light/dark renders captured")
    }
}
