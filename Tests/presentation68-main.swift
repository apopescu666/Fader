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
    @MainActor static func main() throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let a = URL(fileURLWithPath: CommandLine.arguments[2])
        let b = URL(fileURLWithPath: CommandLine.arguments[3])
        let c = URL(fileURLWithPath: CommandLine.arguments[4])
        func make(_ name: String) throws -> (Player, FaderPresentationController) {
            let session = root.appendingPathComponent(name + ".json")
            let first = Library.makeTrack(url: a)
            let snapshot = PlaybackSessionStore.Snapshot(queue: [first], folderTracks: [], isFolderPlayback: false, currentIndex: 0, shuffle: false, repeatMode: "off")
            try JSONEncoder().encode(snapshot.makeSession()).write(to: session)
            FaderStorage.defaults.set(12.5, forKey: "Fader.lastPlaybackPosition")
            FaderStorage.defaults.set("Current Queue", forKey: "Fader.playingSourceName")
            let player = Player(sessionURL: session, deferSessionRestoration: true)
            player.volume = 0
            return (player, FaderPresentationController(player: player))
        }
        // Open Fader -> one Cancel, unchanged saved queue, Lite audio continues.
        do {
            let (player, ui) = try make("cancel")
            ui.openFinder([b])
            wait { player.isPlaying && player.currentTrack?.normalizedPath == b.path }
            precondition(ui.isLite && ui.mainWindow == nil)
            player.seek(to: 37.25)
            let before = player.elapsed
            var confirmations = 0
            ui.queueDecision = { existing, count in
                precondition(existing == 1 && count == 1)
                confirmations += 1; return .cancel
            }
            ui.showFull()
            wait { !ui.hasPendingImports && !player.isRestoringSession }
            precondition(confirmations == 1 && ui.mainWindow?.isVisible == true && !ui.isLite)
            precondition(player.queue.map(\.normalizedPath) == [a.path])
            precondition(player.isPlaying && player.currentTrack?.normalizedPath == b.path && player.elapsed >= before)
            precondition(FaderStorage.defaults.double(forKey: "Fader.lastPlaybackPosition") == 12.5)
            player.stop(); player.flushPendingSession(); ui.mainWindow?.close(); ui.liteWindow?.close()
        }
        // Second Finder file before first header ready -> one batch, both songs.
        do {
            let (player, ui) = try make("burst")
            var confirmations = 0
            ui.queueDecision = { existing, count in
                precondition(existing == 1 && count == 2)
                confirmations += 1; return .replace
            }
            ui.openFinder([b]); ui.openFinder([c]); ui.showFull()
            wait { !ui.hasPendingImports && player.isPlaying }
            precondition(confirmations == 1)
            precondition(player.queue.map(\.normalizedPath) == [b.path,c.path])
            precondition(!player.isFinderPlayback && !player.isFolderPlayback && player.currentTrack?.normalizedPath == b.path)
            precondition(FaderStorage.defaults.string(forKey: "Fader.playingSourceName") == "Current Queue")
            ui.openFinder([b,c]); pump(0.3)
            precondition(confirmations == 1 && !ui.hasPendingImports)
            player.crossfade = 0; player.seek(to: 119.85)
            wait { player.currentTrack?.normalizedPath == c.path }
            precondition(player.isPlaying)
            player.stop(); player.flushPendingSession(); ui.mainWindow?.close(); ui.liteWindow?.close()
        }
        // Real sheet: more callbacks while it is visible update the SAME sheet.
        do {
            let (player, ui) = try make("sheet")
            ui.openFinder([b]); wait { player.isPlaying }
            player.seek(to: 31)
            ui.showFull()
            wait { ui.transferAlertForTesting != nil }
            let alert = ui.transferAlertForTesting!
            let sheet = ui.mainWindow!.attachedSheet
            precondition(sheet != nil)
            ui.addFromLite([c,a,c])
            wait { alert.buttons[0].isEnabled }
            precondition(ui.transferAlertForTesting === alert && ui.mainWindow!.attachedSheet === sheet)
            alert.buttons[0].performClick(nil)
            wait { !ui.hasPendingImports }
            precondition(player.queue.map(\.normalizedPath) == [a.path,b.path,c.path])
            precondition(player.isPlaying && player.elapsed >= 31 && !player.isFinderPlayback)
            precondition(FaderStorage.defaults.string(forKey: "Fader.playingSourceName") == "Current Queue")
            ui.addFromLite([c,a]); pump(0.35)
            precondition(ui.transferAlertForTesting == nil && !ui.hasPendingImports)
            player.stop(); player.flushPendingSession(); ui.mainWindow?.close(); ui.liteWindow?.close()
        }
        // A direct multi-selection in Finder stays temporary, with no queue dialog.
        do {
            let (player, ui) = try make("directFinder")
            var confirmations = 0
            ui.queueDecision = { _, _ in confirmations += 1; return .replace }
            ui.openFinder([c,b])
            wait { player.isPlaying && player.finderPlaybackTracks.count == 2 && !player.isRestoringSession }
            precondition(!ui.hasPendingImports && confirmations == 0)
            precondition(player.queue.map(\.normalizedPath) == [a.path])
            precondition(player.isFinderPlayback && player.finderPlaybackTracks.map(\.normalizedPath) == [c.path,b.path])
            precondition(player.currentTrack?.normalizedPath == c.path && ui.mainWindow?.isVisible == true)
            player.stop(); player.flushPendingSession(); ui.mainWindow?.close()
        }
        // Empty queue: Open Fader transfers the Lite song directly, without a dialog.
        do {
            let session = root.appendingPathComponent("empty.json")
            let snapshot = PlaybackSessionStore.Snapshot(queue: [], folderTracks: [], isFolderPlayback: false, currentIndex: -1, shuffle: false, repeatMode: "off")
            try JSONEncoder().encode(snapshot.makeSession()).write(to: session)
            let player = Player(sessionURL: session, deferSessionRestoration: true)
            player.volume = 0
            let ui = FaderPresentationController(player: player)
            ui.openFinder([b]); wait { player.isPlaying }
            player.seek(to: 24)
            ui.queueDecision = { _, _ in preconditionFailure("Empty queue must not prompt") }
            ui.showFull()
            wait { !ui.hasPendingImports && !player.isRestoringSession }
            precondition(player.queue.map(\.normalizedPath) == [b.path])
            precondition(!player.isFinderPlayback && player.isPlaying && player.elapsed >= 24)
            precondition(FaderStorage.defaults.string(forKey: "Fader.playingSourceName") == "Current Queue")
            player.stop(); player.flushPendingSession(); ui.mainWindow?.close(); ui.liteWindow?.close()
        }
        print("PASS Test68: Lite Open Fader and drops include playing song in Queue, preserve audio position, use one dialog; Cancel keeps saved queue and live song; direct Finder multi-selection stays temporary without queue mutation")
    }
}
