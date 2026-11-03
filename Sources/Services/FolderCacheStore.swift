import Foundation
import CryptoKit

enum FolderCacheStore {

    private struct CachedTrack: Codable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let artworkData: Data

        // P2.9.4.4a: optional fields for future Smart Refresh.
        // Old JSON caches decode with nil values.
        let fileSize: Int64?
        let modificationDate: Date?

        init(_ track: Track) {
            path = track.url.path
            title = track.title
            artist = track.artist
            album = track.album
            duration = track.duration
            format = track.format
            artworkData = track.artworkData ?? Data()

            // P2.9.4.4b: capture file attributes for Smart Refresh.
            let values = try? track.url.resourceValues(
                forKeys: [.fileSizeKey, .contentModificationDateKey]
            )
            fileSize = values?.fileSize.map { Int64($0) }
            modificationDate = values?.contentModificationDate
        }

        func makeTrack() -> Track {
            var track = Track(
                url: URL(fileURLWithPath: path),
                title: title,
                artist: artist,
                album: album
            )
            track.duration = duration
            track.format = format
            track.artworkData = artworkData.isEmpty ? nil : artworkData
            return track
        }
    }

    private struct CacheRecord: Codable {
        let folderPath: String
        let tracks: [CachedTrack]
    }

    private static var directory: URL {
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]

        return support
            .appendingPathComponent(FaderStorage.supportComponent, isDirectory: true)
            .appendingPathComponent("FolderCache", isDirectory: true)
    }

    private static func cacheURL(for folder: URL) -> URL {
        let key = folder.standardizedFileURL.path
        let digest = SHA256.hash(data: Data(key.utf8))
        let name = digest.map {
            String(format: "%02x", $0)
        }.joined()

        return directory.appendingPathComponent(name + ".json")
    }


    // MARK: - FADER_SERIAL_CACHE_IO_V1

    // All cache disk operations use one serial background queue.
    // This preserves request order across save, load and remove.
    private static let ioQueue = DispatchQueue(
        label: "com.fader.folder-cache-io",
        qos: .utility
    )

    // FADER_P29133_BOUNDED_TRACK_CACHE
    // Access ONLY from ioQueue. Reuses decoded Track arrays requested by
    // playlist preload and folder navigation during the same app session.
    // Does not look at original audio files or bypass persistent cache changes.
    private static var hotTracks: [String: [Track]] = [:]
    private static var hotCosts: [String: Int] = [:]
    private static var hotLRU: [String] = []
    private static let hotMaxEntries = 3
    private static let hotMaxBytes = 128 * 1024 * 1024 // approximate JSON bytes

    // FADER_P29134A_SELECTED_FOLDER_PRIORITY
    // Access exclusively from ioQueue; never pin entries over the memory limit.
    private static var preferredFolderKey: String? = nil

    static func preferSelectedFolder(_ folder: URL) {
        let key = folder.standardizedFileURL.path
        ioQueue.async { preferredFolderKey = key }
    }

    private static func forgetHot(_ key: String) {
        hotTracks.removeValue(forKey: key)
        hotCosts.removeValue(forKey: key)
        hotLRU.removeAll { $0 == key }
    }

    private static func touchHot(_ key: String) {
        hotLRU.removeAll { $0 == key }
        hotLRU.append(key)
    }

    private static func rememberHot(_ tracks: [Track], key: String, cost: Int) {
        forgetHot(key)
        guard cost <= hotMaxBytes else { return }
        hotTracks[key] = tracks
        hotCosts[key] = cost
        touchHot(key)
        while hotLRU.count > hotMaxEntries || hotCosts.values.reduce(0, +) > hotMaxBytes {
            // Evict background/preload entries first; selected folder is
            // still evictable if it is the only remaining candidate.
            guard let victim = hotLRU.first(where: { $0 != preferredFolderKey })
                ?? hotLRU.first else { break }
            forgetHot(victim)
        }
    }

    // P2.9.13.5 Stage 1: opt-in autonomous cache, legacy authoritative.
    static func loadDedupAutonomous(for folder: URL) -> [Track]? {
        guard FaderDedupAutonomousStore.enabled else { return nil }
        return ioQueue.sync {
            FaderDedupAutonomousStore.load(folder: folder, legacyURL: cacheURL(for: folder))
        }
    }

    static func load(for folder: URL) -> [Track]? {
        // FADER_P29132_FOLDER_LOAD_DIAGNOSTICS (timing only)
        let requestedAt = ProcessInfo.processInfo.systemUptime
        return ioQueue.sync {
            let startedAt = ProcessInfo.processInfo.systemUptime
            let key = folder.standardizedFileURL.path
            if let fast = FaderDedupAutonomousStore.load(folder: folder, legacyURL: cacheURL(for: folder)) {
                rememberHot(fast, key: key, cost: 0)
                return fast
            }
            if let tracks = hotTracks[key] {
                touchHot(key)
                print(String(format: "FADER PERF FOLDER CACHE: wait=%.0fms read=0ms decode=0ms map=0ms source=hot tracks=%d name=%@", (startedAt-requestedAt)*1000, tracks.count, folder.lastPathComponent))
                return tracks
            }
            let url = cacheURL(for: folder)
            let raw = try? Data(contentsOf: url)
            let readAt = ProcessInfo.processInfo.systemUptime
            let record = raw.flatMap { try? JSONDecoder().decode(CacheRecord.self, from: $0) }
            let decodedAt = ProcessInfo.processInfo.systemUptime
            guard let record = record,
                  record.folderPath == key else {
                print(String(format: "FADER PERF FOLDER CACHE: wait=%.0fms read=%.0fms decode=%.0fms status=missing name=%@", (startedAt-requestedAt)*1000, (readAt-startedAt)*1000, (decodedAt-readAt)*1000, folder.lastPathComponent))
                return nil
            }
            let tracks = record.tracks.map { $0.makeTrack() }
            if let raw { FaderDedupAutonomousStore.createIfEnabled(folder: folder, legacyJSON: raw) }
            rememberHot(tracks, key: key, cost: raw?.count ?? 0)
            let endedAt = ProcessInfo.processInfo.systemUptime
            print(String(format: "FADER PERF FOLDER CACHE: wait=%.0fms read=%.0fms decode=%.0fms map=%.0fms source=disk tracks=%d bytes=%d name=%@", (startedAt-requestedAt)*1000, (readAt-startedAt)*1000, (decodedAt-readAt)*1000, (endedAt-decodedAt)*1000, tracks.count, raw?.count ?? 0, folder.lastPathComponent))
            return tracks
        }
    }

    static func save(_ tracks: [Track], for folder: URL) {
        // Capture the current snapshot and enqueue immediately.
        // Encoding and disk writing happen off the main thread.
        ioQueue.async {
            _ = saveNow(tracks, for: folder)
        }
    }

    // FADER_P295A_CACHE_SAVE_COMPLETION
    static func save(
        _ tracks: [Track],
        for folder: URL,
        completion: @escaping (Bool) -> Void
    ) {
        ioQueue.async {
            let success = saveNow(tracks, for: folder)
            DispatchQueue.main.async {
                completion(success)
            }
        }
    }

    @discardableResult
    private static func saveNow(_ tracks: [Track], for folder: URL) -> Bool {
        do {
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )

            let record = CacheRecord(
                folderPath: folder.standardizedFileURL.path,
                tracks: tracks.map(CachedTrack.init)
            )

            let data = try JSONEncoder().encode(record)

            try data.write(
                to: cacheURL(for: folder),
                options: .atomic
            )

            FaderDedupAutonomousStore.createIfEnabled(folder: folder, legacyJSON: data)
            forgetHot(folder.standardizedFileURL.path)
            print("FADER CACHE: saved \(tracks.count) tracks")
            return true
        } catch {
            print("FADER CACHE: save failed: \(error)")
            return false
        }
    }


    // MARK: - FADER_P2944F_SMART_REFRESH_ENGINE

    struct SmartRefreshResult {
        let tracks: [Track]
        let unchanged: Int
        let updated: Int
        let added: Int
        let elapsed: TimeInterval
    }

    enum SmartRefreshError: LocalizedError {
        case cacheUnavailable
        case incompleteCache
        case incompleteInventory(Int)

        var errorDescription: String? {
            switch self {
            case .cacheUnavailable:
                return "Smart Refresh requires an existing folder cache."
            case .incompleteCache:
                return "Cached file attributes are incomplete."
            case .incompleteInventory(let count):
                return "Inventory is missing \(count) cached audio files. Cache preserved."
            }
        }
    }

    // Called only from a background thread.
    // Does not save, remove or modify the persistent cache.
    static func smartRefresh(for folder: URL) throws -> SmartRefreshResult {
        let started = ProcessInfo.processInfo.systemUptime
        let folderPath = folder.standardizedFileURL.path

        let record: CacheRecord = try ioQueue.sync {
            let data = try Data(contentsOf: cacheURL(for: folder))
            return try JSONDecoder().decode(CacheRecord.self, from: data)
        }

        guard record.folderPath == folderPath,
              !record.tracks.isEmpty else {
            throw SmartRefreshError.cacheUnavailable
        }

        let cachedPaths = record.tracks.map { $0.path }

        guard Set(cachedPaths).count == cachedPaths.count,
              record.tracks.allSatisfy({
                  $0.fileSize != nil && $0.modificationDate != nil
              }) else {
            throw SmartRefreshError.incompleteCache
        }

        let inventory = try SmartRefreshInventory.collect(folder: folder)
        let files = inventory.files

        // A missing cached path may indicate an incomplete SMB listing.
        // Refuse changes rather than silently losing music.
        let missing = cachedPaths.filter { files[$0] == nil }
        guard missing.isEmpty else {
            throw SmartRefreshError.incompleteInventory(missing.count)
        }

        let cachedByPath = Dictionary(
            uniqueKeysWithValues: record.tracks.map { ($0.path, $0) }
        )

        var result: [Track] = []
        result.reserveCapacity(files.count)

        var unchanged = 0
        var updated = 0
        var added = 0

        for (path, info) in files {
            if let cached = cachedByPath[path] {
                let sameSize = cached.fileSize == info.size
                let sameDate = cached.modificationDate.map {
                    abs($0.timeIntervalSince(info.modificationDate)) < 0.001
                } ?? false

                if sameSize && sameDate {
                    result.append(cached.makeTrack())
                    unchanged += 1
                } else {
                    result.append(Library.makeTrack(url: info.url))
                    updated += 1
                }
            } else {
                result.append(Library.makeTrack(url: info.url))
                added += 1
            }
        }

        result.sort { ($0.artist, $0.title) < ($1.artist, $1.title) }

        return SmartRefreshResult(
            tracks: result,
            unchanged: unchanged,
            updated: updated,
            added: added,
            elapsed: ProcessInfo.processInfo.systemUptime - started
        )
    }

    static func remove(for folder: URL) {
        ioQueue.async {
            FaderDedupAutonomousStore.removeManifest(folder: folder)
            forgetHot(folder.standardizedFileURL.path)
            do {
                let url = cacheURL(for: folder)

                if FileManager.default.fileExists(atPath: url.path) {
                    try FileManager.default.removeItem(at: url)
                }
            } catch {
                print("FADER CACHE: removal failed: \(error)")
            }
        }
    }
}
