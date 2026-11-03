import SwiftUI
import AppKit
import Combine
import UniformTypeIdentifiers

/// Presentation and explicit Lite imports only. The full library stays mounted
/// when hidden, preserving its selection, tables, refresh jobs and pane sizes.
final class FaderPresentationController: NSObject, ObservableObject, NSWindowDelegate {
    let player: Player
    private(set) var mainWindow: NSWindow?
    private(set) var liteWindow: NSWindow?
    @Published private(set) var isLite = false
    private var finderReceived = false
    private var subscriptions = Set<AnyCancellable>()
    private var restoration: AnyCancellable?
    private final class QueueTransfer {
        var urls: [URL] = []
        var liteTrack: Track?
        var revision = 0
        var tracks: [Track] = []
        var ready = false
        init(liteTrack: Track?) { self.liteTrack = liteTrack }
    }
    private var transfer: QueueTransfer?
    private var prepareWork: DispatchWorkItem?
    private var transferAlert: NSAlert?
    private var liteOpeningURLs: [URL] = []
    private var recentCommitPaths = Set<String>()
    private var recentCommitTime = -Double.infinity
    var hasPendingImports: Bool { transfer != nil }
#if FADER_PRESENTATION_TESTING
    var queueDecision: ((Int, Int) -> FaderLiteImport.Choice)?
    var transferAlertForTesting: NSAlert? { transferAlert }
#endif

    init(player: Player) {
        self.player = player
        super.init()
        FaderFileOpenRouter.shared.install { [weak self] in self?.openFinder($0) }
        NotificationCenter.default.publisher(for: .faderFinishedLaunching).sink { [weak self] _ in
            self?.finishedLaunching()
        }.store(in: &subscriptions)
        NotificationCenter.default.publisher(for: .faderReopen).sink { [weak self] _ in
            guard let self else { return }
            if self.isLite { self.showLite() } else { self.showFull() }
        }.store(in: &subscriptions)
    }

