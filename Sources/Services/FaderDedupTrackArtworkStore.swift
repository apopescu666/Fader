// FADER_P29134D15_DORMANT_TRACK_ARTWORK_BRIDGE
// Only used if an experimental Track has SHA and prototype directory explicitly set.
// No original audio file reads. No writes, deletions or UI changes.
import Foundation

private final class FaderDedupArtworkBytes: NSObject {
    let value: NSData
    init(_ data: Data) { self.value = data as NSData }
}

enum FaderDedupTrackArtworkStore {
    private static let cache: NSCache<NSString, FaderDedupArtworkBytes> = {
        let c = NSCache<NSString, FaderDedupArtworkBytes>()
        c.countLimit = 250
        c.totalCostLimit = 64 * 1024 * 1024
        return c
    }()

    static func data(sha256: String, prototypeDirectory: URL) -> Data? {
        let key = (prototypeDirectory.standardizedFileURL.path + ":" + sha256) as NSString
        if let cached = cache.object(forKey: key) { return cached.value as Data }
        guard let bytes = try? FaderDedupExperimentalReader.readArtwork(
            sha256: sha256, from: prototypeDirectory
        ) else { return nil }
        cache.setObject(FaderDedupArtworkBytes(bytes), forKey: key, cost: max(bytes.count, 1))
        return bytes
    }
}
