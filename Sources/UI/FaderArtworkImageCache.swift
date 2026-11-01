import AppKit
import ImageIO

/// Shared by the visible rows and Now Playing. Identity is checked before
/// fetching bytes, so cache hits do not reopen or rehash artwork files.
enum FaderArtworkImageCache {
    private static let images: NSCache<NSString, NSImage> = {
        let cache = NSCache<NSString, NSImage>()
        cache.countLimit = 256
        cache.totalCostLimit = 32 * 1024 * 1024
        return cache
    }()

    static func image(for track: Track) -> NSImage? {
        let key = track.artworkIdentity as NSString
        if let image = images.object(forKey: key) { return image }
        guard let data = track.artworkData, !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData,
                  [kCGImageSourceShouldCache: false] as CFDictionary),
              let bitmap = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceShouldCacheImmediately: true,
                  kCGImageSourceThumbnailMaxPixelSize: 256
              ] as CFDictionary) else { return nil }
        let image = NSImage(cgImage: bitmap,
            size: NSSize(width: bitmap.width, height: bitmap.height))
        images.setObject(image, forKey: key,
                         cost: bitmap.bytesPerRow * bitmap.height)
        return image
    }
}
