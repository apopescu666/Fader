import Foundation
import CryptoKit

// P2.9.13.5 STAGE 1: local autonomous cache, enabled by default; set FADER_DEDUP_AUTONOMOUS=0 to disable.
// Legacy FolderCache JSON remains authoritative and always provides fallback.
// Never reads, writes or removes original audio files.
enum FaderDedupAutonomousStore {
    private struct Legacy: Decodable {
        let folderPath: String
        let tracks: [LegacyTrack]
    }
    private struct LegacyTrack: Decodable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let artworkData: Data
        let fileSize: Int64?
        let modificationDate: Date?
    }
    private struct CompactTrack: Codable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let artworkSHA256: String?
    }
    private struct CompactFolder: Codable {
        let version: Int
        let folderPath: String
        let originalJSONSHA256: String
        let tracks: [CompactTrack]
    }

    private static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FaderPlayer", isDirectory: true)
            .appendingPathComponent("FolderCache", isDirectory: true)
            .appendingPathComponent("DedupV1", isDirectory: true)
    }
    private static func hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    private static func manifestURL(_ path: String) -> URL {
        directory.appendingPathComponent(hex(Data(path.utf8)) + ".json")
    }
    private static func blobURL(_ sha: String) -> URL {
        directory.appendingPathComponent("artwork", isDirectory: true)
            .appendingPathComponent(sha + ".bin")
    }
    static var enabled: Bool {
        ProcessInfo.processInfo.environment["FADER_DEDUP_AUTONOMOUS"] != "0"
    }

    // Called ONLY while FolderCacheStore.ioQueue is held.
    static func load(folder: URL, legacyURL: URL) -> [Track]? {
        guard enabled,
              let original = try? Data(contentsOf: legacyURL, options: .mappedIfSafe),
              let compact = try? JSONDecoder().decode(CompactFolder.self,
                   from: Data(contentsOf: manifestURL(folder.standardizedFileURL.path))),
              compact.version == 1,
              compact.folderPath == folder.standardizedFileURL.path,
              compact.originalJSONSHA256 == hex(original) else { return nil }
        // Check all referenced blobs exist before committing to the fast track array.
        // Actual blob SHA256 is verified lazily by the existing artwork reader.
        for hash in Set(compact.tracks.compactMap({ $0.artworkSHA256 })) {
            guard FileManager.default.fileExists(atPath: blobURL(hash).path) else { return nil }
        }
        var output: [Track] = []
        output.reserveCapacity(compact.tracks.count)
        for m in compact.tracks {
            var item = Track(url: URL(fileURLWithPath: m.path, isDirectory: false),
                             title: m.title, artist: m.artist, album: m.album)
            item.duration = m.duration
            item.format = m.format
            item.experimentalArtworkSHA256 = m.artworkSHA256
            item.experimentalArtworkDirectory = directory
            output.append(item)
        }
        print("FADER AUTONOMOUS HIT: folder=\(folder.lastPathComponent) tracks=\(output.count)")
        return output
    }

    // Publishes the metadata LAST. Every failure leaves legacy JSON usable.
    // Called ONLY while FolderCacheStore.ioQueue is held.
    static func createIfEnabled(folder: URL, legacyJSON: Data) {
        guard enabled else { return }
        do {
            let decoded = try JSONDecoder().decode(Legacy.self, from: legacyJSON)
            let path = folder.standardizedFileURL.path
            guard decoded.folderPath == path else { return }
            let fm = FileManager.default
            try fm.createDirectory(at: directory.appendingPathComponent("artwork", isDirectory: true),
                                   withIntermediateDirectories: true)
            var rows: [CompactTrack] = []
            rows.reserveCapacity(decoded.tracks.count)
            for m in decoded.tracks {
                let hash = m.artworkData.isEmpty ? nil : hex(m.artworkData)
                if let hash {
                    let file = blobURL(hash)
                    if !fm.fileExists(atPath: file.path) {
                        try m.artworkData.write(to: file, options: .atomic)
                    }
                }
                rows.append(CompactTrack(path: m.path, title: m.title, artist: m.artist,
                    album: m.album, duration: m.duration, format: m.format,
                    artworkSHA256: hash))
            }
            let compact = CompactFolder(version: 1, folderPath: path,
                originalJSONSHA256: hex(legacyJSON), tracks: rows)
            try JSONEncoder().encode(compact).write(to: manifestURL(path), options: .atomic)
            print("FADER AUTONOMOUS BUILT: folder=\(folder.lastPathComponent) tracks=\(rows.count)")
        } catch {
            print("FADER AUTONOMOUS BUILD SKIPPED: \(error)")
        }
    }

    // On legacy JSON update, the old compact manifest fails the digest check.
    // During remove, delete only this folder's compact manifest; shared artwork
    // blobs remain untouched because other folders may reference them.
    static func removeManifest(folder: URL) {
        guard enabled else { return }
        let url = manifestURL(folder.standardizedFileURL.path)
        try? FileManager.default.removeItem(at: url)
    }
}
