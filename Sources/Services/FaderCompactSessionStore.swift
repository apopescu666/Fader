import Foundation
import CryptoKit

/// Versioned session manifest + immutable metadata catalog + shared cover blobs.
/// The legacy session is read-only fallback, including after an older build is used.
enum FaderCompactSessionStore {
    struct Row: Codable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let artworkHash: String?

        func makeTrack(directory: URL) -> Track {
            var track = Track(url: URL(fileURLWithPath: path, isDirectory: false), title: title, artist: artist, album: album)
            track.duration = duration
            track.format = format
            track.experimentalArtworkSHA256 = artworkHash
            track.experimentalArtworkDirectory = directory
            return track
        }
    }
    struct Manifest: Codable {
        let version: Int
        let catalog: String
        let queueCount: Int
        let folderCount: Int
        let isFolderPlayback: Bool
        let currentIndex: Int
        let shuffle: Bool
        let repeatMode: String
    }

    static func directory(for legacy: URL) -> URL {
        legacy.deletingLastPathComponent().appendingPathComponent("PlaybackSessionV2", isDirectory: true)
    }
    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func validHash(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    final class CatalogWriter {
        let directory: URL
        private var previousSignature: Int?
        private var previousCatalog: String?

        init(legacyURL: URL) { directory = FaderCompactSessionStore.directory(for: legacyURL) }

        func save(_ snapshot: PlaybackSessionStore.Snapshot) throws {
            let tracks = snapshot.queue + snapshot.folderTracks
            var h = Hasher()
            h.combine(snapshot.queue.count)
            for t in tracks {
                h.combine(t.normalizedPath); h.combine(t.title); h.combine(t.artist)
                h.combine(t.album); h.combine(t.duration); h.combine(t.format); h.combine(t.artworkIdentity)
            }
            let signature = h.finalize()
            let fm = FileManager.default
            try fm.createDirectory(at: directory.appendingPathComponent("artwork"), withIntermediateDirectories: true)
            let catalog: String
            var catalogChanged = false
            if previousSignature == signature, let previousCatalog,
               fm.fileExists(atPath: directory.appendingPathComponent(previousCatalog + ".json").path) {
                catalog = previousCatalog
            } else {
                catalogChanged = true
                var rows: [Row] = []
                rows.reserveCapacity(tracks.count)
                // A source reference can be shared by thousands of album tracks.
                var knownArtwork: [String: String] = [:]
                var knownBytes: [Data: String] = [:]
                for track in tracks {
                    let identity = track.artworkIdentity
                    var hash = knownArtwork[identity]
                    if hash == nil, identity != "none" {
                        if let existing = track.experimentalArtworkSHA256,
                           track.embeddedArtworkData == nil, validHash(existing),
                           fm.fileExists(atPath: directory.appendingPathComponent("artwork/" + existing + ".bin").path) {
                            hash = existing
                        } else if let data = track.artworkData, !data.isEmpty {
                            let value = knownBytes[data] ?? digest(data)
                            knownBytes[data] = value
                            let blob = directory.appendingPathComponent("artwork/" + value + ".bin")
                            if !fm.fileExists(atPath: blob.path) { try data.write(to: blob, options: .atomic) }
                            hash = value
                        }
                        if let hash { knownArtwork[identity] = hash }
                    }
                    rows.append(Row(path: track.url.path, title: track.title, artist: track.artist,
                                    album: track.album, duration: track.duration, format: track.format, artworkHash: hash))
                }
                let encoder = JSONEncoder()
                encoder.outputFormatting = [.sortedKeys]
                let data = try encoder.encode(rows)
                catalog = digest(data)
                let file = directory.appendingPathComponent(catalog + ".json")
                if !fm.fileExists(atPath: file.path) { try data.write(to: file, options: .atomic) }
                previousCatalog = catalog
                previousSignature = signature
            }
            // Publish last: interrupted writes always leave the prior session usable.
            let manifest = Manifest(version: 2, catalog: catalog, queueCount: snapshot.queue.count,
                folderCount: snapshot.folderTracks.count, isFolderPlayback: snapshot.isFolderPlayback,
                currentIndex: snapshot.currentIndex, shuffle: snapshot.shuffle, repeatMode: snapshot.repeatMode)
            let file = directory.appendingPathComponent("session.json")
            let previous = directory.appendingPathComponent("session.previous.json")
            var previousCatalog: String?
            if let data = try? Data(contentsOf: file),
               let old = try? JSONDecoder().decode(Manifest.self, from: data),
               old.version == 2, validHash(old.catalog) {
                try data.write(to: previous, options: .atomic)
                previousCatalog = old.catalog
            }
            try JSONEncoder().encode(manifest).write(to: file, options: .atomic)
            // Only derived catalogs owned by this store are pruned. Keep the
            // current and previous publication; never touch legacy data or covers.
            if catalogChanged, let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) {
                for candidate in files where candidate.pathExtension == "json" {
                    let hash = candidate.deletingPathExtension().lastPathComponent
                    if validHash(hash), hash != catalog, hash != previousCatalog { try? fm.removeItem(at: candidate) }
                }
            }
        }
    }

    static func load(legacyURL: URL) -> PlaybackSessionStore.RestoredSession? {
        let directory = directory(for: legacyURL)
        let file = directory.appendingPathComponent("session.json")
        // An older app may have written a newer legacy session after a rollback.
        let fm = FileManager.default
        let oldDate = (try? fm.attributesOfItem(atPath: legacyURL.path)[.modificationDate]) as? Date
        let newDate = (try? fm.attributesOfItem(atPath: file.path)[.modificationDate]) as? Date
        if let oldDate, let newDate, oldDate > newDate { return nil }
        for candidate in [file, directory.appendingPathComponent("session.previous.json")] {
        guard let data = try? Data(contentsOf: candidate),
              let manifest = try? JSONDecoder().decode(Manifest.self, from: data),
              manifest.version == 2, validHash(manifest.catalog),
              manifest.queueCount >= 0, manifest.folderCount >= 0,
              let catalog = try? Data(contentsOf: directory.appendingPathComponent(manifest.catalog + ".json")),
              digest(catalog) == manifest.catalog,
              let rows = try? JSONDecoder().decode([Row].self, from: catalog),
              manifest.queueCount <= rows.count,
              manifest.folderCount == rows.count - manifest.queueCount,
              rows.allSatisfy({ $0.artworkHash.map(validHash) ?? true }) else { continue }
        let hashes = Set(rows.compactMap(\.artworkHash))
        guard hashes.allSatisfy({ fm.fileExists(atPath: directory.appendingPathComponent("artwork/" + $0 + ".bin").path) }) else { continue }
        // No cover decoding or original-audio access is performed here.
        let queue = rows.prefix(manifest.queueCount).map { $0.makeTrack(directory: directory) }
        let folder = rows.dropFirst(manifest.queueCount).map { $0.makeTrack(directory: directory) }
        let session = PlaybackSessionStore.Session(queue: queue.map { .init(metadata: $0) },
            folderTracks: folder.map { .init(metadata: $0) }, isFolderPlayback: manifest.isFolderPlayback,
            currentIndex: manifest.currentIndex, shuffle: manifest.shuffle, repeatMode: manifest.repeatMode)
        return .init(session: session, queue: queue, folderTracks: folder)
        }
        return nil
    }
}
