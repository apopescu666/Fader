import Foundation

/// Two shared readers for ALL initial folder imports. Selection changes affect
/// the next file, never interrupt a file already being decoded.
final class FaderImportScheduler: @unchecked Sendable {
    static let shared = FaderImportScheduler(limit: FaderStorage.serialImport ? 1 : 2)
    private final class Batch {
        let path: String
        let files: [URL]
        let progress: (FaderInitialFolderImport.Progress) -> Void
        let done = DispatchGroup()
        var next = 0
        var completed = 0
        var lastReport = ProcessInfo.processInfo.systemUptime
        var tracks: [Track?]
        init(path: String, files: [URL], progress: @escaping (FaderInitialFolderImport.Progress) -> Void) {
            self.path = path; self.files = files; self.progress = progress
            tracks = Array(repeating: nil, count: files.count)
            done.enter()
        }
    }
    private let lock = NSLock()
    private var batches: [Batch] = []
    private var preferredPath: String?
    private var workers = 0
    private let limit: Int
    private let reader: (URL) -> Track

    init(limit: Int = 2, reader: @escaping (URL) -> Track = Library.makeTrack) {
        self.limit = max(1, min(limit, 2)); self.reader = reader
    }
    func prefer(_ folder: URL) {
        lock.lock(); preferredPath = folder.standardizedFileURL.path; lock.unlock()
    }
    func scan(files: [URL], folder: URL,
              progress: @escaping (FaderInitialFolderImport.Progress) -> Void) -> [Track] {
        guard !files.isEmpty else { return [] }
        let batch = Batch(path: folder.standardizedFileURL.path, files: files, progress: progress)
        lock.lock()
        batches.append(batch)
        while workers < limit {
            workers += 1
            DispatchQueue.global(qos: .userInitiated).async { self.runWorker() }
        }
        lock.unlock()
        progress(FaderInitialFolderImport.Progress(completed: 0, total: files.count))
        batch.done.wait()
        return batch.tracks.compactMap { $0 }
    }
    private func runWorker() {
        while true {
            lock.lock()
            let available = batches.filter { $0.next < $0.files.count }
            guard let batch = available.first(where: { $0.path == preferredPath }) ?? available.first else {
                workers -= 1; lock.unlock(); return
            }
            let index = batch.next
            batch.next += 1
            lock.unlock()
            let track = autoreleasepool { reader(batch.files[index]) }
            lock.lock()
            batch.tracks[index] = track
            batch.completed += 1
            let now = ProcessInfo.processInfo.systemUptime
            let finished = batch.completed == batch.files.count
            let notify = finished || now - batch.lastReport >= 0.2
            if notify { batch.lastReport = now }
            let snapshot = FaderInitialFolderImport.Progress(completed: batch.completed, total: batch.files.count)
            if finished { batches.removeAll { $0 === batch } }
            lock.unlock()
            if notify { batch.progress(snapshot) }
            if finished { batch.done.leave() }
        }
    }
}
