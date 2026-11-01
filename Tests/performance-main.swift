import Foundation
import AppKit
import CryptoKit

@main
struct PerformanceTests {
    static func measure(_ name: String, _ work: () -> Int) -> (Double, Int) {
        let start = ProcessInfo.processInfo.systemUptime
        let result = autoreleasepool(invoking: work)
        let ms = (ProcessInfo.processInfo.systemUptime - start) * 1_000
        print(String(format: "%@: %.2f ms (checksum %d)", name, ms, result))
        return (ms, result)
    }

    static func main() throws {
        // No music files or application preferences are read/written by this test.
        let tracks = (0..<60_000).map { index in
            Track(url: URL(fileURLWithPath: "/Synthetic/Music/Album/../Album/\(index).flac"),
                  title: "Title \(index)", artist: "Artist \(index % 200)", album: "Album \(index % 500)")
        }
        for query in ["", "Artist 12", "ALBUM 45", " ", "Title 100 Artist", "absent", "é"] {
            let old = tracks.enumerated().compactMap { index, track -> Int? in
                let text = "\(track.title) \(track.artist) \(track.album)"
                return query.isEmpty || text.localizedCaseInsensitiveContains(query) ? index : nil
            }
            let projected = FaderTrackListing.entries(in: tracks, query: query)
            precondition(old == projected.map(\.index), "Search semantics changed: \(query)")
            precondition(projected.allSatisfy { tracks[$0.index].id == $0.track.id })
        }
        precondition(FaderTrackListing.affectedRows(in: ["id-a", "id-b", "id-c"],
            previous: "id-a", current: "id-c") == IndexSet([0, 2]))
        precondition(FaderTrackListing.affectedRows(in: ["/a", "/a", "/b"],
            previous: nil, current: "/a") == IndexSet([0, 1]))
        precondition(FaderTrackListing.affectedRows(in: ["/a"],
            previous: "/a", current: nil) == IndexSet([0]))
        precondition(FaderTrackListing.affectedRows(in: ["/a"],
            previous: nil, current: nil).isEmpty)
        let duplicates = [tracks[0], tracks[0], tracks[1]]
        precondition(FaderTrackListing.entries(in: duplicates, query: "").map(\.id) == [0, 1, 2])
        precondition(FaderTrackListing.entries(in: [], query: "").isEmpty)
        var moved = tracks[0]
        moved.url = URL(fileURLWithPath: "/Changed/../New/song.flac")
        precondition(moved.normalizedPath == moved.url.standardizedFileURL.path)
        let originalIdentity = moved.artworkIdentity
        moved.artworkData = Data([1, 2, 3])
        precondition(originalIdentity != moved.artworkIdentity)
        let revisedIdentity = moved.artworkIdentity
        moved.artworkData = Data([3, 2, 1]) // equal size must still invalidate the image
        precondition(revisedIdentity != moved.artworkIdentity)
        let copy = moved
        precondition(copy.artworkIdentity == moved.artworkIdentity)

        func oldProjection() -> [(index: Int, track: Track)] {
            tracks.enumerated().compactMap { index, track in
                let text = "\(track.title) \(track.artist) \(track.album)"
                return text.isEmpty ? nil : (index, track)
            }
        }
        let oldRows = measure("60k: old list evaluation, count + 20 rows/separators") {
            var checksum = oldProjection().count
            for position in 0..<20 {
                checksum += oldProjection()[position].index
                checksum += oldProjection().count
            }
            return checksum
        }
        let newRows = measure("60k: snapshot once, count + same 20 rows/separators") {
            let entries = FaderTrackListing.entries(in: tracks, query: "")
            var checksum = entries.count
            for position in 0..<20 {
                checksum += entries[position].index
                checksum += entries.count
            }
            return checksum
        }
        precondition(oldRows.1 == newRows.1)
        let oldPaths = measure("60k: repeated URL standardization") {
            tracks.reduce(0) { $0 + $1.url.standardizedFileURL.path.utf8.count }
        }
        let newPaths = measure("60k: cached equivalent paths") {
            tracks.reduce(0) { $0 + $1.normalizedPath.utf8.count }
        }
        precondition(oldPaths.1 == newPaths.1)
        var lazy = tracks[0]
        lazy.experimentalArtworkDirectory = URL(fileURLWithPath: "/nonexistent-fader-test-directory")
        lazy.experimentalArtworkSHA256 = String(repeating: "a", count: 64)
        var hash = Hasher()
        FaderTrackListing.hashDisplay(lazy, into: &hash)
        precondition(lazy.embeddedArtworkData == nil)

        let fixture = URL(fileURLWithPath: CommandLine.arguments[1]).appendingPathComponent("cover.jpg")
        let art = try Data(contentsOf: fixture)
        var cover = tracks[0]
        cover.artworkData = art
        let image = FaderArtworkImageCache.image(for: cover)
        precondition(image != nil)
        for _ in 0..<100 {
            precondition(FaderArtworkImageCache.image(for: cover) === image,
                         "Identical row updates should reuse the decoded cover")
        }
        cover.artworkData = Data([1, 2, 3])
        precondition(FaderArtworkImageCache.image(for: cover) == nil,
                     "A changed invalid cover must not display a stale image")
        print(String(format: "Measured list projection speedup %.1fx; path lookup %.1fx", oldRows.0 / max(newRows.0, 0.001), oldPaths.0 / max(newPaths.0, 0.001)))
        print("PASS: 60,000 tracks, exact search parity, duplicate indices, path mutation, cover invalidation and decoded-image reuse")
    }
}
