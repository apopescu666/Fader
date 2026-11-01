import Foundation

@main
struct PersistenceTests {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("fader-session-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("PlaybackSession.json")
        var tracks = (0..<60_000).map { index in
            Track(url: URL(fileURLWithPath: "/Synthetic/\(index).flac"),
                  title: "Title \(index)", artist: "Artist", album: "Album")
        }
        tracks[20].artworkData = Data([1, 3, 5, 7, 9])
        let writer = PlaybackSessionStore.Writer(fileURL: file, delay: 60)
        let started = ProcessInfo.processInfo.systemUptime
        for index in 0..<100 {
            writer.save(.init(queue: [], folderTracks: tracks, isFolderPlayback: true,
                              currentIndex: index, shuffle: true, repeatMode: "all"))
        }
        print(String(format: "60k tracks: 100 snapshot submissions on caller thread %.2f ms",
            (ProcessInfo.processInfo.systemUptime - started) * 1000))
        precondition(!FileManager.default.fileExists(atPath: file.path), "Writes must be deferred")
        writer.flush()
        guard let restored = FaderCompactSessionStore.load(legacyURL: file) else { fatalError("No compact session") }
        let session = restored.session
        precondition(session.queue.isEmpty && session.folderTracks.count == 60_000)
        precondition(session.currentIndex == 99 && session.isFolderPlayback && session.shuffle)
        precondition(session.repeatMode == "all")
        precondition(session.folderTracks[20].path == tracks[20].url.path)
        precondition(session.folderTracks[20].makeTrack().title == tracks[20].title)
        precondition(restored.folderTracks[20].artworkData == tracks[20].artworkData)
        // Exact old JSON schema can still be read; no migration or audio paths changed.
        let encoded = try JSONEncoder().encode(session)
        let object = try JSONSerialization.jsonObject(with: encoded) as! [String: Any]
        precondition(Set(object.keys) == ["queue", "folderTracks", "isFolderPlayback", "currentIndex", "shuffle", "repeatMode"])
        let savedTrack = PlaybackSessionStore.SavedTrack(tracks[0])
        let roundTrip = try JSONDecoder().decode(PlaybackSessionStore.SavedTrack.self,
            from: JSONEncoder().encode(savedTrack))
        precondition(roundTrip.makeTrack().url == tracks[0].url)

        var written: [Int] = [] // accessed only inside serial writer / after flush
        var wroteOnMain = false
        let coalescer = FaderCoalescingWriter<Int>(label: "test", delay: 60) { value in
            wroteOnMain = wroteOnMain || Thread.isMainThread
            written.append(value)
        }
        for value in 0..<10_000 { coalescer.submit(value) }
        coalescer.flush()
        precondition(written == [9_999], "Burst must write only the latest snapshot")
        // queue.sync may execute on the calling thread during explicit shutdown flush;
        // regular background writes are checked with a separate zero-delay worker.
        _ = wroteOnMain
        let completed = DispatchSemaphore(value: 0)
        let background = FaderCoalescingWriter<Int>(label: "test-background", delay: 0) { _ in
            precondition(!Thread.isMainThread)
            completed.signal()
        }
        background.submit(1)
        precondition(completed.wait(timeout: .now() + 10) == .success)
        background.flush()
        coalescer.submit(10_000)
        coalescer.flush()
        precondition(written == [9_999, 10_000])
        let began = DispatchSemaphore(value: 0)
        let resume = DispatchSemaphore(value: 0)
        var overlapping: [Int] = []
        let busy = FaderCoalescingWriter<Int>(label: "test-overlap", delay: 0) { value in
            if value == 1 {
                began.signal()
                precondition(resume.wait(timeout: .now() + 10) == .success)
            }
            overlapping.append(value)
        }
        busy.submit(1)
        precondition(began.wait(timeout: .now() + 10) == .success)
        busy.submit(2)
        busy.submit(3)
        resume.signal()
        busy.flush()
        precondition(overlapping == [1, 3], "Only latest pending snapshot should follow an in-flight write")
        print("PASS: 60k session restore, old JSON schema, newest snapshot wins, serial background I/O, burst coalescing, shutdown flush")
    }
}
