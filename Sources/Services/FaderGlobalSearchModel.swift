import Foundation
import Combine

/// A validated catalog belongs to one source generation. Typing only queries that
/// catalog; it never fingerprints the library or runs database integrity checks.
final class FaderGlobalSearchModel: ObservableObject {
    struct Result {
        let query: String
        let count: Int
        let shown: [Track]
    }
    private struct Catalog {
        let generation: UUID
        let ordered: [Track]
        let database: URL
    }
    @Published private(set) var total = 0
    @Published private(set) var isIndexing = false
    @Published private(set) var isSearching = false
    @Published private(set) var result: Result?
    @Published private(set) var error: String?
    private var catalog: Catalog?
    private var generation = UUID()
    private var query = ""
    private var pending: DispatchWorkItem?
    private var running: FaderSearchCancellation?
    private var sourceWork: FaderSearchCancellation?
    private let indexingQueue = DispatchQueue(label: "fader.search.prepare", qos: .utility)
    private let queryQueue = DispatchQueue(label: "fader.search.query", qos: .userInitiated)
    private let directory: URL
    private let debounce: TimeInterval

    init(directory: URL = SQLiteSearchService.indexDirectory, debounce: TimeInterval = 0.25) {
        self.directory = directory
        self.debounce = debounce
    }

    func updateSources(_ tracks: [Track]) {
        precondition(Thread.isMainThread)
        sourceWork?.cancel(); running?.cancel(); pending?.cancel()
        let ticket = UUID()
        generation = ticket
        catalog = nil; result = nil; error = nil
        isIndexing = true; isSearching = false
        let cancellation = FaderSearchCancellation()
        sourceWork = cancellation
        let directory = self.directory
        indexingQueue.async { [weak self] in
            do {
                try cancellation.check()
                var byPath: [String: Track] = [:]
                for track in tracks where byPath[track.normalizedPath] == nil { byPath[track.normalizedPath] = track }
                let ordered = byPath.values.sorted {
                    let a = ($0.artist + " " + $0.title).localizedLowercase
                    let b = ($1.artist + " " + $1.title).localizedLowercase
                    return a == b ? $0.normalizedPath < $1.normalizedPath : a < b
                }
                try cancellation.check()
                let database = try SQLiteSearchService.prepareUnifiedIndex(tracks: tracks, in: directory)
                try cancellation.check()
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.generation == ticket else { return }
                    self.catalog = Catalog(generation: ticket, ordered: ordered, database: database)
                    self.total = ordered.count
                    self.isIndexing = false
                    self.search(self.query)
                }
            } catch {
                guard !cancellation.isCancelled else { return }
                let message = error.localizedDescription
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.generation == ticket else { return }
                    self.isIndexing = false; self.error = message
                }
            }
        }
    }

    func search(_ text: String) {
        precondition(Thread.isMainThread)
        pending?.cancel(); running?.cancel()
        query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        error = nil
        guard !query.isEmpty else { result = nil; isSearching = false; return }
        guard let catalog else { isSearching = false; return }
        let request = query
        let cancellation = FaderSearchCancellation()
        running = cancellation
        isSearching = true
        let task = DispatchWorkItem { [weak self] in
            guard let self, !cancellation.isCancelled else { return }
            self.queryQueue.async { [weak self] in
                do {
                    try cancellation.check()
                    let hits = try SQLiteSearchService.matchingOptimizedPathsForShadow(
                        databaseURL: catalog.database, text: request, cancellation: cancellation)
                    try cancellation.check()
                    var count = 0
                    var shown: [Track] = []
                    for track in catalog.ordered where hits.contains(track.normalizedPath) {
                        count += 1
                        if shown.count < 200 { shown.append(track) }
                    }
                    try cancellation.check()
                    let result = Result(query: request, count: count, shown: shown)
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.generation == catalog.generation,
                              self.query == request, !cancellation.isCancelled else { return }
                        self.result = result; self.isSearching = false
                    }
                } catch {
                    guard !cancellation.isCancelled else { return }
                    let message = error.localizedDescription
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.generation == catalog.generation, self.query == request else { return }
                        self.error = message; self.isSearching = false
                    }
                }
            }
        }
        pending = task
        DispatchQueue.main.asyncAfter(deadline: .now() + debounce, execute: task)
    }
}
