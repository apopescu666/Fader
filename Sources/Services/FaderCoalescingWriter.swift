import Foundation

/// Serial, bounded pending work: retain only the newest unwritten snapshot.
/// Used for persistence, never for audio scheduling. flush() is for shutdown.
final class FaderCoalescingWriter<Value> {
    private let queue: DispatchQueue
    private let lock = NSLock()
    private let delay: TimeInterval
    private let write: (Value) -> Void
    private var pending: Value?
    private var scheduled = false

    init(label: String, delay: TimeInterval = 0.15, write: @escaping (Value) -> Void) {
        self.queue = DispatchQueue(label: label, qos: .utility)
        self.delay = delay
        self.write = write
    }

    func submit(_ value: Value) {
        lock.lock()
        pending = value
        let needsSchedule = !scheduled
        scheduled = true
        lock.unlock()
        if needsSchedule {
            queue.asyncAfter(deadline: .now() + delay) { [self] in drain() }
        }
    }

    /// Waits for an in-flight write, then commits the latest pending snapshot.
    /// Must be called outside the writer's own serial queue.
    func flush() {
        queue.sync { drain() }
    }

    private func drain() {
        while true {
            lock.lock()
            guard let value = pending else {
                scheduled = false
                lock.unlock()
                return
            }
            pending = nil
            lock.unlock()
            autoreleasepool { write(value) }
        }
    }
}
