import Foundation
@main struct ProfileTests {
    static func main() throws {
        let folder = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
        let defaults = FaderStorage.defaults
        let playlist = Data("[{\"name\":\"Synthetic persistence test\",\"trackPaths\":[]}]".utf8)
        if CommandLine.arguments[1] == "write" {
            precondition(!FileManager.default.fileExists(atPath: FaderStorage.directory.path))
            defaults.set([folder.path], forKey: "Fader.savedMusicFolders")
            defaults.set(playlist, forKey: "Fader.savedPlaylists")
            precondition(defaults.synchronize())
            var saved = false
            FolderCacheStore.save(Library.scan(folder: folder), for: folder) { saved = $0 }
            let deadline = Date().addingTimeInterval(15)
            while !saved && Date() < deadline {
                _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01))
            }
            precondition(saved)
            print("PASS: saved synthetic folders, playlist data and disk cache in isolated profile")
        } else {
            precondition(defaults.stringArray(forKey: "Fader.savedMusicFolders") == [folder.path])
            precondition(defaults.data(forKey: "Fader.savedPlaylists") == playlist)
            precondition(FolderCacheStore.load(for: folder)?.count == 7)
            print("PASS: NEW PROCESS restored same folder list, playlist data and seven cached tracks")
        }
    }
}
