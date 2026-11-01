import SwiftUI
import AppKit

// FADER_P29111_FOLDER_NATIVE_V2
// View-only: never alters a source file or changes folder ordering.
// FADER_SELECTION_IMMEDIATE_P29139
// AppKit consumes mouseUp during super.mouseDown; toggle immediately after it
// returns. Native doubleAction, range selection and drag sessions stay intact.
final class FaderToggleSelectionTable: NSTableView {
    private var dragBeganDuringMouseDown = false

    func cancelToggleForDrag() {
        dragBeganDuringMouseDown = true
    }

    override func mouseDown(with event: NSEvent) {
        dragBeganDuringMouseDown = false
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let plainSingle = event.clickCount == 1 &&
            !flags.contains(.command) && !flags.contains(.shift) &&
            !flags.contains(.option) && !flags.contains(.control)
        let candidate = row(at: convert(event.locationInWindow, from: nil))
        let wasSoleSelection = plainSingle && candidate >= 0 &&
            selectedRowIndexes.count == 1 && selectedRowIndexes.contains(candidate)

        super.mouseDown(with: event)

        // No delayed work: toggle at the end of the completed mouse click.
        // A drag or a modifier gesture must not clear selection.
        guard wasSoleSelection, !dragBeganDuringMouseDown,
              selectedRowIndexes.count == 1,
              selectedRowIndexes.contains(candidate) else { return }
        deselectAll(nil) // existing delegate synchronizes the SwiftUI binding
    }
}

struct FolderNativeTable: NSViewRepresentable {
    let tracks: [Track]
    let playingPath: String?
    let playbackRunning: Bool
    let scrollToPath: String?
    var scrollRequest: Int = 0
    @Binding var selectedPaths: Set<String>
    let play: (Track) -> Void
    let playPause: (Track) -> Void
    let addToQueue: (Track) -> Void
    let addToPlaylist: (Track, UUID) -> Void
    let destinations: [PlaylistNativeTable.CopyDestination]

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let table = FaderToggleSelectionTable()
        table.headerView = nil
        table.rowHeight = 56
        table.intercellSpacing = NSSize(width: 0, height: 2)
        table.allowsMultipleSelection = true
        table.allowsEmptySelection = true
        table.selectionHighlightStyle = .regular
        table.gridStyleMask = [.solidHorizontalGridLineMask]
        table.gridColor = NSColor.separatorColor.withAlphaComponent(0.18)
        table.backgroundColor = .clear
        let col = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("song"))
        col.resizingMask = .autoresizingMask
        table.addTableColumn(col)
        table.delegate = context.coordinator
        table.dataSource = context.coordinator
        table.target = context.coordinator
        table.doubleAction = #selector(Coordinator.openTrack(_:))
        table.setDraggingSourceOperationMask(.copy, forLocal: false)
        table.setDraggingSourceOperationMask(.copy, forLocal: true)
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        context.coordinator.table = table
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        let c = context.coordinator
        c.parent = self
        guard let table = c.table else { return }
        let paths = tracks.map { $0.normalizedPath }
        var hasher = Hasher()
        for track in tracks { FaderTrackListing.hashDisplay(track, into: &hasher) }
        for destination in destinations {
            hasher.combine(destination.id)
            hasher.combine(destination.name)
        }
        let signature = hasher.finalize()
        let displayChanged = paths != c.paths || signature != c.renderVersion
        let playbackChanged = c.playingPath != playingPath || c.playbackRunning != playbackRunning
        if displayChanged {
            c.paths = paths
            c.renderVersion = signature
            c.playingPath = playingPath
            c.playbackRunning = playbackRunning
            table.reloadData()
        } else if playbackChanged {
            let affected = FaderTrackListing.affectedRows(in: paths,
                previous: c.playingPath, current: playingPath)
            c.playingPath = playingPath
            c.playbackRunning = playbackRunning
            if !affected.isEmpty {
                table.reloadData(forRowIndexes: affected, columnIndexes: IndexSet(integer: 0))
            }
        }
        let wanted = IndexSet(tracks.indices.filter { selectedPaths.contains(paths[$0]) })
        if table.selectedRowIndexes != wanted {
            c.updatingSelection = true
            table.selectRowIndexes(wanted, byExtendingSelection: false)
            c.updatingSelection = false
        }
        if c.scrollToPath != scrollToPath || c.lastScrollRequest != scrollRequest || displayChanged || playbackChanged {
            c.lastScrollRequest = scrollRequest
            c.scrollToPath = scrollToPath
            if let path = scrollToPath, let row = paths.firstIndex(of: path) {
                DispatchQueue.main.async { [weak table] in
                    guard let table, row < table.numberOfRows else { return }
                    table.scrollRowToVisible(row)
                    let rect = table.rect(ofRow: row)
                    let clip = scroll.contentView
                    clip.scroll(to: NSPoint(x: 0, y: max(0, rect.midY - clip.bounds.height / 2)))
                    scroll.reflectScrolledClipView(clip)
                }
            }
        }
    }

    final class Coordinator: NSObject, NSTableViewDelegate, NSTableViewDataSource {
        var parent: FolderNativeTable
        weak var table: NSTableView?
        var paths: [String] = []
        var renderVersion = 0
        var playingPath: String?
        var playbackRunning = false
        var scrollToPath: String?
        var lastScrollRequest = -1
        var updatingSelection = false
        init(_ parent: FolderNativeTable) { self.parent = parent }
        func numberOfRows(in tableView: NSTableView) -> Int { parent.tracks.count }

        @objc func openTrack(_ sender: NSTableView) {
            guard parent.tracks.indices.contains(sender.clickedRow) else { return }
            parent.play(parent.tracks[sender.clickedRow])
            // STAGE10D39: release native highlight after playback starts.
            parent.selectedPaths = []
            sender.deselectAll(nil)
        }
        func tableViewSelectionDidChange(_ notification: Notification) {
            guard !updatingSelection, let table else { return }
            parent.selectedPaths = Set(table.selectedRowIndexes.compactMap { row in
                parent.tracks.indices.contains(row)
                    ? parent.tracks[row].normalizedPath : nil
            })
        }
        func tableView(_ tableView: NSTableView, pasteboardWriterForRow row: Int) -> NSPasteboardWriting? {
            (tableView as? FaderToggleSelectionTable)?.cancelToggleForDrag()
            guard parent.tracks.indices.contains(row) else { return nil }
            let clicked = parent.tracks[row].normalizedPath
            let selected = parent.selectedPaths.contains(clicked)
                ? parent.tracks.map { $0.normalizedPath }.filter { parent.selectedPaths.contains($0) }
                : [clicked]
            let data = (try? JSONEncoder().encode(selected)) ?? Data("[]".utf8)
            let item = NSPasteboardItem()
            item.setString("fader-tracks:" + data.base64EncodedString(), forType: .string)
            return item
        }
        func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
            guard parent.tracks.indices.contains(row) else { return nil }
            let track = parent.tracks[row]
            let path = track.normalizedPath
            let id = NSUserInterfaceItemIdentifier("FolderSongCell")
            let cell = (tableView.makeView(withIdentifier: id, owner: nil) as? NSTableCellView) ?? NSTableCellView()
            cell.identifier = id
            let content = FolderNativeRow(
                track: track, active: parent.playingPath == path,
                playbackRunning: parent.playbackRunning,
                play: { [weak self] in self?.parent.playPause(track) },
                addToQueue: { [weak self] in self?.parent.addToQueue(track) },
                addToPlaylist: { [weak self] playlistID in self?.parent.addToPlaylist(track, playlistID) },
                destinations: parent.destinations)
            if let hosting = cell.subviews.first as? NSHostingView<FolderNativeRow> {
                hosting.rootView = content
            } else {
                cell.subviews.forEach { $0.removeFromSuperview() }
                let hosting = NSHostingView(rootView: content)
                hosting.translatesAutoresizingMaskIntoConstraints = false
                cell.addSubview(hosting)
                NSLayoutConstraint.activate([
                    hosting.leadingAnchor.constraint(equalTo: cell.leadingAnchor),
                    hosting.trailingAnchor.constraint(equalTo: cell.trailingAnchor),
                    hosting.topAnchor.constraint(equalTo: cell.topAnchor),
                    hosting.bottomAnchor.constraint(equalTo: cell.bottomAnchor)
                ])
            }
            return cell
        }
    }
}

