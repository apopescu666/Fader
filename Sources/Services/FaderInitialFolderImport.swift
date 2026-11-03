import Foundation

/// Initial cache-miss import only. Metadata/artwork reading and final ordering
/// remain Library's responsibility. Cache refresh, search and playback are unchanged.
enum FaderInitialFolderImport {
    struct Progress {
        let completed: Int
        let total: Int
    }
    static func scan(folder: URL, onProgress: @escaping (Progress) -> Void) throws -> [Track] {
        let started = ProcessInfo.processInfo.systemUptime
        let fm = FileManager.default
        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: folder.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        var enumerationError: Error?
        guard let enumerator = fm.enumerator(at: folder,
            includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles],
            errorHandler: { _, error in enumerationError = error; return false }) else {
            throw CocoaError(.fileReadUnknown)
        }
        var files: [URL] = []
        var seen = Set<String>()
        for case let url as URL in enumerator {
            guard Library.audioExtensions.contains(url.pathExtension.lowercased()),
                  seen.insert(url.standardizedFileURL.path).inserted else { continue }
            files.append(url)
        }
        if let error = enumerationError { throw error }
        let listedAt = ProcessInfo.processInfo.systemUptime
        onProgress(Progress(completed: 0, total: files.count))
        let scheduler = FaderImportScheduler.shared
        var tracks = scheduler.scan(files: files, folder: folder, progress: onProgress)
        // Prevent caching a partial result if the network share disappears mid-import.
        guard fm.fileExists(atPath: folder.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        tracks.sort { ($0.artist, $0.title) < ($1.artist, $1.title) }
        let ready = ProcessInfo.processInfo.systemUptime
        FaderStorage.log(String(format:
            "INITIAL IMPORT workers=%d tracks=%d listing=%.3fs metadata=%.3fs total=%.3fs folder=%@",
            FaderStorage.serialImport ? 1 : 2, tracks.count, listedAt-started, ready-listedAt, ready-started, folder.lastPathComponent))
        return tracks
    }
}
