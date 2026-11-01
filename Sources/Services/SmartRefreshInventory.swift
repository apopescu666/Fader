import Foundation

// P2.9.4.4c — Read-only inventory for Smart Refresh.
// No changes to playback, UI, playlists or persistent cache.
enum SmartRefreshInventory {

    struct FileInfo {
        let url: URL
        let size: Int64
        let modificationDate: Date
    }

    struct Result {
        let files: [String: FileInfo]
        let duplicatesSkipped: Int
        let elapsed: TimeInterval
    }

    enum InventoryError: LocalizedError {
        case unavailable
        case enumerationFailed(String)
        case metadataUnavailable(String)

        var errorDescription: String? {
            switch self {
            case .unavailable:
                return "Music folder is unavailable."
            case .enumerationFailed(let details):
                return "Folder enumeration failed: " + details
            case .metadataUnavailable(let path):
                return "File attributes unavailable: " + path
            }
        }
    }

    static func collect(folder: URL) throws -> Result {
        let started = ProcessInfo.processInfo.systemUptime
        let fm = FileManager.default

        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: folder.path,
                            isDirectory: &isDirectory),
              isDirectory.boolValue else {
            throw InventoryError.unavailable
        }

        var enumerationError: Error?

        guard let enumerator = fm.enumerator(
            at: folder,
            includingPropertiesForKeys: [
                .isRegularFileKey,
                .fileSizeKey,
                .contentModificationDateKey
            ],
            options: [.skipsHiddenFiles],
            errorHandler: { _, error in
                enumerationError = error
                return false
            }
        ) else {
            throw InventoryError.unavailable
        }

        var files: [String: FileInfo] = [:]
        var duplicates = 0

        for case let url as URL in enumerator {
            if let error = enumerationError {
                throw InventoryError.enumerationFailed(
                    error.localizedDescription
                )
            }

            guard Library.audioExtensions.contains(
                url.pathExtension.lowercased()
            ) else {
                continue
            }

            let path = url.standardizedFileURL.path

            if files[path] != nil {
                duplicates += 1
                continue
            }

            let values: URLResourceValues
            do {
                values = try url.resourceValues(forKeys: [
                    .isRegularFileKey,
                    .fileSizeKey,
                    .contentModificationDateKey
                ])
            } catch {
                throw InventoryError.metadataUnavailable(path)
            }

            guard values.isRegularFile == true,
                  let size = values.fileSize,
                  size >= 0,
                  let modified = values.contentModificationDate
            else {
                throw InventoryError.metadataUnavailable(path)
            }

            files[path] = FileInfo(
                url: url,
                size: Int64(size),
                modificationDate: modified
            )
        }

        if let error = enumerationError {
            throw InventoryError.enumerationFailed(
                error.localizedDescription
            )
        }

        let elapsed = ProcessInfo.processInfo.systemUptime - started

        return Result(
            files: files,
            duplicatesSkipped: duplicates,
            elapsed: elapsed
        )
    }
}
