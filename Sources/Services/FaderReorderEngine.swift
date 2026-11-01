import Foundation

// FADER_P299B_REORDER_ENGINE
// Pure data transformation. No UI, player, disk or audio operations.
enum FaderReorderEngine {

    // The insertion slot belongs to the ORIGINAL array:
    // 0 = before first element
    // count = after last element
    static func reorder(
        _ ids: [String],
        moving sourceID: String,
        toInsertionSlot slot: Int
    ) -> [String]? {

        guard slot >= 0, slot <= ids.count else {
            return nil
        }

        // IDs must be unique and the source must exist exactly once.
        guard Set(ids).count == ids.count,
              let sourceIndex = ids.firstIndex(of: sourceID) else {
            return nil
        }

        var result = ids
        result.remove(at: sourceIndex)

        let insertionIndex = slot - (sourceIndex < slot ? 1 : 0)

        guard insertionIndex >= 0,
              insertionIndex <= result.count else {
            return nil
        }

        result.insert(sourceID, at: insertionIndex)
        return result
    }
}
