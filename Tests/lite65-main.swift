import Foundation
import AVFoundation
import AppKit

@main struct LiteTests {
    static func check(_ value: @autoclosure () -> Bool, _ message: String) {
        precondition(value(), message)
    }
    static func pump(_ seconds: Double) {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005)) }
    }
    static func wait(_ condition: () -> Bool) {
        let end = Date().addingTimeInterval(20)
        while !condition() && Date() < end { pump(0.01) }
        check(condition(), "Timed out")
    }
    static func wave(_ url: URL) throws -> Track {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 441000)!
        buffer.frameLength = 441000
        memset(buffer.floatChannelData![0], 0, Int(buffer.frameLength) * MemoryLayout<Float>.size)
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        var track = Track(url: url, title: url.lastPathComponent, artist: "Synthetic silence", album: "Tests")
        track.duration = 10; track.format = "WAV"
        return track
    }
    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let a = try wave(root.appendingPathComponent("a.wav"))
        let b = try wave(root.appendingPathComponent("b.wav"))
        let c = try wave(root.appendingPathComponent("c.wav"))
        let session = root.appendingPathComponent("Session.json")
        let snapshot = PlaybackSessionStore.Snapshot(queue: [a,b], folderTracks: [], isFolderPlayback: false, currentIndex: 0, shuffle: false, repeatMode: "off")
        let original = try JSONEncoder().encode(snapshot.makeSession())
        try original.write(to: session)
        FaderStorage.defaults.set(4.0, forKey: "Fader.lastPlaybackPosition")
        let player = Player(sessionURL: session, deferSessionRestoration: true)
        player.volume = 0
        check(!player.isRestoringSession && player.queue.isEmpty, "Lite must not decode session at startup")
        player.openFinderTrack(c)
        player.seek(to: 2)
        pump(0.05)
        player.flushPendingSession()
        let preserved = try Data(contentsOf: session)
        check(preserved == original, "Closing Lite before restore preserves session")
        check(FaderStorage.defaults.double(forKey: "Fader.lastPlaybackPosition") == 4, "Lite must not overwrite normal saved position")
        player.cycleRepeat()
        let position = player.elapsed
        player.beginSessionRestoration()
        wait { !player.isRestoringSession }
        check(player.repeatMode == .all, "Repeat changed in Lite survives deferred restoration")
        check(player.queue.map(\.normalizedPath) == [a.normalizedPath,b.normalizedPath], "Full mode restores saved queue")
        check(player.isPlaying && player.currentTrack?.normalizedPath == c.normalizedPath && player.elapsed >= position, "Late restoration does not restart/replace audio")
        player.beginSessionRestoration()
        check(!player.isRestoringSession, "Restoration must be idempotent")
        FaderLiteImport.apply([a,c], choice: .cancel, player: player)
        check(player.queue.count == 2 && player.currentTrack?.normalizedPath == c.normalizedPath, "Cancel leaves queue/audio intact")
        FaderLiteImport.apply([b,c,c], choice: .append, player: player)
        check(player.queue.map(\.normalizedPath) == [a.normalizedPath,b.normalizedPath,c.normalizedPath], "Append preserves order and deduplicates")
        FaderLiteImport.apply([b,b], choice: .replace, player: player)
        check(player.queue.map(\.normalizedPath) == [b.normalizedPath], "Replacement only contains new unique files")
        check(player.isPlaying && player.currentTrack?.normalizedPath == c.normalizedPath, "Replacing queue preserves temporary audio")
        player.playQueueIndex(0)
        player.seek(to: 3)
        let before = player.elapsed
        FaderLiteImport.apply([a,c], choice: .replace, player: player)
        check(player.queue.map(\.normalizedPath) == [a.normalizedPath,c.normalizedPath], "Active-queue replacement content")
        check(player.isPlaying && player.currentTrack?.normalizedPath == b.normalizedPath && player.elapsed >= before, "Detach live queue snapshot without restarting audio")
        player.playFolder([a,b])
        player.seek(to: 2)
        FaderLiteImport.apply([c], choice: .replace, player: player)
        check(player.isPlaying && player.isFolderPlayback && player.currentTrack?.normalizedPath == a.normalizedPath, "Folder audio/source remains intact")
        let collected = FaderLiteImport.collect([c.url,a.url,c.url])
        check(collected.map(\.normalizedPath) == [c.normalizedPath,a.normalizedPath], "Import preserves provider order and deduplicates")
        player.stop(); player.flushPendingSession()
        let reopened = Player(sessionURL: session)
        wait { !reopened.isRestoringSession }
        check(reopened.queue.map(\.normalizedPath) == [c.normalizedPath], "Confirmed queue survives restart")
        reopened.stop(); reopened.flushPendingSession()
        print("PASS Lite: deferred restore, untouched saved session/position, no audio restart on expansion, append/replace/cancel, dedup/order, active queue/folder preservation, restart")
    }
}
