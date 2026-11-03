import Foundation
import SQLite3
import CryptoKit
import Darwin

// SQLite search and index synchronization service.
// Global Search uses the P272 index when SQLite Beta is enabled.
// Database searches must run outside the SwiftUI rendering path.
enum SQLiteSearchService {
    struct Song: Equatable {
        let path: String
        let title: String
        let artist: String
        let album: String
    }

    enum SearchError: LocalizedError {
        case missingIndex
        case database(String)

        var errorDescription: String? {
            switch self {
            case .missingIndex: return "Search index is unavailable. Current Global Search remains available."
            case .database(let details): return "SQLite search: \(details)"
            }
        }
    }


    // FADER_SQLITE_BETA_P281_V1
    // Independently validates both content and structural integrity of P272.
    // Called only from a background queue; never writes to the database.
    static func isUnifiedIndexCurrent(databaseURL: URL, tracks: [Track]) -> Bool {
        let schema = "P272_UNIFIED_SYNC_V1"
        var unique: [String: Song] = [:]
        for track in tracks {
            let path = track.url.standardizedFileURL.path
            unique[path] = Song(path: path, title: track.title,
                                artist: track.artist, album: track.album)
        }
        let fingerprint = contentFingerprint(unique, schema: schema)
        return validatedFingerprintMatches(at: databaseURL, schema: schema,
                                           count: unique.count, fingerprint: fingerprint)
    }

    // Read-only optimized Unicode substring search.
    // Used by Global Search SQLite Beta with the validated P272 index.
    // The method name is retained for compatibility with existing callers.
    static func matchingOptimizedPathsForShadow(databaseURL: URL, text: String, cancellation: FaderSearchCancellation? = nil) throws -> Set<String> {
        try cancellation?.check()
        let rawQuery = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rawQuery.isEmpty else { return [] }
        guard FileManager.default.fileExists(atPath: databaseURL.path) else {
            throw SearchError.missingIndex
        }
        let query = rawQuery.folding(options: [.diacriticInsensitive, .caseInsensitive],
                                     locale: Locale(identifier: "en_US_POSIX"))
        guard !query.isEmpty else { return [] }
        var handle: OpaquePointer?
        let result = databaseURL.path.withCString {
            sqlite3_open_v2($0, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil)
        }
        guard result == SQLITE_OK, let db = handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let handle { sqlite3_close(handle) }
            throw SearchError.database(message)
        }
        defer { sqlite3_close(db) }
        if let cancellation {
            sqlite3_progress_handler(db, 500, { context in
                guard let context else { return 0 }
                return Unmanaged<FaderSearchCancellation>.fromOpaque(context).takeUnretainedValue().isCancelled ? 1 : 0
            }, Unmanaged.passUnretained(cancellation).toOpaque())
        }
        defer {
            sqlite3_progress_handler(db, 0, nil, nil)
            withExtendedLifetime(cancellation) {}
        }
        guard sqlite3_db_readonly(db, "main") == 1 else {
            throw SearchError.database("Experimental index not read-only")
        }
        // LIKE on trigram virtual table can use its index for 3+ ordinary characters.
        // Short queries and SQL wildcard characters use the safe table-scan path.
        let useTrigram = query.count >= 3 && !query.contains("%") &&
                         !query.contains("_") && !query.contains("\\")
        let sql: String
        if useTrigram {
            sql = """
            SELECT s.path, s.title_fold, s.artist_fold, s.album_fold
            FROM songs_grams AS g JOIN songs AS s ON s.path = g.path
            WHERE g.searchable LIKE ?1
            """
        } else {
            sql = "SELECT path, title_fold, artist_fold, album_fold FROM songs"
        }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK,
              let stmt = statement else {
            throw SearchError.database(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        if useTrigram {
            let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
            let pattern = "%" + query + "%"
            guard pattern.withCString({ sqlite3_bind_text(stmt, 1, $0, -1, transient) }) == SQLITE_OK else {
                throw SearchError.database("Unable to bind optimized shadow query")
            }
        }
        var matches = Set<String>()
        while true {
            try cancellation?.check()
            let step = sqlite3_step(stmt)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else {
                throw SearchError.database(String(cString: sqlite3_errmsg(db)))
            }
            func field(_ i: Int32) -> String {
                guard let ptr = sqlite3_column_text(stmt, i) else { return "" }
                return String(cString: ptr)
            }
            // No cross-field matches: replicate title/artist/album OR conditions.
            if field(1).contains(query) || field(2).contains(query) || field(3).contains(query) {
                matches.insert(field(0))
            }
        }
        return matches
    }

    // Accept a URL explicitly, so app installation paths do not affect index location.
    // Calling code should resolve the index path on a background queue.
    static func searchSubstring(
        databaseURL: URL,
        text: String,
        limit: Int = 200
    ) throws -> [Song] {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return [] }
        guard FileManager.default.fileExists(atPath: databaseURL.path) else {
            throw SearchError.missingIndex
        }

        var handle: OpaquePointer?
        let openResult = databaseURL.path.withCString { path in
            sqlite3_open_v2(path, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil)
        }
        guard openResult == SQLITE_OK, let database = handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let handle { sqlite3_close(handle) }
            throw SearchError.database(message)
        }
        defer { sqlite3_close(database) }

