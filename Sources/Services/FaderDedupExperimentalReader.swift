// FADER_P29134D4_EXPERIMENTAL_READER
// Experimental only: not called by the Fader app or the existing FolderCacheStore.
// Does NOT open audio files. Reads a selected prototype directory on explicit call.
import Foundation
import CryptoKit

enum FaderDedupExperimentalReader {
    struct CatalogRow: Decodable {
        let folderPath: String
        let track: Metadata
    }

    struct Metadata: Decodable {
        let path: String
        let title: String
        let artist: String
        let album: String
        let duration: Double
        let format: String
        let fileSize: Int64?
        let modificationDate: Double?
        let artworkSHA256: String?
    }

    enum ReaderError: Error {
        case missingCatalog
        case invalidLine
        case tooManyRows
        case oversizedLine
        case invalidArtworkIdentifier
        case invalidArtwork
    }

    // Bounded, read-only, streaming JSONL parser. No filesystem enumeration or audio access.
    // Caller deliberately supplies the prototype path; no automatic discovery or activation.
    static func readMetadata(from prototypeDirectory: URL, maxRows: Int = 100_000) throws -> [CatalogRow] {
        let catalog = prototypeDirectory.appendingPathComponent("metadata.jsonl")
        guard FileManager.default.fileExists(atPath: catalog.path) else { throw ReaderError.missingCatalog }
        let handle = try FileHandle(forReadingFrom: catalog)
        defer { try? handle.close() }
        var rows: [CatalogRow] = []
        var pending = Data()
        let decoder = JSONDecoder()

        func parseLine(_ raw: Data) throws {
            guard !raw.isEmpty else { return }
            guard raw.count <= 4_000_000 else { throw ReaderError.oversizedLine }
            let row = try decoder.decode(CatalogRow.self, from: raw)
            guard !row.folderPath.isEmpty && !row.track.path.isEmpty else { throw ReaderError.invalidLine }
            if let hash = row.track.artworkSHA256 {
                let valid = hash.utf8.count == 64 && hash.utf8.allSatisfy {
                    (48...57).contains($0) || (97...102).contains($0)
                }
                guard valid else { throw ReaderError.invalidArtworkIdentifier }
            }
            guard rows.count < maxRows else { throw ReaderError.tooManyRows }
            rows.append(row)
        }

        while true {
            let block = try handle.read(upToCount: 64 * 1024) ?? Data()
            if block.isEmpty { break }
            pending.append(block)
            while let newline = pending.firstIndex(of: 10) {
                try parseLine(pending.subdata(in: pending.startIndex..<newline))
                pending.removeSubrange(pending.startIndex...newline)
            }
            guard pending.count <= 4_000_000 else { throw ReaderError.oversizedLine }
        }
        try parseLine(pending)
        return rows
    }

    // Lazy artwork, called only for a chosen hash. Verifies content before returning bytes.
    static func readArtwork(sha256: String, from prototypeDirectory: URL) throws -> Data {
        let valid = sha256.utf8.count == 64 && sha256.utf8.allSatisfy {
            (48...57).contains($0) || (97...102).contains($0)
        }
        guard valid else { throw ReaderError.invalidArtworkIdentifier }
        let url = prototypeDirectory.appendingPathComponent("artwork", isDirectory: true)
            .appendingPathComponent(sha256 + ".bin")
        let bytes = try Data(contentsOf: url)
        let actual = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard actual == sha256 else { throw ReaderError.invalidArtwork }
        return bytes
    }
}