private struct FolderNativeRow: View {
    @AppStorage("Fader.appearance.useSystemAccent") private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex") private var accentHex = "#4B8DF8"
    private var faderAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }
    let track: Track
    let active: Bool
    let playbackRunning: Bool
    let play: () -> Void
    let addToQueue: () -> Void
    let addToPlaylist: (UUID) -> Void
    let destinations: [PlaylistNativeTable.CopyDestination]

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: active ? "speaker.wave.2.fill" : "music.note")
                .foregroundStyle(active ? faderAccent : Color.secondary)
                .frame(width: 18)
            if let image = FaderArtworkImageCache.image(for: track) {
                Image(nsImage: image).resizable().scaledToFit().frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            } else {
                Image(systemName: "music.note").foregroundStyle(.secondary).frame(width: 40, height: 40)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(track.title).font(.system(size: 13, weight: .medium)).lineLimit(1)
                Text(track.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(timeText).font(.caption).monospacedDigit().foregroundStyle(.secondary)
            Button(action: play) { Image(systemName: active && playbackRunning ? "pause.circle" : "play.circle") }
                .buttonStyle(.borderless).help("Play from this track")
            Button(action: addToQueue) { Image(systemName: "plus.circle") }
                .buttonStyle(.borderless).help("Add to Queue")
            Menu {
                if destinations.isEmpty { Text("Create a playlist first") }
                else {
                    ForEach(destinations) { item in
                        Button(item.name) { addToPlaylist(item.id) }
                    }
                }
            } label: { Image(systemName: "text.badge.plus") }
                .menuStyle(.borderlessButton).fixedSize().help("Add to Playlist")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(active ? faderAccent.opacity(0.10) : Color.clear)
        .tint(faderAccent)
    }

    private var timeText: String {
        guard track.duration.isFinite, track.duration > 0 else { return "--:--" }
        let n = Int(track.duration)
        return String(format: "%d:%02d", n / 60, n % 60)
    }
}
