import Foundation
import AVFoundation
import ImageIO
import UniformTypeIdentifiers

/// Scans folders and playlist files, and enriches tracks with embedded tags.
enum Library {

    /// File extensions we treat as playable audio.
    static let audioExtensions: Set<String> = [
        "mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "ogg", "oga", "wma", "opus", "mp4"
    ]

    /// Recursively collect audio files under `folder`.
    static func scan(folder: URL) -> [Track] {
        let scanStarted = ProcessInfo.processInfo.systemUptime
        let fm = FileManager.default
        let items = fm.enumerator(at: folder,
                                  includingPropertiesForKeys: [.isRegularFileKey],
                                  options: [.skipsHiddenFiles])
        var tracks: [Track] = []
        var seenPaths = Set<String>()
        var duplicatesSkipped = 0

        guard let en = items else { return [] }
        for case let url as URL in en {
            let ext = url.pathExtension.lowercased()
            guard audioExtensions.contains(ext) else { continue }

            let path = url.standardizedFileURL.path

            guard seenPaths.insert(path).inserted else {
                duplicatesSkipped += 1
                continue
            }

            tracks.append(makeTrack(url: url))
        }

        if duplicatesSkipped > 0 {
            print("FADER SCAN DEDUP: skipped \(duplicatesSkipped) duplicate entries in \(folder.lastPathComponent)")
        }

        tracks.sort { ($0.artist, $0.title) < ($1.artist, $1.title) }

        let elapsed = ProcessInfo.processInfo.systemUptime - scanStarted
        print(String(format: "FADER SCAN TIME: folder=%@ tracks=%d seconds=%.2f duplicates=%d",
                     folder.lastPathComponent, tracks.count, elapsed, duplicatesSkipped))

        return tracks
    }

    /// Build a Track from a file URL. Duration is measured synchronously
    /// (fast for local files); tags come from the file name first.
    static func makeTrack(url: URL) -> Track {
        let base = url.deletingPathExtension().lastPathComponent
        let (t, a) = splitName(base)
        var track = Track(url: url, title: t, artist: a, album: "")
        track.format = url.pathExtension.uppercased()
        track.duration = measureDuration(url: url)

        // Preserve the synchronous scan/import contract while AVFoundation loads
        // metadata on an independent task, away from the calling executor.
        let tags = readCommonTags(url: url)
        if let title = tags["title"] { track.title = title }
        if let artist = tags["artist"] { track.artist = artist }
        if let album = tags["albumName"] { track.album = album }
        // Preserve ID3 handling; native FLAC pictures use a separate read-only reader.
        let artwork = ID3Parser.extractArtwork(from: url)
            ?? (url.pathExtension.lowercased() == "flac"
                ? FLACArtworkReader.extractArtwork(from: url) : nil)
        if let originalArtwork = artwork {
            track.artworkData = thumbnailArtwork(originalArtwork)
                ?? originalArtwork
        }
        return track
    }


    /// A locked result bridges the async AVFoundation API to existing synchronous
    /// importers. The detached task never depends on the caller's actor or queue.
    private final class MetadataResult: @unchecked Sendable {
        private let ready = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var tags: [String: String] = [:]

        func finish(_ value: [String: String]) {
            lock.lock()
            tags = value
            lock.unlock()
            ready.signal()
        }

        func wait() -> [String: String] {
            ready.wait()
            lock.lock()
            defer { lock.unlock() }
            return tags
        }
    }

    private static func readCommonTags(url: URL) -> [String: String] {
        let result = MetadataResult()
        Task.detached(priority: .userInitiated) {
            var tags: [String: String] = [:]
            defer { result.finish(tags) }
            let asset = AVURLAsset(url: url)
            guard var metadata = try? await asset.load(.commonMetadata) else {
                return // Keep filename-derived values if metadata cannot be read.
            }
            // FLAC exposes Vorbis tags through metadata, but commonMetadata may
            // be empty. AVFoundation still maps their commonKey values correctly.
            if metadata.isEmpty && url.pathExtension.lowercased() == "flac" {
                metadata = (try? await asset.load(.metadata)) ?? []
            }
            for item in metadata {
                guard let key = item.commonKey?.rawValue,
                      ["title", "artist", "albumName"].contains(key),
                      let raw = try? await item.load(.stringValue) else { continue }
                let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if !value.isEmpty { tags[key] = value }
            }
        }
        return result.wait()
    }

    // MARK: - FADER_ARTWORK_THUMBNAIL_V1

    /// Reduce embedded artwork before keeping it in Track memory/cache.
    private static func thumbnailArtwork(
        _ data: Data,
        maxSize: Int = 256
    ) -> Data? {
        guard !data.isEmpty else { return nil }

        let sourceOptions: [CFString: Any] = [
            kCGImageSourceShouldCache: false
        ]

        guard let source = CGImageSourceCreateWithData(
            data as CFData,
            sourceOptions as CFDictionary
        ) else {
            return nil
        }

        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSize
        ]

        guard let image = CGImageSourceCreateThumbnailAtIndex(
            source,
            0,
            thumbnailOptions as CFDictionary
        ) else {
            return nil
        }

