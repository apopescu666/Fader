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
        var decisions = 0
        presentation.queueDecision = { existing, new in
            precondition(existing == 1 && new == 1)
            decisions += 1
            return .cancel
        }
        presentation.showFull()
        wait { !player.isRestoringSession && !presentation.hasPendingImports }
        pump(0.1)
        precondition(decisions == 1 && player.queue.map(\.normalizedPath) == [a.standardizedFileURL.path])
        precondition(player.isFinderPlayback && player.isPlaying && player.elapsed >= elapsed)
        let main = presentation.mainWindow!
        precondition(main.isVisible && presentation.liteWindow?.isVisible == false)
        presentation.showLite()
        precondition(presentation.mainWindow === main && !main.isVisible)
        presentation.queueDecision = { existing, new in
            precondition(existing == 1 && new == 1)
            return .append
        }
        presentation.showFull()
        wait { !presentation.hasPendingImports }
        precondition(player.queue.map(\.normalizedPath) == [a.standardizedFileURL.path,b.standardizedFileURL.path])
        precondition(!player.isFinderPlayback && !player.isFolderPlayback && player.currentIndex == 1)
        precondition(player.isPlaying && player.currentTrack?.normalizedPath == b.standardizedFileURL.path && player.elapsed >= elapsed)
        // Drag two new providers plus a duplicate of the playing song. Current
        // must be included exactly once in the replacement and remain live.
        let c = URL(fileURLWithPath: CommandLine.arguments[4])
        presentation.showLite()
        presentation.queueDecision = { existing, new in
            precondition(existing == 2 && new == 3)
            return .replace
        }
        var delivered = false
        FaderLiteDropReader.read([NSItemProvider(contentsOf: c)!, NSItemProvider(contentsOf: a)!, NSItemProvider(contentsOf: b)!]) { urls in
            precondition(urls.map(\.standardizedFileURL) == [c.standardizedFileURL,a.standardizedFileURL,b.standardizedFileURL])
            delivered = true
            presentation.addFromLite(urls)
        }
        wait { delivered && !presentation.hasPendingImports }
        precondition(player.queue.map(\.normalizedPath) == [b.standardizedFileURL.path,c.standardizedFileURL.path,a.standardizedFileURL.path])
        precondition(player.currentIndex == 0 && !player.isFinderPlayback && player.isPlaying && player.elapsed >= elapsed)
        // The live song now advances inside Current Queue, not a one-song
        // temporary Finder source. Exercise the actual audio timer at EOF.
        player.crossfade = 0
        player.seek(to: 119.85)
        wait { player.currentTrack?.normalizedPath == c.standardizedFileURL.path }
        precondition(player.isPlaying && !player.isFinderPlayback)
        player.stop(); player.flushPendingSession()
        main.close(); presentation.liteWindow?.close()
        print("PASS Test66: cold Finder Lite; Open Fader cancel/append includes current; multi-provider replace includes current once; same audio position; EOF advances in Current Queue; light/dark unchanged")
    }
}
