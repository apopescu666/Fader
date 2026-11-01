import Foundation
import ImageIO

/// Reads native FLAC PICTURE metadata without reading or changing audio frames.
enum FLACArtworkReader {
    static func extractArtwork(from url: URL) -> Data? {
        guard let file = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? file.close() }
        do {
            let fileSize = try file.seekToEnd()
            try file.seek(toOffset: 0)
            guard try file.read(upToCount: 4) == Data("fLaC".utf8) else { return nil }
            var offset: UInt64 = 4
            var fallback: Data?
            // Bound traversal of malformed or unusually large metadata sections.
            for _ in 0..<1024 {
                guard offset <= fileSize, fileSize - offset >= 4,
                      let header = try file.read(upToCount: 4), header.count == 4 else {
                    return fallback
                }
                let last = header[0] & 0x80 != 0
                let type = header[0] & 0x7f
                let length = (Int(header[1]) << 16) | (Int(header[2]) << 8) | Int(header[3])
                offset += 4
                guard type != 127, UInt64(length) <= fileSize - offset,
                      offset + UInt64(length) <= 64 * 1024 * 1024 else { return fallback }
                if type == 6 {
                    guard let data = try file.read(upToCount: length), data.count == length else {
                        return fallback
                    }
                    if let picture = picture(in: data) {
                        if picture.type == 3 { return picture.bytes } // Front cover.
                        if fallback == nil { fallback = picture.bytes }
                    }
                } else {
                    try file.seek(toOffset: offset + UInt64(length))
                }
                offset += UInt64(length)
                if last { return fallback }
            }
            return fallback
        } catch {
            return nil
        }
    }

    private static func picture(in data: Data) -> (type: UInt32, bytes: Data)? {
        var cursor = Cursor(data: data)
        guard let type = cursor.uint32(),
              let mimeLength = cursor.uint32(),
              let mime = cursor.bytes(Int(mimeLength)),
              String(data: mime, encoding: .utf8) != "-->", // External URL, never fetched.
              let descriptionLength = cursor.uint32(),
              cursor.skip(Int(descriptionLength)),
              cursor.skip(16), // Width, height, depth, indexed palette size.
              let imageLength = cursor.uint32(), imageLength > 0,
              let image = cursor.bytes(Int(imageLength)),
              let source = CGImageSourceCreateWithData(image as CFData, nil),
              CGImageSourceGetCount(source) > 0 else { return nil }
        return (type, image)
    }

    private struct Cursor {
        let data: Data
        var offset = 0

        mutating func uint32() -> UInt32? {
            guard let value = bytes(4) else { return nil }
            return value.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        }

        mutating func skip(_ count: Int) -> Bool {
            guard count >= 0, count <= data.count - offset else { return false }
            offset += count
            return true
        }

        mutating func bytes(_ count: Int) -> Data? {
            guard count >= 0, count <= data.count - offset else { return nil }
            let start = offset
            offset += count
            return data.subdata(in: start..<offset)
        }
    }
}
