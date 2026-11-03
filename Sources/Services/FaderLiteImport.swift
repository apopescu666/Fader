import Foundation

/// Only the new Lite → Current Queue flow uses this policy. Existing folder,
/// playlist, table and full-player drop handlers retain their behavior.
enum FaderLiteImport {
    enum Choice { case append, replace, cancel }
    static func collect(_ urls: [URL]) -> [Track] {
        var tracks: [Track] = []
        for url in urls where url.isFileURL {
            var directory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &directory) else { continue }
            if directory.boolValue { tracks.append(contentsOf: Library.scan(folder: url)) }
            else if Library.isPlaylist(url: url) { tracks.append(contentsOf: Library.loadPlaylist(url: url)) }
            else if Library.audioExtensions.contains(url.pathExtension.lowercased()) { tracks.append(Library.makeTrack(url: url)) }
        }
        var seen = Set<String>()
        return tracks.filter { seen.insert($0.normalizedPath).inserted }
    }
    /// A Lite transfer always carries its current song first, even for Replace.
    /// Prefer imported metadata when that file is also among the dropped items.
    static func includingCurrent(_ current: Track?, in tracks: [Track]) -> [Track] {
        guard let current else { return tracks }
        let song = tracks.first(where: { $0.normalizedPath == current.normalizedPath }) ?? current
        return [song] + tracks.filter { $0.normalizedPath != current.normalizedPath }
    }
    static func apply(_ tracks: [Track], choice: Choice, player: Player, liteTrackPath: String? = nil) {
        switch choice {
        case .append: player.append(tracks)
        case .replace: player.replaceQueueFromLite(tracks)
        case .cancel: break
        }
        if choice != .cancel, let liteTrackPath {
            player.attachLitePlaybackToQueue(path: liteTrackPath)
        }
    }
}
