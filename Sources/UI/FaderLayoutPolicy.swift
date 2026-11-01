import Foundation

/// Pure layout calculations shared by native split views and regression tests.
enum FaderLayoutPolicy {
    static func position(length: CGFloat, divider: CGFloat, minimumFirst: CGFloat,
                         minimumSecond: CGFloat, fraction: CGFloat) -> CGFloat {
        let available = max(0, length - divider)
        guard available > 0 else { return 0 }
        let totalMinimum = minimumFirst + minimumSecond
        guard available >= totalMinimum else {
            return available * minimumFirst / max(1, totalMinimum)
        }
        let safeFraction = fraction.isFinite ? min(max(fraction, 0), 1) : 0.5
        return min(max(available * safeFraction, minimumFirst), available - minimumSecond)
    }
}
