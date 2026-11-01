import Foundation

enum FaderFinderOpenPolicy {
    enum Decision: Equatable { case active(Int), temporary }
    static func decide(path: String, activePaths: [String]) -> Decision {
        activePaths.firstIndex(of: path).map(Decision.active) ?? .temporary
    }
}
