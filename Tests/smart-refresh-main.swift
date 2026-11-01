import Foundation
import CryptoKit

@main
struct SmartRefreshTests {
    static func check(_ condition: @autoclosure () -> Bool, line: Int = #line) {
        if !condition() {
            FileHandle.standardError.write(Data("FAILED CHECK at line \(line)\n".utf8))
            exit(1)
        }
    }
    static func trace(_ value: String) { FileHandle.standardError.write(Data((value + "\n").utf8)) }

    static func main() throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1]).resolvingSymlinksInPath()
        let fixture = URL(fileURLWithPath: CommandLine.arguments[2])
        let music = directory.appendingPathComponent("Music")
        let fm = FileManager.default
        try fm.createDirectory(at: music, withIntermediateDirectories: true)
        let first = music.appendingPathComponent("first.mp3")
        try fm.copyItem(at: fixture.appendingPathComponent("tagged.mp3"), to: first)
        let initial = Library.scan(folder: music)
        check(initial.count == 1)
        var saved = false
        FolderCacheStore.save(initial, for: music) { success in saved = success }
        let deadline = Date().addingTimeInterval(20)
        while !saved && Date() < deadline {
            _ = RunLoop.main.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
        check(saved)
        trace("Stage unchanged")
        let unchanged = try FolderCacheStore.smartRefresh(for: music)
        check(unchanged.unchanged == 1 && unchanged.added == 0 && unchanged.updated == 0)
        let second = music.appendingPathComponent("second.flac")
        try fm.copyItem(at: fixture.appendingPathComponent("tagged.flac"), to: second)
        trace("Stage added")
        let added = try FolderCacheStore.smartRefresh(for: music)
        check(added.unchanged == 1 && added.added == 1 && added.updated == 0)
        try fm.removeItem(at: first)
        try fm.copyItem(at: fixture.appendingPathComponent("covered.mp3"), to: first)
        trace("Stage modified")
        let updated = try FolderCacheStore.smartRefresh(for: music)
        trace("Result updated=\(updated.updated) new=\(updated.added) unchanged=\(updated.unchanged)")
        check(updated.updated == 1 && updated.added == 1 && updated.unchanged == 0)
        trace("Stage missing")
        let cache = directory.appendingPathComponent("FolderCache")
        let json = try fm.contentsOfDirectory(at: cache, includingPropertiesForKeys: nil).first { $0.pathExtension == "json" }!
        let before = try Data(contentsOf: json)
        try fm.removeItem(at: first)
        do {
            _ = try FolderCacheStore.smartRefresh(for: music)
            preconditionFailure("Missing cached music must not silently remove library entries")
        } catch FolderCacheStore.SmartRefreshError.incompleteInventory(let count) {
            check(count == 1)
        }
        let after = try Data(contentsOf: json)
        check(after == before)
        print("PASS: Smart Refresh unchanged/new/modified metadata counts; missing file stops refresh and preserves cache; all files isolated")
    }
}
