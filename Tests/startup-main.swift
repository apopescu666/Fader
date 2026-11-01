import Foundation

@main
struct StartupTests {
    static func main() throws {
        precondition(Thread.isMainThread)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("fader-startup-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("session.json")
        var tracks = (0..<60_000).map { index in
            Track(url: URL(fileURLWithPath: "/UnavailableNAS/Music/\(index).flac"),
                  title: "Song \(index)", artist: "Artist \(index % 100)", album: "Album")
        }
        tracks[42].artworkData = Data([1, 2, 3, 4])
        let session = PlaybackSessionStore.Snapshot(queue: [tracks[0]], folderTracks: tracks,
            isFolderPlayback: true, currentIndex: 42, shuffle: true, repeatMode: "all").makeSession()
        try JSONEncoder().encode(session).write(to: file)
        var done = false
        var restored: PlaybackSessionStore.RestoredSession?
        var heartbeats = 0
        let timer = Timer.scheduledTimer(withTimeInterval: 0.001, repeats: true) { _ in heartbeats += 1 }
        let started = ProcessInfo.processInfo.systemUptime
        PlaybackSessionStore.loadInBackground(from: file) { value in
            precondition(Thread.isMainThread)
            restored = value
            done = true
        }
        let returned = ProcessInfo.processInfo.systemUptime - started
        precondition(!done, "Restore must not block initializer/caller")
        let deadline = Date().addingTimeInterval(30)
        while !done && Date() < deadline {
            _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
        timer.invalidate()
        precondition(done && heartbeats > 0, "Main run loop must stay responsive during restore")
        precondition(restored?.queue.count == 1 && restored?.folderTracks.count == 60_000)
        precondition(restored?.session.currentIndex == 42 && restored?.session.shuffle == true)
        precondition(restored?.folderTracks[42].artworkData == Data([1, 2, 3, 4]))
        precondition(restored?.folderTracks[42].normalizedPath == tracks[42].normalizedPath)
        print(String(format: "60k session: loader call returned %.2fms, restoration %.2fms, main-loop heartbeats %d",
            returned * 1000, (ProcessInfo.processInfo.systemUptime - started) * 1000, heartbeats))
        for name in ["missing.json", "corrupt.json"] {
            let invalid = directory.appendingPathComponent(name)
            if name == "corrupt.json" { try Data("invalid".utf8).write(to: invalid) }
            done = false
            PlaybackSessionStore.loadInBackground(from: invalid) { value in
                precondition(Thread.isMainThread && value == nil)
                done = true
            }
            while !done && Date() < deadline {
                _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01))
            }
            precondition(done)
        }
        print("PASS: async startup, responsive main loop, original 60k queue/source/index/artwork, missing/corrupt session, no NAS/audio access")
    }
}
