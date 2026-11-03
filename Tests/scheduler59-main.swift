import Foundation

final class Probe: @unchecked Sendable {
    let lock = NSLock()
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    var order: [String] = []
    var active = 0
    var peak = 0
    func read(_ url: URL) -> Track {
        lock.lock(); active += 1; peak = max(peak, active)
        order.append(url.lastPathComponent); let position = order.count
        lock.unlock()
        if position <= 2 { entered.signal(); precondition(release.wait(timeout: .now() + 10) == .success) }
        Thread.sleep(forTimeInterval: 0.01)
        lock.lock(); active -= 1; lock.unlock()
        return Track(url: url, title: url.lastPathComponent, artist: "", album: "")
    }
}
@main struct SchedulerTests {
    static func main() {
        let probe = Probe()
        let scheduler = FaderImportScheduler(reader: probe.read)
        let background = URL(fileURLWithPath: "/synthetic/background")
        let selected = URL(fileURLWithPath: "/synthetic/selected")
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global().async {
            let tracks = scheduler.scan(files: (0..<12).map { background.appendingPathComponent("B\($0).mp3") }, folder: background) { _ in }
            precondition(tracks.count == 12); group.leave()
        }
        for _ in 0..<2 { precondition(probe.entered.wait(timeout: .now()+10) == .success) }
        scheduler.prefer(selected)
        let queued = DispatchSemaphore(value: 0)
        group.enter()
        DispatchQueue.global().async {
            let tracks = scheduler.scan(files: (0..<4).map { selected.appendingPathComponent("A\($0).mp3") }, folder: selected) {
                if $0.completed == 0 { queued.signal() }
            }
            precondition(tracks.count == 4); group.leave()
        }
        precondition(queued.wait(timeout: .now()+10) == .success)
        probe.release.signal(); probe.release.signal()
        precondition(group.wait(timeout: .now()+10) == .success)
        precondition(probe.peak == 2)
        precondition(probe.order.count == 16)
        precondition(probe.order[2].hasPrefix("A") && probe.order[3].hasPrefix("A"))
        precondition(scheduler.scan(files: [], folder: selected) { _ in }.isEmpty)
        print("PASS: background and selected batches both complete; selection prioritizes next file; maximum two total readers; empty batch")
    }
}
