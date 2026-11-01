import Foundation

/// Preserve playlist-only metadata when queue entries or source folders disappear.
/// Source membership is explicit; cached metadata alone never creates membership.
enum FaderSearchSources {
    struct Snapshot {
        let tracks: [Track]
        let metadata: [String: Track]
    }
    static func collect(folders: [Track], queue: [Track], activePlaylistPaths: Set<String>,
                        metadata: [String: Track]) -> Snapshot {
        var metadata = metadata
        for track in queue + folders where activePlaylistPaths.contains(track.normalizedPath) && metadata[track.normalizedPath] == nil {
            metadata[track.normalizedPath] = track
        }
        let playlistTracks = metadata.filter { activePlaylistPaths.contains($0.key) }.map { $0.value }
        return Snapshot(tracks: folders + queue + playlistTracks, metadata: metadata)
    }
}