        let output = NSMutableData()

        guard let destination = CGImageDestinationCreateWithData(
            output,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else {
            return nil
        }

        let properties: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: 0.78
        ]

        CGImageDestinationAddImage(
            destination,
            image,
            properties as CFDictionary
        )

        guard CGImageDestinationFinalize(destination) else {
            return nil
        }

        return output as Data
    }

    /// Split "Artist - Title" style names. Falls back to the whole name.
    static func splitName(_ name: String) -> (title: String, artist: String) {
        if let range = name.range(of: " - ") {
            let artist = String(name[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
            let title = String(name[range.upperBound...]).trimmingCharacters(in: .whitespaces)
            if !artist.isEmpty && !title.isEmpty { return (title, artist) }
        }
        return (title: name, artist: "Unknown")
    }

    /// Measure length in seconds from the file header. 0 on failure.
    static func measureDuration(url: URL) -> Double {
        guard let file = try? AVAudioFile(forReading: url) else { return 0 }
        let frames = file.length
        let rate = file.processingFormat.sampleRate
        guard rate > 0 else { return 0 }
        return Double(frames) / rate
    }

    // MARK: - Playlist files

    static func isPlaylist(url: URL) -> Bool {
        ["m3u", "m3u8", "pls", "xspf", "json"].contains(url.pathExtension.lowercased())
    }

    /// Load any supported playlist format into tracks.
    static func loadPlaylist(url: URL) -> [Track] {
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "m3u", "m3u8", "pls":
            return parseTextPlaylist(url, ext: ext)
        case "xspf":
            return parseXSPF(url)
        case "json":
            return parseJSON(url)
        default:
            return []
        }
    }

    private static func parseTextPlaylist(_ url: URL, ext: String) -> [Track] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let dir = url.deletingLastPathComponent()
        var out: [Track] = []
        for rawLine in text.split(whereSeparator: { $0.isNewline }) {
            var line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("#") { continue }

            if ext == "pls" {
                // File1=/path  or  File1= C:\path
                guard line.lowercased().hasPrefix("file") else { continue }
                if let eq = line.firstIndex(of: "=") {
                    line = String(line[line.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
                }
            }
            out.append(resolve(line, relativeTo: dir))
        }
        // De-duplicate by absolute path.
        var seen = Set<String>()
        return out.filter { seen.insert($0.url.standardizedFileURL.absoluteString).inserted }
    }

    private static func resolve(_ path: String, relativeTo base: URL) -> Track {
        let trimmed = path.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return Track(url: base, title: "", artist: "", album: "") }
        // Unescape common M3U percent-encoding.
        let unescaped = trimmed
            .replacingOccurrences(of: "%20", with: " ")
            .replacingOccurrences(of: "%3A", with: ":")
            .replacingOccurrences(of: "%2F", with: "/")
            .replacingOccurrences(of: "%3F", with: "?")
            .replacingOccurrences(of: "%23", with: "#")
        if unescaped.hasPrefix("/") || unescaped.hasPrefix("file://") ||
           unescaped.range(of: ":", options: .caseInsensitive, range: unescaped.startIndex..<unescaped.index(unescaped.startIndex, offsetBy: 2)) != nil {
            // Absolute (unix or windows-like). Use as-is; windows drive paths
            // won't resolve on macOS but that's a data issue, not a crash.
            return makeTrack(url: URL(fileURLWithPath: unescaped))
        }
        return makeTrack(url: base.appendingPathComponent(unescaped).standardizedFileURL)
    }

    private static func parseXSPF(_ url: URL) -> [Track] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let dir = url.deletingLastPathComponent()
        var out: [Track] = []
        var search = text
        while let open = search.range(of: "<location>"),
              let close = search.range(of: "</location>") {
            let body = String(search[search.index(after: open.upperBound)..<close.lowerBound])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty {
                let u: URL
                if body.contains("://"), let parsed = URL(string: body) { u = parsed }
                else { u = dir.appendingPathComponent(body).standardizedFileURL }
                out.append(makeTrack(url: u))
            }
            search = String(search[close.upperBound...])
        }
        return out
    }

    private static func parseJSON(_ url: URL) -> [Track] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        guard let obj = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let dir = url.deletingLastPathComponent()
        func resolve(_ s: String) -> URL {
            if s.contains("://") { return URL(string: s) ?? dir }
            if s.hasPrefix("/") { return URL(fileURLWithPath: s) }
            return dir.appendingPathComponent(s).standardizedFileURL
        }
        func path(_ value: Any) -> Track? {
            if let s = value as? String { return makeTrack(url: resolve(s)) }
            if let dict = value as? [String: Any] {
                for key in ["path", "file", "location", "url", "trackPath"] {
                    if let s = dict[key] as? String { return makeTrack(url: resolve(s)) }
                }
            }
            return nil
        }
        var out: [Track] = []
        if let arr = obj as? [Any] {
            for item in arr { if let t = path(item) { out.append(t) } }
        } else if let dict = obj as? [String: Any], let arr = dict["tracks"] as? [Any] {
            for item in arr { if let t = path(item) { out.append(t) } }
        }
        return out
    }
}