import Foundation
import ImageIO
let folder = URL(fileURLWithPath: CommandLine.arguments[1])
let mp3 = Library.makeTrack(url: folder.appendingPathComponent("tagged.mp3"))
precondition(mp3.title == "Titlu Știință" && mp3.artist == "Artist Test" && mp3.album == "Album Test")
precondition(mp3.duration > 0.2 && mp3.duration < 0.6)
let m4a = Library.makeTrack(url: folder.appendingPathComponent("tagged.m4a"))
precondition(m4a.title == "M4A Title" && m4a.artist == "M4A Artist" && m4a.album == "M4A Album")
let flac = Library.makeTrack(url: folder.appendingPathComponent("tagged.flac"))
precondition(flac.title == "FLAC Știință" && flac.artist == "FLAC Artist" && flac.album == "FLAC Album")
precondition(flac.duration > 0.2 && flac.duration < 0.6 && flac.format == "FLAC")
precondition(flac.artworkData != nil)
precondition(CGImageSourceCreateWithData(flac.artworkData! as CFData, nil) != nil)
let originalFLAC = try Data(contentsOf: folder.appendingPathComponent("tagged.flac"))
let jpegFLAC = Library.makeTrack(url: folder.appendingPathComponent("jpeg-cover.flac"))
precondition(jpegFLAC.artworkData != nil && jpegFLAC.title == "JPEG FLAC")
let noCover = Library.makeTrack(url: folder.appendingPathComponent("no-cover.flac"))
precondition(noCover.artworkData == nil && noCover.duration > 0.2)
let coveredMP3 = Library.makeTrack(url: folder.appendingPathComponent("covered.mp3"))
precondition(coveredMP3.artworkData != nil)
let afterFLAC = try Data(contentsOf: folder.appendingPathComponent("tagged.flac"))
precondition(afterFLAC == originalFLAC)
print("PASS: FLAC PNG/JPEG covers, FLAC without cover, MP3 ID3 artwork and unchanged original FLAC bytes")
let wav = Library.makeTrack(url: folder.appendingPathComponent("Fallback Artist - Fallback Title.wav"))
precondition(wav.title == "Fallback Title" && wav.artist == "Fallback Artist" && wav.album.isEmpty)
let missing = Library.makeTrack(url: folder.appendingPathComponent("Missing Artist - Missing Title.mp3"))
precondition(missing.title == "Missing Title" && missing.artist == "Missing Artist" && missing.duration == 0)
DispatchQueue.concurrentPerform(iterations: 8) { _ in
    let track = Library.makeTrack(url: folder.appendingPathComponent("tagged.mp3"))
    precondition(track.title == "Titlu Știință" && track.artist == "Artist Test")
}
let scanned = Library.scan(folder: folder)
precondition(scanned.count == 7)
let playlist = folder.appendingPathComponent("test.m3u8")
try "#EXTM3U\ntagged.mp3\ntagged.m4a\ntagged.mp3\n".write(to: playlist, atomically: true, encoding: .utf8)
let imported = Library.loadPlaylist(url: playlist)
precondition(imported.count == 2 && imported[0].title == "Titlu Știință" && imported[1].album == "M4A Album")
print("PASS: MP3, M4A and FLAC tags, Unicode trimming, duration, filename fallback, missing file, 8 concurrent imports, scan and playlist dedup")

// Metadata-only parser fixtures exercise malformed input without decoding audio.
let cases = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: cases, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: cases) }
let png = FLACArtworkReader.extractArtwork(from: folder.appendingPathComponent("tagged.flac"))!
let jpeg = FLACArtworkReader.extractArtwork(from: folder.appendingPathComponent("jpeg-cover.flac"))!
func u32(_ n: UInt32) -> Data { Data([UInt8(truncatingIfNeeded: n >> 24), UInt8(truncatingIfNeeded: n >> 16), UInt8(truncatingIfNeeded: n >> 8), UInt8(truncatingIfNeeded: n)]) }
func picture(_ bytes: Data, type: UInt32 = 3, mime: String = "image/png") -> Data {
    u32(type) + u32(UInt32(mime.utf8.count)) + Data(mime.utf8) + u32(0) + Data(repeating: 0, count: 16) + u32(UInt32(bytes.count)) + bytes
}
func block(_ data: Data, type: UInt8 = 6, last: Bool = true) -> Data {
    let n = data.count
    return Data([type | (last ? 0x80 : 0), UInt8(truncatingIfNeeded: n >> 16), UInt8(truncatingIfNeeded: n >> 8), UInt8(truncatingIfNeeded: n)]) + data
}
let magic = Data("fLaC".utf8)
func check(_ name: String, _ data: Data, _ expected: Data?) throws {
    let file = cases.appendingPathComponent(name)
    try data.write(to: file)
    precondition(FLACArtworkReader.extractArtwork(from: file) == expected, name)
    let after = try Data(contentsOf: file)
    precondition(after == data, "Parser wrote to original")
}
try check("prefer-front", magic + block(picture(jpeg, type: 4), last: false) + block(picture(png)), png)
try check("fallback-back", magic + block(picture(jpeg, type: 4)), jpeg)
try check("skip-padding", magic + block(Data(repeating: 0, count: 100), type: 1, last: false) + block(picture(png)), png)
try check("url-only", magic + block(picture(Data("https://example.invalid/cover".utf8), mime: "-->")), nil)
try check("invalid-image", magic + block(picture(Data([1,2,3]))), nil)
try check("bad-picture-then-good", magic + block(Data([0,1]), last: false) + block(picture(png)), png)
try check("oversized-mime", magic + block(u32(3) + u32(UInt32.max)), nil)
try check("oversized-description", magic + block(u32(3) + u32(0) + u32(UInt32.max)), nil)
try check("truncated-block", magic + Data([0x86, 0xff, 0xff, 0xff]), nil)
try check("invalid-block-type", magic + block(Data(), type: 127), nil)
try check("wrong-magic", Data("not FLAC".utf8), nil)
try check("no-picture", magic + block(Data(repeating: 0, count: 34), type: 0), nil)
let complete = magic + block(picture(png))
for length in [0,1,3,4,5,7,8,12,20,35,complete.count - 1] {
    try check("truncated-\(length)", Data(complete.prefix(length)), nil)
}
for i in 0..<128 {
    let bytes = Data((0..<(i * 7)).map { UInt8(truncatingIfNeeded: i * 131 + $0 * 17) })
    try check("malformed-\(i)", magic + block(bytes), nil)
}
print("PASS: front-cover priority, fallback, URL rejection, skipped blocks, truncated/oversized fields and 128 malformed pictures")
