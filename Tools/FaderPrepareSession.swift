import Foundation

@main
struct FaderPrepareSession {
    static func main() {
        guard CommandLine.arguments.count == 2 else {
            FileHandle.standardError.write(Data("Usage: FaderPrepareSession /path/to/PlaybackSession.json\n".utf8))
            exit(2)
        }
        let legacy = URL(fileURLWithPath: CommandLine.arguments[1])
        if FaderCompactSessionStore.load(legacyURL: legacy) != nil {
            print("The compact session is ready.")
            return
        }
        guard FileManager.default.fileExists(atPath: legacy.path) else {
            print("There is no legacy session to convert.")
            return
        }
        print("Preparing the existing session once. The legacy file remains unchanged.")
        guard let session = PlaybackSessionStore.load(from: legacy) else {
            FileHandle.standardError.write(Data("The legacy session cannot be read. Conversion stopped; the original data is preserved.\n".utf8))
            exit(1)
        }
        let snapshot = PlaybackSessionStore.Snapshot(queue: session.queue.map { $0.makeTrack() },
            folderTracks: session.folderTracks.map { $0.makeTrack() }, isFolderPlayback: session.isFolderPlayback,
            currentIndex: session.currentIndex, shuffle: session.shuffle, repeatMode: session.repeatMode)
        do {
            let writer = FaderCompactSessionStore.CatalogWriter(legacyURL: legacy)
            try writer.save(snapshot)
            print("Done: \(snapshot.queue.count) queue entries, \(snapshot.folderTracks.count) playback list entries.")
        } catch {
            FileHandle.standardError.write(Data(("Conversion stopped: " + error.localizedDescription + "\n").utf8))
            exit(1)
        }
    }
}
