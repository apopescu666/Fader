import Foundation
import CryptoKit

func check(_ value: @autoclosure () -> Bool, _ message: String) {
    if !value() { fatalError(message) }
}
func signature(_ track: Track) -> String {
    [track.url.path, track.title, track.artist, track.album, track.format,
     String(track.duration), track.artworkData.map { SHA256.hash(data: $0).description } ?? ""].joined(separator: "|")
}
@main struct ImportTest {
    static func main() throws {
        let folder = URL(fileURLWithPath: CommandLine.arguments[1])
        check(!FileManager.default.fileExists(atPath: FaderStorage.directory.path), "Profile was not empty")
        check(FaderStorage.defaults.stringArray(forKey: "Fader.musicFolders") == nil, "Inherited library preferences")
        check(!FaderStorage.directory.path.contains("FaderPlayer/"), "Production profile path")
        let expected = Library.scan(folder: folder).map(signature)
        var last = 0
        let lock = NSLock()
        let actual = try FaderInitialFolderImport.scan(folder: folder) { progress in
            lock.lock(); defer { lock.unlock() }
            last = max(last, progress.completed)
            check(progress.completed <= progress.total, "Invalid progress")
        }
        check(expected == actual.map(signature), "Metadata/order/artwork parity failed")
        check(last == actual.count, "Final progress missing")
        do {
            _ = try FaderInitialFolderImport.scan(folder: folder.appendingPathComponent("missing-directory")) { _ in }
            fatalError("Missing folder accepted")
        } catch { print("PASS: missing folder rejected") }
        print("PASS: clean namespace, metadata/duration/artwork/order parity (\(actual.count) tracks), progress")
        print("PROFILE: \(FaderStorage.token)")
    }
}