        // Read-only even if the database was mistakenly prepared differently.
        guard sqlite3_db_readonly(database, "main") == 1 else {
            throw SearchError.database("Database is not read-only")
        }

        let sql = """
        SELECT path, title, artist, album
        FROM songs
        WHERE instr(lower(title), lower(?1)) > 0
           OR instr(lower(artist), lower(?1)) > 0
           OR instr(lower(album), lower(?1)) > 0
        ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE
        LIMIT ?2
        """
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK,
              let stmt = statement else {
            throw SearchError.database(String(cString: sqlite3_errmsg(database)))
        }
        defer { sqlite3_finalize(stmt) }

        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        let bindResult = query.withCString {
            sqlite3_bind_text(stmt, 1, $0, -1, transient)
        }
        guard bindResult == SQLITE_OK,
              sqlite3_bind_int(stmt, 2, Int32(max(1, min(limit, 1000)))) == SQLITE_OK else {
            throw SearchError.database("Unable to bind search parameters")
        }

        var songs: [Song] = []
        while true {
            let result = sqlite3_step(stmt)
            if result == SQLITE_DONE { break }
            guard result == SQLITE_ROW else {
                throw SearchError.database(String(cString: sqlite3_errmsg(database)))
            }
            func field(_ index: Int32) -> String {
                guard let pointer = sqlite3_column_text(stmt, index) else { return "" }
                return String(cString: pointer)
            }
            songs.append(Song(path: field(0), title: field(1), artist: field(2), album: field(3)))
        }
        return songs
    }


    // FADER_SQLITE_CACHE_SYNC_P271_V1
    // Native, background-only, isolated cache synchronization. Never touches music,
    // playlists, App Support, P261/P267 indexes or visible search results.
    private static let cacheSyncQueue = DispatchQueue(label: "fader.sqlite.cacheSync", qos: .utility)

    // FADER_P297A_PORTABLE_SQLITE
    // Store indexes in user Application Support, independent of app location.
    static var indexDirectory: URL {
        FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
        .appendingPathComponent(FaderStorage.supportComponent, isDirectory: true)
        .appendingPathComponent("SearchIndex", isDirectory: true)
    }

    static func refreshCacheSnapshotInBackground(tracks: [Track]) {
        let snapshot = tracks.map {
            Song(path: $0.url.standardizedFileURL.path,
                 title: $0.title, artist: $0.artist, album: $0.album)
        }
        // Only project builds have the expected sibling Fader_Search_Index folder.
        let indexDir = indexDirectory
        cacheSyncQueue.async {
            do {
                let result = try rebuildCacheSnapshot(snapshot, in: indexDir)
                let status = result.rebuilt ? "SUCCESS" : "UNCHANGED"
                print("FADER SQLITE SYNC P271: \(status); tracks=\(result.count); index=FaderSearch_P271_CacheSync.sqlite")
            } catch {
                print("FADER SQLITE SYNC P271: SKIPPED " + error.localizedDescription)
            }
        }
    }

    // Publishes only the dedicated P271 index, using atomic rename after integrity checks.
    // The prior valid P271 index survives every unsuccessful rebuild.
    // FADER_SQLITE_UNIFIED_SYNC_P272_V1
    // A *separate* SQLite index covering all currently loaded Global Search sources.
    // Snapshot supplied from the UI main thread; all SQLite work runs on cacheSyncQueue.
    // FADER_SQLITE_AUTOSYNC_P29121_V1
    static func refreshUnifiedSnapshotInBackground(
        tracks: [Track], completion: ((Bool) -> Void)? = nil
    ) {
        let snapshot = tracks.map {
            Song(path: $0.url.standardizedFileURL.path,
                 title: $0.title, artist: $0.artist, album: $0.album)
        }
        let indexDir = indexDirectory
        cacheSyncQueue.async {
            do {
                let result = try rebuildCacheSnapshot(
                    snapshot, in: indexDir,
                    filename: "FaderSearch_P272_UnifiedSync.sqlite",
                    schema: "P272_UNIFIED_SYNC_V1",
                    source: "Music Folders, Current Queue, folder playback, Playlists")
                let status = result.rebuilt ? "SUCCESS" : "UNCHANGED"
                print("FADER SQLITE SYNC P272: \(status); unique=\(result.count); " +
                      "sources=folders+queue+playlists; index=FaderSearch_P272_UnifiedSync.sqlite")
                DispatchQueue.main.async { completion?(true) }
            } catch {
                print("FADER SQLITE SYNC P272: SKIPPED " + error.localizedDescription)
                DispatchQueue.main.async { completion?(false) }
            }
        }
    }

    /// Full validation belongs to source publication, never the keystroke path.
    static func prepareUnifiedIndex(tracks: [Track], in directory: URL) throws -> URL {
        let snapshot = tracks.map { Song(path: $0.normalizedPath, title: $0.title, artist: $0.artist, album: $0.album) }
        return try cacheSyncQueue.sync {
            _ = try rebuildCacheSnapshot(snapshot, in: directory,
                filename: "FaderSearch_P272_UnifiedSync.sqlite", schema: "P272_UNIFIED_SYNC_V1",
                source: "Music Folders, Current Queue, Playlists")
            return directory.appendingPathComponent("FaderSearch_P272_UnifiedSync.sqlite")
        }
    }

    // FADER_SQLITE_SMART_SYNC_P273_V1
    // Fingerprint of the same folded fields used to build the search index.
    // Include all paths and metadata, not just row count or file timestamps.
    private struct RefreshResult {
        let count: Int
        let rebuilt: Bool
    }

    private static func contentFingerprint(_ songs: [String: Song], schema: String) -> String {
        let locale = Locale(identifier: "en_US_POSIX")
        var hasher = SHA256()
        func add(_ value: String) {
            let bytes = Data(value.utf8)
            hasher.update(data: Data("\(bytes.count):".utf8))
            hasher.update(data: bytes)
        }
        add("FADER_P273_CONTENT_V1")
        add(schema)
        for path in songs.keys.sorted() {
            guard let song = songs[path] else { continue }
            add(path)
            add(song.title.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale))
            add(song.artist.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale))
            add(song.album.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // Do not trust a matching hash alone: verify schema, row counts and integrity
    // from a read-only handle. If anything is missing or corrupt, rebuild safely.
    private static func validatedFingerprintMatches(
        at url: URL, schema: String, count: Int, fingerprint: String
    ) -> Bool {
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        var raw: OpaquePointer?
        let opened = url.path.withCString {
            sqlite3_open_v2($0, &raw, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX, nil)
        }
        guard opened == SQLITE_OK, let db = raw else {
            if let raw { sqlite3_close(raw) }
            return false
        }
        defer { sqlite3_close(db) }
        guard sqlite3_db_readonly(db, "main") == 1 else { return false }

        func singleValue(_ sql: String) -> String? {
            var statement: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK,
                  let stmt = statement else { return nil }
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_step(stmt) == SQLITE_ROW,
                  let bytes = sqlite3_column_text(stmt, 0) else { return nil }
            return String(cString: bytes)
        }
        guard singleValue("SELECT value FROM metadata WHERE key='schema_version'") == schema,
              singleValue("SELECT value FROM metadata WHERE key='content_sha256'") == fingerprint,
              singleValue("SELECT value FROM metadata WHERE key='song_count'") == String(count),
              singleValue("SELECT count(*) FROM songs") == String(count),
              singleValue("SELECT count(*) FROM songs_grams") == String(count),
              singleValue("PRAGMA quick_check") == "ok" else { return false }
        return true
    }

    private static func rebuildCacheSnapshot(
        _ tracks: [Song], in directory: URL,
        filename: String = "FaderSearch_P271_CacheSync.sqlite",
        schema: String = "P271_CACHE_SYNC_V1",
        source: String = "Loaded Music Folders caches"
    ) throws -> RefreshResult {
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        var unique: [String: Song] = [:]
        for track in tracks where !track.path.isEmpty { unique[track.path] = track }
        guard !unique.isEmpty else { throw SearchError.database("Refusing empty cache index") }

        // Whitelist both project-local indexes. Never replace an experimental index.
        guard filename == "FaderSearch_P271_CacheSync.sqlite" ||
              filename == "FaderSearch_P272_UnifiedSync.sqlite" else {
            throw SearchError.database("Unexpected SQLite index filename")
        }
        let destination = directory.appendingPathComponent(filename)
        let fingerprint = contentFingerprint(unique, schema: schema)
        if validatedFingerprintMatches(at: destination, schema: schema,
                                       count: unique.count, fingerprint: fingerprint) {
            return RefreshResult(count: unique.count, rebuilt: false)
        }
        let temporary = directory.appendingPathComponent(".Fader_P271_" + UUID().uuidString + ".sqlite")
        defer { try? FileManager.default.removeItem(at: temporary) }

        var raw: OpaquePointer?
        let opened = temporary.path.withCString {
            sqlite3_open_v2($0, &raw, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_EXCLUSIVE, nil)
        }
        guard opened == SQLITE_OK, let db = raw else {
            let detail = raw.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let raw { sqlite3_close(raw) }
            throw SearchError.database(detail)
        }
        var closed = false
        defer { if !closed { sqlite3_close(db) } }
        func exec(_ sql: String) throws {
            let result = sqlite3_exec(db, sql, nil, nil, nil)
            guard result == SQLITE_OK else {
                throw SearchError.database(String(cString: sqlite3_errmsg(db)))
            }
        }
        try exec("PRAGMA journal_mode=DELETE")
        try exec("PRAGMA synchronous=FULL")
        try exec("CREATE TABLE songs(path TEXT PRIMARY KEY, title_fold TEXT NOT NULL, artist_fold TEXT NOT NULL, album_fold TEXT NOT NULL) WITHOUT ROWID")
        try exec("CREATE VIRTUAL TABLE songs_grams USING fts5(path UNINDEXED, searchable, tokenize='trigram')")
        try exec("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")

        let insertSong = "INSERT INTO songs(path,title_fold,artist_fold,album_fold) VALUES (?1,?2,?3,?4)"
        let insertGram = "INSERT INTO songs_grams(path,searchable) VALUES (?1,?2)"
        var songStmt: OpaquePointer?
        var gramStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, insertSong, -1, &songStmt, nil) == SQLITE_OK,
              sqlite3_prepare_v2(db, insertGram, -1, &gramStmt, nil) == SQLITE_OK,
              let song = songStmt, let gram = gramStmt else {
            if let songStmt { sqlite3_finalize(songStmt) }
            if let gramStmt { sqlite3_finalize(gramStmt) }
            throw SearchError.database("Failed preparing index statements")
        }
        var finalized = false
        defer { if !finalized { sqlite3_finalize(song); sqlite3_finalize(gram) } }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        func bind(_ value: String, at index: Int32, to stmt: OpaquePointer) throws {
            let code = value.withCString { sqlite3_bind_text(stmt, index, $0, -1, transient) }
            guard code == SQLITE_OK else { throw SearchError.database("SQLite bind failed") }
        }
        let locale = Locale(identifier: "en_US_POSIX")
        func fold(_ value: String) -> String {
            value.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale)
        }
        try exec("BEGIN IMMEDIATE")
        do {
            for key in unique.keys.sorted() {
                guard let record = unique[key] else { continue }
                let title = fold(record.title)
                let artist = fold(record.artist)
                let album = fold(record.album)
                sqlite3_reset(song); sqlite3_clear_bindings(song)
                try bind(key, at: 1, to: song)
                try bind(title, at: 2, to: song)
                try bind(artist, at: 3, to: song)
                try bind(album, at: 4, to: song)
                guard sqlite3_step(song) == SQLITE_DONE else {
                    throw SearchError.database("Song insertion failed")
                }
                sqlite3_reset(gram); sqlite3_clear_bindings(gram)
                try bind(key, at: 1, to: gram)
                try bind([title, artist, album].joined(separator: " "), at: 2, to: gram)
                guard sqlite3_step(gram) == SQLITE_DONE else {
                    throw SearchError.database("Trigram insertion failed")
                }
            }
            // schema/source are fixed strings from the two known call sites.
            let allowedSchemas = ["P271_CACHE_SYNC_V1", "P272_UNIFIED_SYNC_V1"]
            guard allowedSchemas.contains(schema), !source.contains("'") else {
                throw SearchError.database("Unexpected index metadata")
            }
            try exec("INSERT INTO metadata(key,value) VALUES ('schema_version','\(schema)')")
            try exec("INSERT INTO metadata(key,value) VALUES ('source','\(source)')")
            try exec("INSERT INTO metadata(key,value) VALUES ('song_count','\(unique.count)')")
            try exec("INSERT INTO metadata(key,value) VALUES ('content_sha256','\(fingerprint)')")
            try exec("COMMIT")
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
        // Validation before publication: integrity AND both expected table counts.
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "PRAGMA integrity_check", -1, &stmt, nil) == SQLITE_OK,
              let integrity = stmt else {
            throw SearchError.database("Cannot run integrity check")
        }
        let integrityOK = sqlite3_step(integrity) == SQLITE_ROW &&
            sqlite3_column_text(integrity, 0).map { String(cString: $0) } == "ok"
        sqlite3_finalize(integrity)
        guard integrityOK else { throw SearchError.database("SQLite integrity check failed") }
        for table in ["songs", "songs_grams"] {
            let sql = "SELECT count(*) FROM " + table
            var countStmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, sql, -1, &countStmt, nil) == SQLITE_OK,
                  let counter = countStmt else {
                throw SearchError.database("Cannot count " + table)
            }
            let correct = sqlite3_step(counter) == SQLITE_ROW &&
                Int(sqlite3_column_int64(counter, 0)) == unique.count
            sqlite3_finalize(counter)
            guard correct else { throw SearchError.database("Index count mismatch " + table) }
        }
        sqlite3_finalize(song)
        sqlite3_finalize(gram)
        finalized = true
        guard sqlite3_close(db) == SQLITE_OK else {
            throw SearchError.database("Cannot close temporary index safely")
        }
        closed = true
        // POSIX rename is atomic when source and target share this same directory.
        // It replaces ONLY the dedicated P271 filename, never other indexes.
        let renameCode = temporary.path.withCString { source in
            destination.path.withCString { target in Darwin.rename(source, target) }
        }
        guard renameCode == 0 else { throw SearchError.database("Unable to publish SQLite sync index") }
        return RefreshResult(count: unique.count, rebuilt: true)
    }

}