    private func finishedLaunching() {
        NSApp.setActivationPolicy(.regular)
        FaderStorage.defaults.set(true, forKey: "Fader.appearance.useSystemAccent")
        // Finder delivers its initial Apple event around didFinishLaunching.
        // No library view/session is constructed until the launch route is known.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { [weak self] in
            guard let self, !self.finderReceived else { return }
#if FADER_LITE_PREVIEW
            if CommandLine.arguments.contains("--lite-preview") { self.showLite(); return }
#endif
            self.showFull()
        }
        DispatchQueue.main.async {
            NSApp.mainMenu?.items.first(where: { $0.title == "Edit" })?.isHidden = true
        }
    }

    func openFinder(_ urls: [URL]) {
        let supported = urls.filter { $0.isFileURL && Library.audioExtensions.contains($0.pathExtension.lowercased()) }
        guard !supported.isEmpty else { player.status = "No supported audio files."; return }
        finderReceived = true
        let paths = Set(supported.map { $0.standardizedFileURL.path })
        if ProcessInfo.processInfo.systemUptime - recentCommitTime < 1,
           paths.isSubset(of: recentCommitPaths) { return }
        if transfer != nil {
            enqueueQueueImport(supported, liteTrack: nil)
        } else if isLite && (player.currentTrack != nil || !liteOpeningURLs.isEmpty) {
            // Finder may deliver several open-file callbacks before the first
            // header is ready. Include that first URL instead of replacing it.
            if supported.map(\.standardizedFileURL) == liteOpeningURLs.map(\.standardizedFileURL) { return }
            let initial = player.currentTrack == nil ? liteOpeningURLs : []
            enqueueQueueImport(initial + supported, liteTrack: player.currentTrack)
        } else if mainWindow?.isVisible == true || supported.count > 1 {
            // Direct Finder opening remains temporary. Only an explicit Lite
            // transition or a drop on Lite transfers songs into Current Queue.
            liteOpeningURLs = []
            player.finishLiteSession()
            player.openFinderFiles(supported)
            presentFullWindow()
        } else {
            liteOpeningURLs = supported
            showLite()
            player.openFinderFiles(supported)
        }
    }

    func showLite() {
        if !isLite { player.beginLiteSession() }
        isLite = true
        mainWindow?.orderOut(nil)
        if liteWindow == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 155),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window.title = "Fader Lite"
            window.isReleasedWhenClosed = false
            window.minSize = NSSize(width: 900, height: 180)
            window.maxSize = NSSize(width: 1800, height: 260)
            window.setFrameAutosaveName("Fader.LiteWindow")
            window.contentView = NSHostingView(rootView: FaderLiteView(presentation: self).environmentObject(player))
            window.delegate = self
            window.center()
            liteWindow = window
        }
        liteWindow?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func showFull() {
        if transfer != nil { presentFullWindow(); return }
        if isLite, player.currentTrack != nil || !liteOpeningURLs.isEmpty {
            enqueueQueueImport(player.currentTrack == nil ? liteOpeningURLs : [], liteTrack: player.currentTrack)
        } else {
            presentFullWindow()
        }
    }

    private func presentFullWindow() {
        isLite = false
        player.beginSessionRestoration()
        liteWindow?.orderOut(nil)
        if mainWindow == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1200, height: 760),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
            window.title = "Fader"
            window.isReleasedWhenClosed = false
            window.contentMinSize = NSSize(width: 900, height: 480)
            window.center()
            window.contentView = NSHostingView(rootView: ContentView().environmentObject(player)
                .frame(minWidth: 900, minHeight: 480)
                .background(FaderWindowSizing().frame(width: 1, height: 1).allowsHitTesting(false)))
            window.delegate = self
            mainWindow = window
        }
        mainWindow?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func chooseTracks() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = ["mp3", "m4a", "aac", "wav", "aif", "aiff", "flac"].compactMap { UTType(filenameExtension: $0) }
        panel.message = "Choose audio files or folders to add to Current Queue"
        panel.begin { [weak self] response in
            if response == .OK { self?.addFromLite(panel.urls) }
        }
    }

    /// A single transaction spans all callbacks of a Finder/drop action. No
    /// queue mutation occurs before the one sheet has been answered.
    func addFromLite(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        let paths = Set(urls.map { $0.standardizedFileURL.path })
        if ProcessInfo.processInfo.systemUptime - recentCommitTime < 1,
           paths.isSubset(of: recentCommitPaths) { return }
        let initial = isLite && player.currentTrack == nil ? liteOpeningURLs : []
        enqueueQueueImport(initial + urls, liteTrack: (isLite || player.isFinderPlayback) ? player.currentTrack : nil)
    }

    private func enqueueQueueImport(_ urls: [URL], liteTrack: Track?) {
        let request = transfer ?? QueueTransfer(liteTrack: liteTrack)
        let isNew = transfer == nil
        if request.liteTrack == nil { request.liteTrack = liteTrack }
        var paths = Set(request.urls.map { $0.standardizedFileURL.path })
        let added = urls.filter { paths.insert($0.standardizedFileURL.path).inserted }
        if !isNew && added.isEmpty { presentFullWindow(); return }
        request.urls.append(contentsOf: added)
        request.revision += 1
        request.ready = false
        transfer = request
        transferAlert?.buttons.prefix(2).forEach { $0.isEnabled = false }
        transferAlert?.informativeText = "Preparing all selected tracks…"
        presentFullWindow()
        withRestoredSession { [weak self] in self?.schedulePreparation() }
    }

    func withRestoredSession(_ action: @escaping () -> Void) {
        if !player.isRestoringSession { action(); return }
        restoration = player.$isRestoringSession.filter { !$0 }.prefix(1).sink { _ in
            DispatchQueue.main.async(execute: action)
        }
    }

    private func schedulePreparation() {
        prepareWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.prepareTransfer() }
        prepareWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18, execute: work)
    }

    private func prepareTransfer() {
        guard let request = transfer else { return }
        let revision = request.revision
        let urls = request.urls
        let current = request.liteTrack
        player.status = "Preparing files for Current Queue…"
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let imported = FaderLiteImport.collect(urls)
            let tracks = urls.isEmpty || !imported.isEmpty ? FaderLiteImport.includingCurrent(current, in: imported) : []
            DispatchQueue.main.async {
                guard let self, self.transfer === request, request.revision == revision else { return }
                guard !tracks.isEmpty else {
                    self.player.status = "No supported audio files to add."
                    self.transfer = nil
                    return
                }
                request.tracks = tracks
                request.ready = true
                if let alert = self.transferAlert {
                    alert.informativeText = self.confirmationText(tracks.count)
                    alert.buttons.prefix(2).forEach { $0.isEnabled = true }
                    return // Update the same sheet, never present a second one.
                }
                self.confirmQueueImport(request)
            }
        }
    }

    private func confirmationText(_ count: Int) -> String {
        "Add these \(count) tracks (including the Lite song) to the existing queue, replace the queue with this new list, or cancel without changing the saved queue. Finder playback continues. Music files will not be deleted."
    }

    private func completeTransfer(_ request: QueueTransfer, choice: FaderLiteImport.Choice) {
        guard transfer === request else { return }
        guard choice == .cancel || request.ready else { return }
        prepareWork?.cancel()
        transfer = nil
        transferAlert = nil
        let included = Set(request.urls.map { $0.standardizedFileURL.path } + request.tracks.map(\.normalizedPath))
        recentCommitPaths = included
        recentCommitTime = ProcessInfo.processInfo.systemUptime
        liteOpeningURLs = []
        if choice == .cancel {
            player.cancelLiteTransfer()
            return
        }
        let current = (request.liteTrack != nil || player.isFinderPlayback) ? player.currentTrack : nil
        let tracks = FaderLiteImport.includingCurrent(current, in: request.tracks)
        FaderLiteImport.apply(tracks, choice: choice, player: player, liteTrackPath: current?.normalizedPath ?? request.liteTrack?.normalizedPath)
        player.finishLiteSession()
        NotificationCenter.default.post(name: .faderShowCurrentQueue, object: nil)
    }

    private func confirmQueueImport(_ request: QueueTransfer) {
        guard !player.queue.isEmpty else { completeTransfer(request, choice: .append); return }
#if FADER_PRESENTATION_TESTING
        if let queueDecision {
            completeTransfer(request, choice: queueDecision(player.queue.count, request.tracks.count))
            return
        }
#endif
        let alert = NSAlert()
        transferAlert = alert
        alert.messageText = "Current Queue contains \(player.queue.count) tracks."
        alert.informativeText = confirmationText(request.tracks.count)
        alert.addButton(withTitle: "Add to Current Queue")
        alert.addButton(withTitle: "Replace Queue")
        alert.addButton(withTitle: "Cancel")
        alert.buttons.last?.keyEquivalent = "\u{1b}"
        guard let mainWindow else { completeTransfer(request, choice: .cancel); return }
        alert.beginSheetModal(for: mainWindow) { [weak self] response in
            let choice: FaderLiteImport.Choice
            switch response {
            case .alertFirstButtonReturn: choice = .append
            case .alertSecondButtonReturn: choice = .replace
            default: choice = .cancel
            }
            self?.completeTransfer(request, choice: choice)
        }
    }
}
