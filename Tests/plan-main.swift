import Foundation
import AVFoundation
import AppKit
import UniformTypeIdentifiers

@main
struct PlanTests {
    static func check(_ condition: @autoclosure () -> Bool, _ message: String, line: Int = #line) {
        guard condition() else {
            FileHandle.standardError.write(Data("FAIL line \(line): \(message)\n".utf8))
            exit(1)
        }
    }
    static func wait(_ message: String, timeout: TimeInterval = 30, until condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end {
            _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005))
        }
        check(condition(), "Timeout: " + message)
    }
    static func pump(_ duration: TimeInterval) {
        let end = Date().addingTimeInterval(duration)
        while Date() < end { _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.005)) }
    }
    static func timed<T>(_ name: String, _ work: () throws -> T) rethrows -> T {
        let started = ProcessInfo.processInfo.systemUptime
        let value = try work()
        print(String(format: "%@: %.2f ms", name, (ProcessInfo.processInfo.systemUptime - started) * 1000))
        return value
    }
    static func makeTracks(_ count: Int) -> [Track] {
        (0..<count).map { index in
            var t = Track(url: URL(fileURLWithPath: "/Synthetic/Music/\(index).flac"),
                title: index % 13 == 0 ? "ABBA Dancing \(index)" : "Title \(index)",
                artist: index % 29 == 0 ? "Ștefan Élan" : "Artist \(index % 200)", album: "Album \(index % 500)")
            t.duration = 180; t.format = "FLAC"
            if index % 100 == 0 { t.artworkData = Data(repeating: 7, count: 4096) }
            return t
        }
    }
    static func restored(_ source: URL) -> PlaybackSessionStore.RestoredSession? {
        var done = false
        var value: PlaybackSessionStore.RestoredSession?
        PlaybackSessionStore.loadInBackground(from: source) { value = $0; done = true }
        check(!done, "Restoration must return asynchronously")
        wait("session restore") { done }
        return value
    }
    static func sessions(_ root: URL, tracks: [Track]) throws {
        let legacy = root.appendingPathComponent("PlaybackSession.json")
        let old = PlaybackSessionStore.Snapshot(queue: [tracks[0]], folderTracks: [],
            isFolderPlayback: false, currentIndex: 0, shuffle: false, repeatMode: "off").makeSession()
        let oldBytes = try JSONEncoder().encode(old)
        try oldBytes.write(to: legacy)
        let snapshot = PlaybackSessionStore.Snapshot(queue: [tracks[0], tracks[0]], folderTracks: tracks,
            isFolderPlayback: true, currentIndex: 4321, shuffle: true, repeatMode: "all")
        let writer = PlaybackSessionStore.Writer(fileURL: legacy, delay: 60)
        timed("60k session: snapshot submit") { writer.save(snapshot) }
        timed("60k session: first catalog write + flush") { writer.flush() }
        check(tryRead(legacy) == oldBytes, "Legacy file must remain byte-identical")
        let directory = FaderCompactSessionStore.directory(for: legacy)
        let manifest = directory.appendingPathComponent("session.json")
        let manifestSize = try Data(contentsOf: manifest).count
        check(manifestSize < 1024, "Session manifest should remain small")
        let before = try JSONDecoder().decode(FaderCompactSessionStore.Manifest.self, from: Data(contentsOf: manifest))
        let catalog = directory.appendingPathComponent(before.catalog + ".json")
        let catalogDate = try catalog.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
        let ready = timed("60k session: async compact restore") { restored(legacy) }
        check(ready?.queue.count == 2 && ready?.folderTracks.count == 60_000, "Order/duplicates/length restored")
        check(ready?.session.currentIndex == 4321 && ready?.session.shuffle == true, "Playback state restored")
        check(ready?.folderTracks[500].artworkData == tracks[500].artworkData, "Lazy artwork bytes restored")
        check(ready?.folderTracks[4321].normalizedPath == tracks[4321].normalizedPath, "Track identity/path unchanged")
        let blobs = try FileManager.default.contentsOfDirectory(at: directory.appendingPathComponent("artwork"), includingPropertiesForKeys: nil)
        check(blobs.count == 1, "Identical covers must share one blob")
        var later = snapshot
        later = .init(queue: snapshot.queue, folderTracks: snapshot.folderTracks,
                      isFolderPlayback: true, currentIndex: 5000, shuffle: false, repeatMode: "one")
        writer.save(later); writer.flush()
        check(tryRead(legacy) == oldBytes, "Old session never overwritten")
        let after = try JSONDecoder().decode(FaderCompactSessionStore.Manifest.self, from: Data(contentsOf: manifest))
        check(after.catalog == before.catalog && after.currentIndex == 5000, "Track changes only update manifest")
        check(tryDate(catalog) == catalogDate, "Metadata catalog must not be rewritten on a track change")
        let catalogBytes = try Data(contentsOf: catalog)
        try Data("corrupt".utf8).write(to: catalog)
        check(restored(legacy)?.queue.count == 1, "Corrupt compact catalog falls back to intact legacy")
        try catalogBytes.write(to: catalog)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(5)], ofItemAtPath: legacy.path)
        check(restored(legacy)?.queue.count == 1, "Newer legacy state from older app wins after rollback")
        print("PASS session: \(manifestSize)-byte manifest, 60k metadata, shared/lazy covers, duplicate order, catalog reuse, corruption/rollback fallback")
    }
    static func tryRead(_ url: URL) -> Data? { try? Data(contentsOf: url) }
    static func tryDate(_ url: URL) -> Date? { try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }

    static func expected(_ tracks: [Track], query: String) -> Set<String> {
        let locale = Locale(identifier: "en_US_POSIX")
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
        guard !q.isEmpty else { return [] }
        let unique = Dictionary(tracks.map { ($0.normalizedPath, $0) }, uniquingKeysWith: { _, last in last })
        return Set(unique.compactMap { path, t in
            [t.title, t.artist, t.album].contains { $0.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale).contains(q) } ? path : nil
        })
    }
    static func searches(_ root: URL, tracks: [Track]) throws {
        let preserved = FaderSearchSources.collect(folders: [], queue: [tracks[0], tracks[1]],
            activePlaylistPaths: [tracks[0].normalizedPath], metadata: [:])
        let afterClear = FaderSearchSources.collect(folders: [], queue: [],
            activePlaylistPaths: [tracks[0].normalizedPath], metadata: preserved.metadata)
        check(afterClear.tracks.map(\.normalizedPath) == [tracks[0].normalizedPath], "Playlist-only song remains searchable after clearing queue")
        let removedPlaylist = FaderSearchSources.collect(folders: [], queue: [], activePlaylistPaths: [], metadata: preserved.metadata)
        check(removedPlaylist.tracks.isEmpty, "Stale cached metadata is not library membership")
        let folderMember = FaderSearchSources.collect(folders: [tracks[2]], queue: [], activePlaylistPaths: [tracks[2].normalizedPath], metadata: [:])
        let afterFolderRemoval = FaderSearchSources.collect(folders: [], queue: [], activePlaylistPaths: [tracks[2].normalizedPath], metadata: folderMember.metadata)
        check(afterFolderRemoval.tracks.count == 1, "Removing a folder preserves playlist membership")
        let dir = root.appendingPathComponent("Search")
        let db = try timed("60k search: index preparation/validation once") {
            try SQLiteSearchService.prepareUnifiedIndex(tracks: tracks, in: dir)
        }
        for q in ["a", "ab", "abba", "Ș", "stefan", "é", "%", "_", "\\", "😃", "Artist 12", "  title 1 ", "", "never-match-zz"] {
            let hits = try SQLiteSearchService.matchingOptimizedPathsForShadow(databaseURL: db, text: q)
            check(hits == expected(tracks, query: q), "Exact search parity for \(q.debugDescription)")
        }
        let old = try timed("60k 'a': previous full validation + query") {
            check(SQLiteSearchService.isUnifiedIndexCurrent(databaseURL: db, tracks: tracks), "Baseline index current")
            return try SQLiteSearchService.matchingOptimizedPathsForShadow(databaseURL: db, text: "a")
        }
        let new = try timed("60k 'a': validated-catalog query only") {
            try SQLiteSearchService.matchingOptimizedPathsForShadow(databaseURL: db, text: "a")
        }
        check(old == new, "Optimized search must not change matches")
        let cancelled = FaderSearchCancellation(); cancelled.cancel()
        do {
            _ = try SQLiteSearchService.matchingOptimizedPathsForShadow(databaseURL: db, text: "a", cancellation: cancelled)
            check(false, "Cancelled search must stop")
        } catch { check(cancelled.isCancelled, "Cancellation signal") }
        let model = FaderGlobalSearchModel(directory: dir, debounce: 0.01)
        model.updateSources(tracks)
        wait("model indexing", timeout: 60) { !model.isIndexing }
        check(model.error == nil && model.total == 60_000, "Validated catalog published")
        model.search("a"); model.search("ab"); model.search("abba")
        wait("latest query") { model.result?.query == "abba" && !model.isSearching }
        check(model.result?.count == expected(tracks, query: "abba").count, "Latest query result exact")
        model.search("a")
        wait("one letter query") { model.result?.query == "a" && !model.isSearching }
        check(model.result?.count == new.count && model.result?.shown.count == 200, "One-letter total and first page retained")
        model.search("")
        check(model.result == nil && !model.isSearching, "Reset clears results")
        let reduced = Array(tracks.prefix(59_000))
        model.search("a"); model.updateSources(reduced); model.search("abba")
        wait("new catalog", timeout: 60) { !model.isIndexing && model.result?.query == "abba" && !model.isSearching }
        check(model.total == 59_000 && model.result?.count == expected(reduced, query: "abba").count, "Removed membership never leaks from stale query")
        print("PASS search: 60k exact Unicode/literal/one-letter parity, cancellation, rapid typing, reset, 200-row page/count, source-generation changes")
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
    static func finderAndAudio(_ root: URL) throws {
        let a = try wave(root.appendingPathComponent("a.wav"))
        let b = try wave(root.appendingPathComponent("b.wav"))
        let c = try wave(root.appendingPathComponent("c.wav"))
        check(FaderFinderOpenPolicy.decide(path: a.normalizedPath, activePaths: [a.normalizedPath, b.normalizedPath]) == .active(0), "Active entry reuse")
        check(FaderFinderOpenPolicy.decide(path: c.normalizedPath, activePaths: [a.normalizedPath]) == .temporary, "Other lists must not be chosen arbitrarily")
        var queued: [[URL]] = []
        let router = FaderFileOpenRouter()
        router.receive([a.url]); router.receive([b.url])
        router.install { queued.append($0) }
        check(queued == [[a.url], [b.url]], "Early Finder events buffered in order")
        router.receive([c.url])
        check(queued.last == [c.url], "Warm Finder events forwarded")
        let legacy = root.appendingPathComponent("PlayerSession.json")
        let old = PlaybackSessionStore.Snapshot(queue: [c], folderTracks: [a, b], isFolderPlayback: true,
                                               currentIndex: 1, shuffle: false, repeatMode: "off").makeSession()
        try JSONEncoder().encode(old).write(to: legacy)
        let bytes = try Data(contentsOf: legacy)
        let player = Player(sessionURL: legacy)
        player.volume = 0
        check(player.isRestoringSession, "Cold restore is asynchronous")
        player.openFinderTrack(c)
        player.toggleShuffle()
        check(player.isFinderPlayback && player.currentTrack?.normalizedPath == c.normalizedPath, "Finder starts before session restore")
        wait("player restore") { !player.isRestoringSession }
        check(player.isFinderPlayback && player.currentTrack?.normalizedPath == c.normalizedPath, "Late restore must not replace Finder track")
        check(player.shuffle, "Late restoration preserves newer transport choices")
        check(player.queue.count == 1 && player.folderPlaybackTracks.count == 2, "Temporary playback does not alter saved lists")
        player.remove(atOffsets: IndexSet(integer: 0))
        check(player.isFinderPlayback && player.currentTrack?.normalizedPath == c.normalizedPath, "Editing base queue must not interrupt Finder audio")
        player.playFolder([a, b])
        player.openFinderTrack(b)
        check(!player.isFinderPlayback && player.currentIndex == 1 && player.folderPlaybackTracks.count == 2, "Existing active-list entry reused")
        player.playFolder([a, b])
        player.crossfade = 0.15
        player.seek(to: 9.9)
        wait("automatic crossfade", timeout: 3) { player.currentIndex == 1 }
        wait("crossfade ramp", timeout: 3) { !player.isCrossfading }
        check(player.isPlaying, "Playback continues after crossfade")
        player.seek(to: 1.25)
        check(abs(player.elapsed - 1.25) < 0.1, "Forward/rewind seek retained")
        player.openFinderFiles([c.url])
        player.playFolder([a, b])
        pump(0.3)
        check(!player.isFinderPlayback && player.currentTrack?.normalizedPath == a.normalizedPath, "Late file load must not replace a newer user action")
        player.openFinderFiles([c.url])
        wait("Finder header-first playback") { player.isFinderPlayback && player.currentTrack?.normalizedPath == c.normalizedPath }
        check(player.isPlaying && player.folderPlaybackTracks.count == 2, "Finder file plays without replacing base list")
        player.stop(); player.flushPendingSession()
        check(tryRead(legacy) == bytes, "Player integration preserves original legacy backup")
        player.playFolder([a, b]); player.stop(); player.flushPendingSession()
        let cold = Player(sessionURL: legacy)
        cold.volume = 0
        cold.openFinderTrack(a)
        wait("cold attachment") { !cold.isRestoringSession }
        check(!cold.isFinderPlayback && cold.currentTrack?.normalizedPath == a.normalizedPath && cold.isPlaying,
              "Cold Finder track attaches to restored active list without restarting")
        cold.stop(); cold.flushPendingSession()
        print("PASS Finder/audio: buffered cold/warm requests, active-list reuse, temporary playback, late-restore priority, queue edits, seek, automatic crossfade on silent WAVs")
    }
    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let audioRoot = root.appendingPathComponent("Audio")
        try FileManager.default.createDirectory(at: audioRoot, withIntermediateDirectories: true)
        try finderAndAudio(audioRoot)
        let tracks = makeTracks(60_000)
        try sessions(root, tracks: tracks)
        try searches(root, tracks: tracks)
        print("ALL PLAN TESTS PASS — synthetic files only, no installed app/user profile writes")
    }
}
