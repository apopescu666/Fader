import Foundation

enum PlaybackSessionStore {

    struct SavedTrack: Codable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let artworkData: Data?

        init(_ track: Track) {
            path = track.url.path
            title = track.title
            artist = track.artist
            album = track.album
            duration = track.duration
            format = track.format
            artworkData = track.artworkData
        }

        init(metadata track: Track) {
            path = track.url.path; title = track.title; artist = track.artist
            album = track.album; duration = track.duration; format = track.format
            artworkData = nil
        }

        func makeTrack() -> Track {
            var track = Track(
                url: URL(fileURLWithPath: path, isDirectory: false),
                title: title,
                artist: artist,
                album: album
            )

            track.duration = duration
            track.format = format
            track.artworkData = artworkData

            return track
        }
    }

    struct Session: Codable {
        let queue: [SavedTrack]
        let folderTracks: [SavedTrack]
        let isFolderPlayback: Bool
        let currentIndex: Int
        let shuffle: Bool
        let repeatMode: String
    }

    private static var fileURL: URL {
        FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
        .appendingPathComponent(FaderStorage.supportComponent, isDirectory: true)
        .appendingPathComponent("PlaybackSession.json")
    }

    /// Capturing these value arrays is cheap (copy-on-write). Resolving artwork,
    /// encoding JSON and atomic disk I/O all happen on the writer's utility queue.
    struct Snapshot {
        let queue: [Track]
        let folderTracks: [Track]
        let isFolderPlayback: Bool
        let currentIndex: Int
        let shuffle: Bool
        let repeatMode: String

        func makeSession() -> Session {
            Session(queue: queue.map { SavedTrack($0) },
                    folderTracks: folderTracks.map { SavedTrack($0) },
                    isFolderPlayback: isFolderPlayback, currentIndex: currentIndex,
                    shuffle: shuffle, repeatMode: repeatMode)
        }
    }

    // Injectable output URL lets regression tests use only a temporary directory.
    final class Writer {
        private let worker: FaderCoalescingWriter<Snapshot>
        init(fileURL: URL, delay: TimeInterval = 0.15) {
            let catalog = FaderCompactSessionStore.CatalogWriter(legacyURL: fileURL)
            worker = FaderCoalescingWriter(label: "com.fader.playback-session-io", delay: delay) { snapshot in
                do { try catalog.save(snapshot) }
                catch { print("FADER SESSION: save failed: \(error)") }
            }
        }
        func save(_ snapshot: Snapshot) { worker.submit(snapshot) }
        func flush() { worker.flush() }
    }

    private static let writer = Writer(fileURL: fileURL)
    static func save(_ snapshot: Snapshot) { writer.save(snapshot) }
    static func flush() { writer.flush() }

    struct RestoredSession {
        let session: Session
        let queue: [Track]
        let folderTracks: [Track]
    }

    /// Decode the potentially large legacy JSON AND build Track values off main.
    /// Only applying the ready arrays is dispatched to the UI thread.
    static func loadInBackground(from url: URL? = nil,
                                 completion: @escaping (RestoredSession?) -> Void) {
        let source = url ?? fileURL
        DispatchQueue.global(qos: .utility).async {
            let restored: RestoredSession? = autoreleasepool {
                if let compact = FaderCompactSessionStore.load(legacyURL: source) { return compact }
                guard let session = load(from: source) else { return nil }
                return RestoredSession(session: session,
                    queue: session.queue.map { $0.makeTrack() },
                    folderTracks: session.folderTracks.map { $0.makeTrack() })
            }
            DispatchQueue.main.async { completion(restored) }
        }
    }

    static func load(from url: URL? = nil) -> Session? {
        guard let data = try? Data(contentsOf: url ?? fileURL) else { return nil }
        return try? JSONDecoder().decode(Session.self, from: data)
    }
}
