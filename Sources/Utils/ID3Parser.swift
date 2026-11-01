import Foundation

// MARK: - FADER_ID3_MEMORY_FIX_V1

/// Reads embedded JPEG/PNG artwork from ID3v2.3 and ID3v2.4.
/// Reads only the ID3 tag, never the whole audio file.
class ID3Parser {

    static func extractArtwork(from url: URL) -> Data? {
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            return nil
        }
        defer { try? handle.close() }

        let header = handle.readData(ofLength: 10)

        guard header.count == 10,
              header[0] == 0x49,
              header[1] == 0x44,
              header[2] == 0x33 else {
            return nil
        }

        let version = Int(header[3])
        guard version == 3 || version == 4 else {
            return nil
        }

        let tagSize =
            (Int(header[6] & 0x7F) << 21) |
            (Int(header[7] & 0x7F) << 14) |
            (Int(header[8] & 0x7F) << 7) |
            Int(header[9] & 0x7F)

        // Protect against exceptionally large or corrupt tags.
        guard tagSize > 0, tagSize <= 32 * 1024 * 1024 else {
            return nil
        }

        let tag = handle.readData(ofLength: tagSize)
        guard tag.count == tagSize else { return nil }

        var offset = 0

        while offset + 10 <= tag.count {
            let idBytes = tag[offset..<(offset + 4)]

            // Padding or invalid frame.
            guard idBytes.allSatisfy({
                (0x41...0x5A).contains(Int($0)) ||
                (0x30...0x39).contains(Int($0))
            }) else {
                break
            }

            let frameID = String(
                data: Data(idBytes),
                encoding: .ascii
            ) ?? ""

            let a = Int(tag[offset + 4])
            let b = Int(tag[offset + 5])
            let c = Int(tag[offset + 6])
            let d = Int(tag[offset + 7])

            let size: Int

            if version == 4 {
                // ID3v2.4: synchsafe frame size.
                size = ((a & 0x7F) << 21) |
                       ((b & 0x7F) << 14) |
                       ((c & 0x7F) << 7) |
                       (d & 0x7F)
            } else {
                // ID3v2.3: ordinary big-endian frame size.
                size = (a << 24) |
                       (b << 16) |
                       (c << 8) |
                       d
            }

            let payloadStart = offset + 10

            guard size > 0,
                  size <= tag.count - payloadStart else {
                break
            }

            let payloadEnd = payloadStart + size

            if frameID == "APIC" {
                if let artwork = imageFromAPIC(
                    tag,
                    start: payloadStart,
                    end: payloadEnd
                ) {
                    return artwork
                }
            }

            offset = payloadEnd
        }

        return nil
    }

    private static func imageFromAPIC(
        _ data: Data,
        start: Int,
        end: Int
    ) -> Data? {
        guard start >= 0,
              end <= data.count,
              end - start >= 8 else {
            return nil
        }

        // JPEG SOI signature.
        let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF]

        // PNG signature.
        let png: [UInt8] = [
            0x89, 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A
        ]

        for position in start..<(end - 7) {
            let remaining = end - position

            if remaining >= 3,
               Array(data[position..<(position + 3)]) == jpeg {
                return Data(data[position..<end])
            }

            if remaining >= 8,
               Array(data[position..<(position + 8)]) == png {
                return Data(data[position..<end])
            }
        }

        return nil
    }
}
