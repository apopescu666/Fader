import Foundation
import AVFoundation
import SwiftUI
import AppKit
import Combine

// Seek diagnostics intentionally disabled; retain no-op for existing call sites.
private func logToFile(_ message: String) {
    _ = message
}

/// Playback engine with automatic crossfading, built on two `AVAudioPlayer`s.
///
/// Two players are mixed by the system. A crossfade ramps the outgoing
/// player's volume to 0 while the incoming player's ramps up, over a timer.
/// This uses only the long-stable `AVAudioPlayer` API (`volume`, `play`,
/// `pause`, `stop`, `currentTime`, `duration`), so it's simple and reliable.
final class Player: ObservableObject {


    // MARK: - Resume Last Playback

    @Published private(set) var isRestoringSession = false
    private var restoringSession = false
    @Published private(set) var isFinderPlayback = false
    @Published private(set) var finderPlaybackTracks: [Track] = []
    private var finderResumeIndex = -1
    private var finderResumePath: String?
    private var finderRequest = UUID()
    private var finderTransportSettingsChanged = false

    private struct LiteReturnState {
        let queue: [Track]
        let folder: [Track]
        let isFolder: Bool
        let index: Int
        let elapsed: Double
        let shuffle: Bool
        let repeatMode: RepeatMode
        let source: [String: String]
    }
    private var liteReturnState: LiteReturnState?
    private var liteReturnPending = false
    private var liteSource: [String: String] = [:]

    /// Capture the normal session before entering Finder-only Lite playback.
    func beginLiteSession() {
        guard liteReturnState == nil, !liteReturnPending else { return }
        for key in ["Fader.playingSourceKind", "Fader.playingSourceName", "Fader.playingSourceID"] {
            liteSource[key] = FaderStorage.defaults.string(forKey: key) ?? ""
        }
        if !sessionRestorationStarted || isRestoringSession {
            liteReturnPending = true
        } else {
            liteReturnState = LiteReturnState(queue: queue, folder: folderPlaybackTracks,
                isFolder: isFolderPlayback, index: isFinderPlayback ? finderBaseIndex : currentIndex,
                elapsed: isFinderPlayback ? max(0, FaderStorage.defaults.double(forKey: "Fader.lastPlaybackPosition")) : elapsed,
                shuffle: shuffle, repeatMode: repeatMode, source: liteSource)
        }
    }

    func finishLiteSession() {
        liteReturnState = nil
        liteReturnPending = false
    }

    /// Reject queue changes while the explicitly opened Finder audio continues.
    /// Keep saved lists/index/position separate from this temporary live source.
    func cancelLiteTransfer() {
        guard let saved = liteReturnState else { return }
        let liveTracks = playbackTracks
        let liveIndex = currentIndex
        let hasLiveTrack = currentTrack != nil
        let savedTracks = saved.isFolder ? saved.folder : saved.queue
        restoringSession = true
        if hasLiveTrack {
            finderPlaybackTracks = liveTracks
            isFinderPlayback = true
            finderResumeIndex = saved.index
            finderResumePath = savedTracks.indices.contains(saved.index) ? savedTracks[saved.index].normalizedPath : nil
        }
        queue = saved.queue
        folderPlaybackTracks = saved.folder
        isFolderPlayback = saved.isFolder
        if hasLiveTrack {
            currentIndex = liveIndex
        } else {
            currentIndex = saved.index
            elapsed = saved.elapsed
            progress = (currentTrack?.duration ?? 0) > 0 ? min(1, elapsed / (currentTrack?.duration ?? 1)) : 0
        }
        for (key, value) in saved.source { FaderStorage.defaults.set(value, forKey: key) }
        FaderStorage.defaults.set(saved.elapsed, forKey: "Fader.lastPlaybackPosition")
        restoringSession = false
        finishLiteSession()
        status = "Transfer cancelled. Saved queue unchanged; Finder playback continues."
        persistSession()
    }

    private var lastPositionSave = Date.distantPast
    private var terminationObserver: NSObjectProtocol?

    private let restoreURL: URL?
    private let isolatedWriter: PlaybackSessionStore.Writer?

