import Foundation
import SwiftUI

/// A single playable item in the queue.
struct Track: Identifiable, Hashable {
    let id = UUID()
    var url: URL {
        didSet { normalizedPath = url.standardizedFileURL.path }
    }
    private(set) var normalizedPath: String

    init(url: URL, title: String, artist: String, album: String) {
        self.url = url
        self.normalizedPath = url.standardizedFileURL.path
        self.title = title
        self.artist = artist
        self.album = album
    }

    /// Display fields. Derived from the file name first, then enriched
    /// from embedded audio tags when available.
    var title: String
    var artist: String
    var album: String

    /// Seconds. Measured from the file when it is added (0 only if unknown).
    var duration: Double = 0
    var format: String = ""

    // FADER_P29134D15_DORMANT_TRACK_ARTWORK_BRIDGE
    // Existing tracks keep their embedded bytes and the same artworkData API.
    // These optional fields are used only by future explicit dedup trial tracks.
    var embeddedArtworkData: Data? = nil {
        didSet { artworkRevision = UUID() }
    }
    private(set) var artworkRevision = UUID()

    /// Stable in-memory identity; inspecting it never loads a cover from disk.
    var artworkIdentity: String {
        if embeddedArtworkData == nil, let hash = experimentalArtworkSHA256 {
            return (experimentalArtworkDirectory?.path ?? "") + ":" + hash
        }
        return embeddedArtworkData == nil ? "none" : artworkRevision.uuidString
    }
    var experimentalArtworkSHA256: String? = nil
    var experimentalArtworkDirectory: URL? = nil

    var artworkData: Data? {
        get {
            if let embeddedArtworkData { return embeddedArtworkData }
            guard let hash = experimentalArtworkSHA256,
                  let directory = experimentalArtworkDirectory else { return nil }
            return FaderDedupTrackArtworkStore.data(sha256: hash, prototypeDirectory: directory)
        }
        set { embeddedArtworkData = newValue }
    }

    var fileName: String { url.lastPathComponent }

    static func == (l: Track, r: Track) -> Bool { l.id == r.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

/// How the queue behaves when a track reaches its end.
enum RepeatMode: String, CaseIterable, Identifiable {
    case off
    case all
    case one

    var id: String { rawValue }
    var label: String {
        switch self {
        case .off: return "Repeat off"
        case .all: return "Repeat all"
        case .one: return "Repeat one"
        }
    }
    var symbol: String {
        switch self {
        case .off: return "repeat"
        case .all: return "repeat"
        case .one: return "repeat.1"
        }
    }
}
