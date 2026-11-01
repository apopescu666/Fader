import Foundation

/// A display projection only. Original indices (including duplicate queue items)
/// remain the indices passed to existing selection, playback and drag handlers.
enum FaderTrackListing {
    struct Entry: Identifiable {
        let index: Int
        let track: Track
        var id: Int { index }
    }

    static func entries(in tracks: [Track], query: String) -> [Entry] {
        tracks.enumerated().compactMap { index, track in
            // Keep the original search semantics, including spaces and case.
            if !query.isEmpty {
                let text = "\(track.title) \(track.artist) \(track.album)"
                guard text.localizedCaseInsensitiveContains(query) else { return nil }
            }
            return Entry(index: index, track: track)
        }
    }

    static func affectedRows(in identities: [String], previous: String?, current: String?) -> IndexSet {
        IndexSet(identities.indices.filter {
            identities[$0] == previous || identities[$0] == current
        })
    }

    static func hashDisplay(_ track: Track, into hasher: inout Hasher) {
        hasher.combine(track.title)
        hasher.combine(track.artist)
        hasher.combine(track.album)
        hasher.combine(track.duration)
        hasher.combine(track.artworkIdentity)
    }
}