    init(sessionURL: URL? = nil, deferSessionRestoration: Bool = false) {
        restoreURL = sessionURL
        isolatedWriter = sessionURL.map { PlaybackSessionStore.Writer(fileURL: $0) }
        if !deferSessionRestoration { beginSessionRestoration() }

        terminationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.persistSession()
            self?.flushPendingSession()
        }
    }

    deinit {
        if let observer = terminationObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func persistPosition(force: Bool = false) {
        guard sessionRestorationStarted, !restoringSession, !isFinderPlayback else { return }

        let now = Date()
        guard force ||
              now.timeIntervalSince(lastPositionSave) >= 2 else {
            return
        }

        lastPositionSave = now

        FaderStorage.defaults.set(
            max(0, elapsed),
            forKey: "Fader.lastPlaybackPosition"
        )
    }

    private func persistSession() {
        guard sessionRestorationStarted, !restoringSession else { return }

        persistPosition(force: true)

        let repeatValue: String
        switch repeatMode {
        case .one: repeatValue = "one"
        case .all: repeatValue = "all"
        default: repeatValue = "off"
        }

        let session = PlaybackSessionStore.Snapshot(
            queue: queue,
            folderTracks: folderPlaybackTracks,
            isFolderPlayback: isFolderPlayback,
            currentIndex: isFinderPlayback ? finderBaseIndex : currentIndex,
            shuffle: shuffle,
            repeatMode: repeatValue
        )

        if let isolatedWriter { isolatedWriter.save(session) }
        else { PlaybackSessionStore.save(session) }
    }

    func flushPendingSession() {
        if let isolatedWriter { isolatedWriter.flush() }
        else { PlaybackSessionStore.flush() }
    }

    private var sessionRestorationStarted = false
    /// Lite can play an explicit Finder file before restoring a large session.
    /// Existing callers retain immediate restoration through the default init.
    func beginSessionRestoration() {
        guard !sessionRestorationStarted else { return }
        sessionRestorationStarted = true
        restoreSession()
    }

    private func restoreSession() {
        restoringSession = true
        isRestoringSession = true
        status = "Restoring previous session…"
        PlaybackSessionStore.loadInBackground(from: restoreURL) { [weak self] restored in
            guard let self else { return }
            defer {
                self.restoringSession = false
                self.isRestoringSession = false
                // Migrate once, after restoring; subsequent launches use the small manifest.
                self.persistSession()
            }
            guard let restored else {
                if self.liteReturnPending {
                    self.liteReturnState = LiteReturnState(queue: [], folder: [], isFolder: false,
                        index: -1, elapsed: 0, shuffle: false, repeatMode: .off, source: self.liteSource)
                    self.liteReturnPending = false
                }
                self.status = "Ready. Add a folder or playlist to begin."
                return
            }
            self.applyRestoredSession(restored)
        }
    }

    private func applyRestoredSession(_ restored: PlaybackSessionStore.RestoredSession) {
        let session = restored.session
        if liteReturnPending {
            let savedTracks = session.isFolderPlayback ? restored.folderTracks : restored.queue
            let savedIndex = savedTracks.indices.contains(session.currentIndex) ? session.currentIndex : (savedTracks.isEmpty ? -1 : 0)
            let repeatMode: RepeatMode = session.repeatMode == "one" ? .one : (session.repeatMode == "all" ? .all : .off)
            liteReturnState = LiteReturnState(queue: restored.queue, folder: restored.folderTracks,
                isFolder: session.isFolderPlayback, index: savedIndex,
                elapsed: max(0, FaderStorage.defaults.double(forKey: "Fader.lastPlaybackPosition")),
                shuffle: session.shuffle, repeatMode: repeatMode, source: liteSource)
            liteReturnPending = false
        }
        queue = restored.queue
        folderPlaybackTracks = restored.folderTracks
        isFolderPlayback = session.isFolderPlayback
        if !isFinderPlayback || !finderTransportSettingsChanged {
            shuffle = session.shuffle
            switch session.repeatMode {
            case "one": repeatMode = .one
            case "all": repeatMode = .all
            default: repeatMode = .off
            }
        }
        let tracks = isFolderPlayback ? folderPlaybackTracks : queue
        let savedIndex = tracks.indices.contains(session.currentIndex)
            ? session.currentIndex : (tracks.isEmpty ? -1 : 0)
        if isFinderPlayback {
            finderResumeIndex = savedIndex
            finderResumePath = tracks.indices.contains(savedIndex) ? tracks[savedIndex].normalizedPath : nil
            if let path = finderPlaybackTracks.first?.normalizedPath,
               let index = tracks.firstIndex(where: { $0.normalizedPath == path }) {
                // Attach the already-playing audio to its active list; never restart it.
                finderPlaybackTracks = []
                isFinderPlayback = false
                currentIndex = index
                front.index = index
            }
            return // A late restore must never stop or replace Finder playback.
        }
        currentIndex = savedIndex
        guard let track = currentTrack else {
            status = "Previous queue restored."
            return
        }
        let savedPosition = max(0, FaderStorage.defaults.double(forKey: "Fader.lastPlaybackPosition"))
        elapsed = track.duration > 0 ? min(savedPosition, max(0, track.duration - 0.1)) : savedPosition
        progress = track.duration > 0 ? min(elapsed / track.duration, 1) : 0
        // play() already opens the audio file and seeks to elapsed when live is false.
        // Do not contact the local/NAS audio source just to display a paused session.
        live = false
        isPlaying = false
        isCrossfading = false
        incoming = nil
        preRoll = false
        status = "Previous playback restored. Press Play to continue."
    }

    // MARK: - Published UI state
    @Published var queue: [Track] = [] {
        didSet { persistSession() }
    }

    @Published private(set) var folderPlaybackTracks: [Track] = [] {
        didSet { persistSession() }
    }
    @Published private(set) var isFolderPlayback = false {
        didSet { persistSession() }
    }

    private var playbackTracks: [Track] {
        isFinderPlayback ? finderPlaybackTracks : (isFolderPlayback ? folderPlaybackTracks : queue)
    }

    private var finderBaseIndex: Int {
        let base = isFolderPlayback ? folderPlaybackTracks : queue
        if let path = finderResumePath, let index = base.firstIndex(where: { $0.normalizedPath == path }) { return index }
        return base.isEmpty ? -1 : min(max(0, finderResumeIndex), base.count - 1)
    }

    func openFinderFiles(_ urls: [URL]) {
        let supported = urls.filter { $0.isFileURL && Library.audioExtensions.contains($0.pathExtension.lowercased()) }
        guard let first = supported.first else { status = "Unsupported audio file."; return }
        let ticket = UUID(); finderRequest = ticket
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // Start with filename/header information. Tags and artwork must not
            // delay an explicit Finder play request, especially on network storage.
            let name = Library.splitName(first.deletingPathExtension().lastPathComponent)
            var track = Track(url: first, title: name.title, artist: name.artist, album: "")
            track.duration = Library.measureDuration(url: first)
            track.format = first.pathExtension.uppercased()
            let initial = track
            DispatchQueue.main.async { [weak self] in
                guard let self, self.finderRequest == ticket else { return }
                self.openFinderTrack(initial, forceTemporary: supported.count > 1)
                if self.isFinderPlayback {
                    DispatchQueue.global(qos: .utility).async { [weak self] in
                        let enriched = Library.makeTrack(url: first)
                        DispatchQueue.main.async { [weak self] in
                            guard let self, self.finderRequest == ticket, self.isFinderPlayback,
                                  self.finderPlaybackTracks.first?.normalizedPath == initial.normalizedPath else { return }
                            self.finderPlaybackTracks[0] = enriched
                        }
                    }
                }
                if supported.count > 1 {
                    DispatchQueue.global(qos: .utility).async { [weak self] in
                        let rest = supported.dropFirst().map { Library.makeTrack(url: $0) }
                        DispatchQueue.main.async { [weak self] in
                            guard let self, self.finderRequest == ticket, self.isFinderPlayback else { return }
                            self.finderPlaybackTracks.append(contentsOf: rest)
                        }
                    }
                }
            }
        }
    }

    func openFinderTrack(_ track: Track, forceTemporary: Bool = false) {
        let active = isFinderPlayback ? finderPlaybackTracks : (isFolderPlayback ? folderPlaybackTracks : queue)
        if !forceTemporary, !isRestoringSession, !isFinderPlayback,
           case .active(let index) = FaderFinderOpenPolicy.decide(path: track.normalizedPath, activePaths: active.map(\.normalizedPath)) {
            stop()
            playIndex(index)
            return
        }
        if !isFinderPlayback {
            persistPosition(force: true)
            finderResumeIndex = currentIndex
            finderResumePath = currentTrack?.normalizedPath
        }
        stop()
        isFinderPlayback = true
        finderPlaybackTracks = [track]
        currentIndex = 0
        playIndex(0)
    }

    private func leaveFinderPlayback() {
        guard isFinderPlayback else { return }
        stop()
        finderPlaybackTracks = []
        isFinderPlayback = false
        currentIndex = finderBaseIndex
    }

    func playFolder(_ tracks: [Track], startingAt index: Int = 0) {
        finderRequest = UUID()
        guard !tracks.isEmpty, tracks.indices.contains(index) else { return }

        leaveFinderPlayback()
        stop()
        folderPlaybackTracks = tracks
        isFolderPlayback = true
        currentIndex = index
        playIndex(index)
    }

    func playQueueIndex(_ index: Int) {
        finderRequest = UUID()
        guard queue.indices.contains(index) else { return }

        leaveFinderPlayback()
        stop()
        isFolderPlayback = false
        folderPlaybackTracks = []
        currentIndex = index
        playIndex(index)
        // FADER_PLAYING_SOURCE_P07_V1
        FaderStorage.defaults.set("queue", forKey: "Fader.playingSourceKind")
        FaderStorage.defaults.set("Current Queue", forKey: "Fader.playingSourceName")
        FaderStorage.defaults.set("", forKey: "Fader.playingSourceID")
    }
    @Published var currentIndex: Int = -1 {
        didSet { persistSession() }
    }
    @Published var isPlaying = false
    @Published var isCrossfading = false
    // FADER_DECOUPLED_PROGRESS_V4
    var progress: Double = 0        // 0…1 within the current track
    var elapsed: Double = 0         // seconds within the current track
    @Published var volume: Float =
        FaderStorage.defaults.object(forKey: "Fader.savedVolume") == nil
        ? 1.0
        : FaderStorage.defaults.float(forKey: "Fader.savedVolume")
    @Published var crossfade: Double = {
        let defaults = FaderStorage.defaults
        guard defaults.object(forKey: "Fader.savedCrossfade") != nil else {
            return 3.0
        }
        return defaults.double(forKey: "Fader.savedCrossfade")
    }() {
        didSet {
            FaderStorage.defaults.set(
                crossfade,
                forKey: "Fader.savedCrossfade"
            )
        }
    }      // seconds; 0 = instant swap
    @Published var shuffle = false {
        didSet { persistSession() }
    }
    @Published var repeatMode: RepeatMode = .off {
        didSet { persistSession() }
    }
    @Published var status = "Ready. Add a folder or playlist to begin."
    @Published var incoming: Track? = nil       // track fading in (shown during a crossfade)

    // Mute state tracking
    @Published var isMuted = false
    private var lastVolume: Float =
        max(0.01, FaderStorage.defaults.object(forKey: "Fader.savedVolume") == nil
            ? 1.0
            : FaderStorage.defaults.float(forKey: "Fader.savedVolume"))         // Store the last non-zero volume for unmute

    private let fadeDuration: Double = 1.0      // fade-in at start / fade-out at end

    // MARK: - Playback

    private final class Slot {
        var player: AVAudioPlayer?
        var index = -1
        var startWall = Date.distantPast
        var pausedAccum: TimeInterval = 0
    }
    private let slotA = Slot(), slotB = Slot()
    private var active = 0                       // 0 = slotA in front, 1 = slotB in front
    private var front: Slot { active == 0 ? slotA : slotB }
    private var back: Slot { active == 0 ? slotB : slotA }

    private var timer: Timer?
    private var lastUIProgressUpdate = Date.distantPast

    private var live = false                    // is a player actually running?
    private var preRoll = false                 // advance already scheduled for the ending track?
    private var pauseStart: Date? = nil

    // MARK: - Volume ramp (drives crossfades / fades)
    private var ramping = false
    private var rampStartWall = Date()
    private var rampDur: Double = 1.0
    private var rampFrontFrom: Float = 0, rampFrontTo: Float = 0, rampFrontActive = false
    private var rampBackFrom: Float = 0, rampBackTo: Float = 0, rampBackActive = false
    private var rampDone: (() -> Void)?

    var currentTrack: Track? {
        guard currentIndex >= 0, currentIndex < playbackTracks.count else { return nil }
        return playbackTracks[currentIndex]
    }

    // MARK: - Timer

    private func ensureTimer() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 0.03, repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    // MARK: - Queue

    func replaceQueue(_ tracks: [Track]) {
        finderRequest = UUID()
        leaveFinderPlayback()
        stop()
        queue = tracks
        currentIndex = tracks.isEmpty ? -1 : 0
        progress = 0; elapsed = 0; incoming = nil
        status = tracks.isEmpty ? "Queue empty." : "\(tracks.count) tracks ready."
    }

    /// Called only after the Lite confirmation. Keep the live playback slots
    /// and their source snapshot intact, including an in-flight crossfade.
    func replaceQueueFromLite(_ tracks: [Track]) {
        guard !isRestoringSession else { return }
        if !isFolderPlayback && !isFinderPlayback && currentTrack != nil {
            finderResumeIndex = currentIndex
            finderResumePath = currentTrack?.normalizedPath
            finderPlaybackTracks = queue
            isFinderPlayback = true
        }
        var seen = Set<String>()
        queue = tracks.filter { seen.insert($0.normalizedPath).inserted }
        status = "Current Queue replaced: \(queue.count) track(s)."
    }

    /// Reassign only the list indices/source. Keep the same AVAudioPlayer slots,
    /// position, pause state and crossfade ramp when the Lite song joins Queue.
    func attachLitePlaybackToQueue(path: String) {
        guard currentTrack?.normalizedPath == path,
              let index = queue.firstIndex(where: { $0.normalizedPath == path }) else { return }
        let previousTracks = playbackTracks
        let outgoingPath = previousTracks.indices.contains(back.index) ? previousTracks[back.index].normalizedPath : nil
        finderRequest = UUID()
        isFolderPlayback = false
        folderPlaybackTracks = []
        isFinderPlayback = false
        finderPlaybackTracks = []
        currentIndex = index
        front.index = index
        back.index = outgoingPath.flatMap { path in queue.firstIndex(where: { $0.normalizedPath == path }) } ?? -1
        FaderStorage.defaults.set("queue", forKey: "Fader.playingSourceKind")
        FaderStorage.defaults.set("Current Queue", forKey: "Fader.playingSourceName")
        FaderStorage.defaults.set("", forKey: "Fader.playingSourceID")
        persistSession()
    }

    func append(_ tracks: [Track]) {
        // FADER_QUEUE_GLOBAL_DEDUP_V1
        // Keep one entry per normalized audio-file path.
        // Preserve existing queue entries, their order and playback state.
        var knownPaths = Set(queue.map {
            $0.url.standardizedFileURL.path
        })

        let uniqueTracks = tracks.filter { track in
            knownPaths.insert(
                track.url.standardizedFileURL.path
            ).inserted
        }

        queue.append(contentsOf: uniqueTracks)
        status = "Added \(tracks.count) track(s). Queue: \(queue.count)."
    }

    // FADER_STAGE10D23: external copy insertion only; native reorder is unchanged.
    // Keep active and crossfade slot indices pointing to the same audio tracks.
    func insertExternalQueueTracks(_ tracks: [Track], at proposedSlot: Int) {
        var knownPaths = Set(queue.map { $0.url.standardizedFileURL.path })
        let unique = tracks.filter {
            knownPaths.insert($0.url.standardizedFileURL.path).inserted
        }
        guard !unique.isEmpty else { return }
        let slot = max(0, min(proposedSlot, queue.count))
        if !isFolderPlayback && !isFinderPlayback {
            if currentIndex >= slot { currentIndex += unique.count }
            if front.index >= slot { front.index += unique.count }
            if back.index >= slot { back.index += unique.count }
        }
        queue.insert(contentsOf: unique, at: slot)
        status = "Inserted \(unique.count) song(s). Queue: \(queue.count)."
    }

    func remove(atOffsets offsets: IndexSet) {
        if isFolderPlayback || isFinderPlayback {
            queue.remove(atOffsets: offsets)
            status = "Removed items from Current Queue."
            return
        }
        let removed = offsets.map { queue[$0] }
        let removedURLs = Set(removed.map { $0.url.standardizedFileURL })
        if let ct = currentTrack, removedURLs.contains(ct.url.standardizedFileURL) {
            stop(); currentIndex = -1
        } else if currentIndex >= 0 {
            let delta = offsets.filter { $0 < currentIndex }.count
            currentIndex = max(0, currentIndex - delta)
        }
        queue.remove(atOffsets: offsets)
        status = "Removed \(offsets.count). Queue: \(queue.count)."
    }

    // FADER_P2911_QUEUE_REORDER
    // Reorder queue entries without restarting audio playback.
    @discardableResult
    func reorderQueue(sourceID: UUID, toInsertionSlot slot: Int) -> Bool {
        guard !isCrossfading, !ramping else { return false }

        let ids = queue.map { $0.id.uuidString }
        guard let reorderedIDs = FaderReorderEngine.reorder(
            ids,
            moving: sourceID.uuidString,
            toInsertionSlot: slot
        ) else { return false }

        guard reorderedIDs != ids else { return false }

        let tracksByID = Dictionary(
            uniqueKeysWithValues: queue.map { ($0.id.uuidString, $0) }
        )
        let reordered = reorderedIDs.compactMap { tracksByID[$0] }
        guard reordered.count == queue.count else { return false }

        let activeID: UUID? = (!isFolderPlayback && queue.indices.contains(currentIndex))
            ? queue[currentIndex].id : nil

        let frontID: UUID? = (!isFolderPlayback && queue.indices.contains(front.index))
            ? queue[front.index].id : nil

        let backID: UUID? = (!isFolderPlayback && queue.indices.contains(back.index))
            ? queue[back.index].id : nil

        queue = reordered

        if !isFolderPlayback && !isFinderPlayback {
            if let activeID,
               let index = queue.firstIndex(where: { $0.id == activeID }) {
                currentIndex = index
            }

            if let frontID,
               let index = queue.firstIndex(where: { $0.id == frontID }) {
                front.index = index
            }

            if let backID,
               let index = queue.firstIndex(where: { $0.id == backID }) {
                back.index = index
            }
        }

        status = "Current Queue order updated."
        return true
    }

    // MARK: - Transport

    func play() {
        ensureTimer()
        if live {
            resume()
        } else if !playbackTracks.isEmpty {
            let savedPosition = elapsed
            playIndex(currentIndex >= 0 ? currentIndex : 0)
            if live && savedPosition > 0 {
                seek(to: savedPosition)
            }
        }
    }

    func pause() {
        front.player?.pause()
        if isCrossfading { back.player?.pause() }
        pauseStart = Date()
        isPlaying = false
        status = "Paused."
        persistPosition(force: true)
    }

    func togglePlay() { isPlaying ? pause() : play() }

    func stop() {
        for s in [slotA, slotB] { s.player?.stop(); s.player = nil }
        live = false
        isPlaying = false
        isCrossfading = false
        ramping = false
        rampDone = nil
        incoming = nil
        progress = 0; elapsed = 0
        pauseStart = nil
        status = "Stopped."
    }

    private func resume() {
        let now = Date()
        if let p = pauseStart {
            let d = now.timeIntervalSince(p)
            front.pausedAccum += d
            if isCrossfading { back.pausedAccum += d }
            pauseStart = nil
        }
        front.player?.play()
        if isCrossfading { back.player?.play() }
        isPlaying = true
        status = "Playing."
    }

    func playIndex(_ index: Int) {
        guard index >= 0, index < playbackTracks.count else {
            logToFile("playIndex called with invalid index \(index)")
            return
        }
        ensureTimer()
        for s in [slotA, slotB] { s.player?.stop(); s.player = nil }
        active = 0
        let slot = front
        // FADER_PLAY_FAILURE_STATE_V1
        guard let p = try? AVAudioPlayer(contentsOf: playbackTracks[index].url) else {
            live = false
            isPlaying = false
            isCrossfading = false
            ramping = false
            rampDone = nil
            incoming = nil
            pauseStart = nil
            elapsed = 0
            progress = 0

            status = "Couldn't load \(playbackTracks[index].title)."
            logToFile("Failed to load track \(playbackTracks[index].title)")
            return
        }
        slot.player = p
        slot.index = index
        slot.startWall = Date()
        slot.pausedAccum = 0
        p.volume = 0
        p.prepareToPlay()
        p.play()
        currentIndex = index
        elapsed = 0
        progress = 0
        preRoll = false
        pauseStart = nil
        isCrossfading = false
        incoming = nil
        live = true
        // Fade in from silence.
        startRamp(front: volume, back: nil, over: fadeDuration) { }
        isPlaying = true
        status = "Now playing: \(playbackTracks[index].title)"
        logToFile("playIndex completed for track \(playbackTracks[index].title)")
    }

    func next(manual: Bool = false) {
        guard !playbackTracks.isEmpty else { return }
        if let idx = nextIndex(from: currentIndex) {
            crossfade(to: idx, manual: manual)
        } else {
            fadeOutAndStop()
        }
    }

    // FADER_UNIFIED_CROSSFADE_V2
    func previous() {
        guard !playbackTracks.isEmpty else { return }

        // Preserve existing behavior: after 3 seconds,
        // Previous restarts the current song.
        if elapsed > 3 {
            playIndex(currentIndex)
            return
        }

        let targetIndex = previousIndex(from: currentIndex)

        // Crossfade only when audio is actively playing.
        // Otherwise preserve direct track selection.
        if live && isPlaying && targetIndex != currentIndex {
            crossfade(to: targetIndex, manual: true)
        } else {
            playIndex(targetIndex)
        }
    }

    // MARK: - Crossfade

    private func crossfade(to newIndex: Int, manual: Bool) {
        guard newIndex >= 0, newIndex < playbackTracks.count else { return }
        ensureTimer()
        let target = playbackTracks[newIndex]
        guard let p = try? AVAudioPlayer(contentsOf: target.url) else {
            status = "Couldn't load \(target.title)."
            return
        }

        // Load the new track onto the back slot, silent.
        let incomingSlot = back
        incomingSlot.player?.stop()
        incomingSlot.player = p
        incomingSlot.index = newIndex
        incomingSlot.startWall = Date()
        incomingSlot.pausedAccum = 0
        p.volume = 0
        p.prepareToPlay()
        p.play()

        // Flip so the new track is now in front.
        active = (active == 0) ? 1 : 0
        live = true
        isCrossfading = true
        incoming = target
        currentIndex = newIndex
        elapsed = 0
        progress = 0
        preRoll = false
        persistSession()

        if crossfade <= 0.05 {
            // Instant swap.
            front.player?.volume = volume
            back.player?.stop(); back.player = nil
            isCrossfading = false
            incoming = nil
            status = "Now playing: \(target.title)"
        } else {
            let over = crossfade
            startRamp(front: volume, back: 0, over: over) { [weak self] in
                guard let self else { return }
                self.back.player?.stop()
                self.back.player = nil
                self.isCrossfading = false
                self.incoming = nil
            }
            status = "Crossfading into \(target.title)…"
        }
    }

    private func fadeOutAndStop() {
        let over = max(crossfade, fadeDuration)
        startRamp(front: 0, back: nil, over: over) { [weak self] in
            guard let self else { return }
            self.slotA.player?.stop(); self.slotA.player = nil
            self.slotB.player?.stop(); self.slotB.player = nil
            self.live = false
            self.isPlaying = false
            self.isCrossfading = false
            self.progress = 0; self.elapsed = 0
            self.status = "Finished."
        }
    }

    // MARK: - Ramp

    private func startRamp(front targetFront: Float?, back targetBack: Float?,
                           over seconds: Double, done: @escaping () -> Void) {
        rampFrontActive = targetFront != nil
        rampBackActive = targetBack != nil
        rampFrontFrom = self.front.player?.volume ?? 0
        rampFrontTo = targetFront ?? rampFrontFrom
        rampBackFrom = self.back.player?.volume ?? 0
        rampBackTo = targetBack ?? rampBackFrom
        rampDur = max(seconds, 0.02)
        rampStartWall = Date()
        rampDone = done
        ramping = true
    }

    private func lerp(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }

    // MARK: - Tick

    private func tick() {
        // 1. Advance the crossfade / fade ramp.
        if ramping {
            let p = min(1.0, Date().timeIntervalSince(rampStartWall) / rampDur)
            if rampFrontActive { front.player?.volume = lerp(rampFrontFrom, rampFrontTo, Float(p)) }
            if rampBackActive { back.player?.volume = lerp(rampBackFrom, rampBackTo, Float(p)) }
            if p >= 1 {
                if rampFrontActive { front.player?.volume = rampFrontTo }
                if rampBackActive { back.player?.volume = rampBackTo }
                ramping = false
                rampFrontActive = false; rampBackActive = false
                let d = rampDone; rampDone = nil
                d?()
            }
        }

        guard isPlaying, let t = currentTrack, t.duration > 0 else { return }
        let e = elapsedNow()

        // FADER_UI_PROGRESS_THROTTLE_V1
        // Keep the 30 ms audio timer for crossfade accuracy.
        // Publish UI progress approximately every 100 ms.
        let now = Date()
        if now.timeIntervalSince(lastUIProgressUpdate) >= 0.10 {
            elapsed = e
            progress = min(e / t.duration, 1.0)
            lastUIProgressUpdate = now
        }

        persistPosition()

        // 2. Decide when to advance to the next track.
        if !preRoll {
            // Calculate when crossfade should begin for exact overlap timing
            let remaining = t.duration - e

            // For accurate N-second overlap:
            // Crossfade should begin when there are exactly 'crossfade' seconds remaining
            // This ensures the last 'crossfade' seconds of current track overlap with first 'crossfade' seconds of next track
            if crossfade > 0.05 && remaining <= crossfade && remaining > 0 {
                preRoll = true
                if let idx = nextIndex(from: currentIndex) {
                    // Calculate exact timing for the crossfade to achieve desired overlap
                    crossfade(to: idx, manual: false)
                } else {
                    fadeOutAndStop()
                }
            } else if crossfade <= 0.05 && e >= t.duration {
                // Instant swap at end of track
                preRoll = true
                if let idx = nextIndex(from: currentIndex) {
                    crossfade(to: idx, manual: true)
                } else {
                    fadeOutAndStop()
                }
            }
        }
    }

    private func elapsedNow() -> Double {
        let s = front
        let ref = (pauseStart ?? Date())
        return ref.timeIntervalSince(s.startWall) - s.pausedAccum
    }

    // MARK: - Index helpers

    private func nextIndex(from current: Int) -> Int? {
        let n = playbackTracks.count
        guard n > 0 else { return nil }
        if repeatMode == .one { return current }
        if isFinderPlayback && n == 1 { return nil }
        if shuffle {
            guard n > 1 else { return current }
            var r = current
            while r == current { r = Int.random(in: 0..<n) }
            return r
        }
        if current + 1 < n { return current + 1 }
        return repeatMode == .all ? 0 : nil
    }

    private func previousIndex(from current: Int) -> Int {
        let n = playbackTracks.count
        guard n > 0 else { return 0 }
        if shuffle { return n > 1 ? Int.random(in: 0..<n) : current }
        if current - 1 >= 0 { return current - 1 }
        return repeatMode == .all ? n - 1 : 0
    }

    // MARK: - Settings

    func setVolume(_ v: Float) {
        volume = v
        FaderStorage.defaults.set(v, forKey: "Fader.savedVolume")
        if v > 0 {
            lastVolume = v
        }
        if !isCrossfading && !ramping {
            front.player?.volume = v
            back.player?.volume = v
        }

        // If we're setting a non-zero volume and were muted, unmute
        if v > 0 && isMuted {
            isMuted = false
        }
    }

    func toggleShuffle() {
        if (isRestoringSession || !sessionRestorationStarted) && isFinderPlayback { finderTransportSettingsChanged = true }
        shuffle.toggle(); status = "Shuffle \(shuffle ? "on" : "off")." }

    func cycleRepeat() {
        if (isRestoringSession || !sessionRestorationStarted) && isFinderPlayback { finderTransportSettingsChanged = true }
        let order: [RepeatMode] = [.off, .all, .one]
        let i = order.firstIndex(of: repeatMode) ?? 0
        repeatMode = order[(i + 1) % order.count]
        status = repeatMode.label
    }

    // Mute toggle functionality
    func toggleMute() {
        if isMuted {
            // Unmute - restore last volume
            setVolume(lastVolume)
            isMuted = false
        } else {
            // Mute - save current volume and set to 0
            lastVolume = volume
            setVolumeWithoutMute(0.0)
            isMuted = true
        }
    }

    // Override the volume setter for mute handling
    func setVolumeWithoutMute(_ v: Float) {
        volume = v
        if !isCrossfading && !ramping {
            front.player?.volume = v
            back.player?.volume = v
        }
    }

    // Seek to a specific time in the currently playing track
    func seek(to seconds: Double) {
        guard let track = currentTrack else { return }

        // Handle pause state preservation
        let wasPlaying = isPlaying

        // Pause if needed for clean seeking
        if isPlaying {
            pause()
        }

        // Update the player's current time directly
        if let p = front.player {
            p.currentTime = seconds

            // Update UI elements - this ensures the displayed values match currentTime immediately
            let newProgress = min(seconds / track.duration, 1.0)
            elapsed = seconds
            progress = newProgress

            // Synchronize timing state to match the new currentTime
            // This ensures that elapsedNow() will return the correct value in the next tick
            let now = Date()

            // Calculate what startWall should be so that elapsedNow() returns seconds
            // elapsedNow() = ref.timeIntervalSince(s.startWall) - s.pausedAccum
            // We want elapsedNow() to equal seconds, so:
            // seconds = ref.timeIntervalSince(s.startWall) - s.pausedAccum
            // s.startWall = ref.addingTimeInterval(-seconds) - s.pausedAccum

            // The key fix: always set startWall correctly regardless of pause state
            front.startWall = now.addingTimeInterval(-seconds)
            front.pausedAccum = 0 // Reset accumulated pause time since we're seeking
        }

        // Resume playback if it was playing
        if wasPlaying {
            play()
        }

        persistPosition(force: true)
    }
}