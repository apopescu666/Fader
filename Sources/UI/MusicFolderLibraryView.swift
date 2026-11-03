import CryptoKit
import SwiftUI
import AppKit


private struct FaderPlaylist: Codable, Identifiable {
    var id: UUID
    var name: String
    var trackPaths: [String]
}

struct MusicFolderLibraryView: View {
    @EnvironmentObject var player: Player
    @AppStorage("Fader.appearance.useSystemAccent", store: FaderStorage.defaults) private var selectionUseSystemAccent = true
    @AppStorage("Fader.appearance.accentHex", store: FaderStorage.defaults) private var selectionAccentHex = "#4B8DF8"
    private var selectionAccent: Color {
        selectionUseSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(selectionAccentHex)
    }
    @Binding var showSidebar: Bool
    @AppStorage("Fader.premium.showQueuePanel", store: FaderStorage.defaults) private var showQueuePanel = true

    @State private var libraryStartupComplete = false
    @State private var folders: [URL] = []
    @State private var selectedFolder: String? = nil
    @State private var folderTracks: [Track] = []
    @State private var folderSearchText = ""
    @State private var playlistSearchText = ""
    @State private var goToPlayingRequest = 0
    @State private var selectedFolderTrackPaths: Set<String> = []
    @State private var folderSelectionAnchorPath: String? = nil
    @State private var isLoading = false
    @State private var initialImportProgress: FaderInitialFolderImport.Progress?
    @State private var initialImportError: String?
    @State private var folderPendingRemoval: URL? = nil

    @State private var playlists: [FaderPlaylist] = []
    @State private var selectedPlaylistID: UUID? = nil
    @State private var playlistNameDraft = ""
    @State private var playlistBeingRenamed: UUID? = nil
    @State private var showPlaylistEditor = false
    @State private var playlistPendingRemoval: UUID? = nil
    @State private var selectedPlaylistSongIndices: Set<Int> = []
    @State private var showRemoveSelectedConfirmation = false

    @State private var refreshingPlaylistID: UUID? = nil
    // FADER_PLAYLIST_SAFE_REFRESH_V2
    @State private var pendingPlaylistCleanup:
        (id: UUID, missing: Set<String>, originalPaths: [String])? = nil


    private let playlistStorageKey = "Fader.savedPlaylists"
    private let lastPlaylistKey = "Fader.lastSelectedPlaylistID"

    // Cache tracks separately for each folder.
    @State private var folderCache: [String: [Track]] = [:]

    // Metadata used by playlists, loaded from the existing disk cache.
    @State private var playlistMetadataCache: [String: Track] = [:]
    @State private var scanningFolders: Set<String> = []
    @State private var repairingArtworkFolders: Set<String> = []

    // FADER_GLOBAL_SEARCH_SAFE_P21_V1: read-only index, independent of playlist metadata.
    // FADER_SELECTION_TOGGLE_ALL_P29137
    @State private var selectedGlobalSearchPaths: Set<String> = []
    @State private var globalSearchSelectionAnchor: String? = nil
    @State private var globalSearchSelected = false
    @State private var globalSearchText = ""
    @State private var globalSearchTracks: [Track] = []
    @State private var globalSearchLoading = false
    // FADER_P295C_PENDING_SEARCH_SYNC
    @State private var globalSearchRefreshPending = false
    // FADER_SQLITE_CLEANUP_P29124_V1
    // SQLite is the sole Global Search engine; no experimental mode toggle.
    @StateObject private var searchModel = FaderGlobalSearchModel()
    // FADER_GLOBAL_SEARCH_GO_TO_SONG_P25_V1
    @State private var goToSongPath: String? = nil
    @State private var goToSongPlaylistID: UUID? = nil


    private let storageKey = "Fader.savedMusicFolders"

    var body: some View {
        VStack(spacing: 0) {
            libraryToolbar
        Group {
        if showSidebar {
        FaderAccentNativeSplit(
            isVertical: true,
            storageKey: "Fader.split.sidebar",
            minFirst: 220,
            minSecond: showQueuePanel ? 633 : 336,
            first: Group {
                GeometryReader { geometry in
                    ScrollView(.vertical) {
                        sidebar
                            .frame(width: geometry.size.width,
                                   height: max(400, geometry.size.height))
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
            }, second: Group {
            if showQueuePanel {
                FaderPersistentQueueSplit(
                    center:
                    Group {
                if globalSearchSelected {
                    globalSearchContent
                } else if let id = selectedPlaylistID {
                    playlistDetails(id)
                } else if let path = selectedFolder {
                    folderBrowser(path: path)
                } else {
                    queueWithPlaylistActions
                }
            }
            // Keep navigation switches instant; do not animate the split's central pane.
            // FADER_PREMIUM_10D4_NO_NAV_TRANSITION
                    .transaction { transaction in
                transaction.disablesAnimations = true
            }
                    .frame(minWidth: 320, maxWidth: .infinity)
                    .background(Color(nsColor: .windowBackgroundColor),
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .environmentObject(player),
                    queue:
                FaderPremiumQueuePreview(
                    onDropToQueue: acceptCurrentQueueSidebarDrop,
                    onDropToPlaylist: { providers, id in
                        acceptPlaylistTrackDrop(providers, into: id)
                    },
                    playlistTracks: { id in
                        guard let playlist = playlists.first(where: { $0.id == id }) else { return [] }
                        return playlistPlaybackTracks(playlist).map { $0.track }
                    },
                    playPlaylistAt: { id, index in
                        playPlaylist(id, startingAt: index)
                    }
                )
                    .background(Color(nsColor: .windowBackgroundColor),
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                    .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .environmentObject(player)
                )
                .frame(minWidth: 633, maxWidth: .infinity)
            } else {
            Group {
                if globalSearchSelected {
                    globalSearchContent
                } else if let id = selectedPlaylistID {
                    playlistDetails(id)
                } else if let path = selectedFolder {
                    folderBrowser(path: path)
                } else {
                    queueWithPlaylistActions
                }
            }
            // Keep navigation switches instant; do not animate the split's central pane.
            // FADER_PREMIUM_10D4_NO_NAV_TRANSITION
            .transaction { transaction in
                transaction.disablesAnimations = true
            }
            .frame(minWidth: 320, maxWidth: .infinity)
            .background(Color(nsColor: .windowBackgroundColor),
                        in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)

            }
        })
        } else {
            Group {
                if showQueuePanel {
                    FaderPersistentQueueSplit(
                        center: Group {
                            if globalSearchSelected { globalSearchContent }
                            else if let id = selectedPlaylistID { playlistDetails(id) }
                            else if let path = selectedFolder { folderBrowser(path: path) }
                            else { queueWithPlaylistActions }
                        }
                        .frame(minWidth: 320, maxWidth: .infinity)
                        .background(Color(nsColor: .windowBackgroundColor),
                                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .environmentObject(player),
                        queue: FaderPremiumQueuePreview(
                            onDropToQueue: acceptCurrentQueueSidebarDrop,
                            onDropToPlaylist: { providers, id in acceptPlaylistTrackDrop(providers, into: id) },
                            playlistTracks: { id in
                                guard let playlist = playlists.first(where: { $0.id == id }) else { return [] }
                                return playlistPlaybackTracks(playlist).map { $0.track }
                            },
                            playPlaylistAt: { id, index in playPlaylist(id, startingAt: index) }
                        )
                        .background(Color(nsColor: .windowBackgroundColor),
                                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .environmentObject(player)
                    )
                    .frame(minWidth: 633, maxWidth: .infinity)
                } else {
                    Group {
                        if globalSearchSelected { globalSearchContent }
                        else if let id = selectedPlaylistID { playlistDetails(id) }
                        else if let path = selectedFolder { folderBrowser(path: path) }
                        else { queueWithPlaylistActions }
                    }
                    .frame(minWidth: 320, maxWidth: .infinity)
                        .background(Color(nsColor: .windowBackgroundColor),
                                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                        .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                }
            }
        }
        }
        }
        .background(Color(nsColor: .controlBackgroundColor))
        .onReceive(NotificationCenter.default.publisher(
            for: Notification.Name("Fader.goToPlaying")
        )) { _ in
            goToPlayingSource()
            // Allow the destination table to mount before requesting scroll.
            DispatchQueue.main.async { goToPlayingRequest += 1 }
        }
        // FADER_FILEMENU_IMPORT_P291313 — only the File menu uses these notifications.
        .onReceive(NotificationCenter.default.publisher(
            for: Notification.Name("Fader.fileMenuAddFolder")
        )) { notification in
            guard let url = notification.userInfo?["url"] as? URL else { return }
            let normalized = url.standardizedFileURL
            guard !folders.contains(where: { $0.standardizedFileURL.path == normalized.path }) else {
                player.status = "Folder already in Music Folders."
                selectFolder(normalized)
                return
            }
            folders.append(normalized)
            saveFolders()
            selectFolder(normalized)
            player.status = "Added folder to Music Folders: \(normalized.lastPathComponent)"
        }
        .onReceive(NotificationCenter.default.publisher(
            for: Notification.Name("Fader.fileMenuImportPlaylists")
        )) { notification in
            guard let imports = notification.userInfo?["imports"] as? [[String: Any]] else { return }
            var importedCount = 0
            for item in imports {
                guard let url = item["url"] as? URL,
                      let tracks = item["tracks"] as? [Track] else { continue }
                var seen = Set<String>()
                let unique = tracks.filter { track in
                    seen.insert(track.normalizedPath).inserted
                }
                guard !unique.isEmpty else { continue }
                let name = url.deletingPathExtension().lastPathComponent
                // Avoid importing the same unchanged playlist as a duplicate.
                let paths = unique.map { $0.normalizedPath }
                if let existing = playlists.first(where: { $0.name == name && $0.trackPaths == paths }) {
                    selectPlaylist(existing)
                    continue
                }
                let imported = FaderPlaylist(id: UUID(), name: name, trackPaths: paths)
                playlists.append(imported)
                for track in unique {
                    playlistMetadataCache[track.normalizedPath] = track
                }
                selectPlaylist(imported)
                importedCount += 1
            }
            if importedCount > 0 { savePlaylists() }
            player.status = importedCount > 0
                ? "Imported \(importedCount) playlist(s)."
                : "No new playlist imported (empty or already present)."
        }
        .onReceive(NotificationCenter.default.publisher(for: .faderShowCurrentQueue)) { _ in
            globalSearchSelected = false
            globalSearchText = ""
            selectedFolder = nil
            selectedPlaylistID = nil
        }
        .onAppear { initializeLibraryIfReady() }
        .onChange(of: player.isRestoringSession) { _, loading in
            if !loading { initializeLibraryIfReady() }
        }
        // FADER_SQLITE_AUTOSYNC_P29121_V1
        // Queue membership changes trigger a local SQLite index refresh.
        // Sorting/reordering alone does not change the indexed membership.
        .onChange(of: Set(player.queue.map { $0.normalizedPath })) { _, _ in
            if libraryStartupComplete { publishSearchSources() }
        }
        .onChange(of: globalSearchText) { _, newValue in
            // STAGE10D41: navigation only; reuse the existing SQLite scheduler.
            globalSearchSelected = !newValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            scheduleSQLiteBetaSearch(newValue)
        }
        .onChange(of: globalSearchSelected) { _, selected in
            if selected { scheduleSQLiteBetaSearch(globalSearchText) }
            else if !globalSearchText.isEmpty { globalSearchText = "" }
        }
        .onChange(of: globalSearchLoading) { _, loading in
            if !loading && globalSearchSelected && !globalSearchText.isEmpty {
                scheduleSQLiteBetaSearch(globalSearchText)
            }
        }
        .onChange(of: selectedFolder) { _, newValue in
            if let path = newValue {
                FaderStorage.defaults.set(
                    path,
                    forKey: "Fader.lastSelectedFolder"
                )
            } else {
                FaderStorage.defaults.removeObject(
                    forKey: "Fader.lastSelectedFolder"
                )
            }
        }

        .confirmationDialog(
            "Remove missing songs from playlist?",
            isPresented: Binding(
                get: { pendingPlaylistCleanup != nil },
                set: {
                    if !$0 {
                        pendingPlaylistCleanup = nil
                    }
                }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove Missing Songs", role: .destructive) {
                confirmPlaylistCleanup()
            }

            Button("Keep All Songs", role: .cancel) {
                pendingPlaylistCleanup = nil
            }
        } message: {
            if let pending = pendingPlaylistCleanup {
                Text(
                    "\(pending.missing.count) song(s) were not found. " +
                    "They may be temporarily unavailable on Synology. " +
                    "Remove their references from this playlist? " +
                    "Original audio files will not be deleted."
                )
            }
        }
        .alert(
            playlistBeingRenamed == nil ? "New Playlist" : "Rename Playlist",
            isPresented: $showPlaylistEditor
        ) {
            TextField("Playlist name", text: $playlistNameDraft)
            Button("Cancel", role: .cancel) {
                playlistBeingRenamed = nil
            }
            Button("Save") {
                savePlaylistName()
            }
        } message: {
            Text("Choose a name for your playlist.")
        }
    }

    // Stage 10D19: decode native URL and UTF-8 payload from NSItemProvider.
    // Data representation avoids ambiguous loadItem NSString / NSData bridging.
    private func loadD19DropPaths(
        _ providers: [NSItemProvider],
        completion: @escaping ([String]?) -> Void
    ) -> Bool {
        let types = ["public.utf8-plain-text", "public.plain-text"]
        if let provider = providers.first(where: { item in
            types.contains(where: { item.hasItemConformingToTypeIdentifier($0) })
        }), let type = types.first(where: { provider.hasItemConformingToTypeIdentifier($0) }) {
            print("FADER D19 DROP: decoding \(type)")
            provider.loadDataRepresentation(forTypeIdentifier: type) { data, error in
                guard error == nil, let data,
                      let value = String(data: data, encoding: .utf8) else {
                    print("FADER D19 DROP: text decode failed \(String(describing: error))")
                    completion(nil)
                    return
                }
                let paths: [String]
                if value.hasPrefix("fader-tracks:") {
                    let encoded = String(value.dropFirst("fader-tracks:".count))
                    guard let bytes = Data(base64Encoded: encoded),
                          let decoded = try? JSONDecoder().decode([String].self, from: bytes) else {
                        print("FADER D19 DROP: JSON payload invalid")
                        completion(nil)
                        return
                    }
                    paths = decoded
                } else if value.hasPrefix("fader-track:") {
                    paths = [String(value.dropFirst("fader-track:".count))]
                } else {
                    print("FADER D19 DROP: unknown text prefix")
                    completion(nil)
                    return
                }
                print("FADER D19 DROP: decoded paths=\(paths.count)")
                completion(paths)
            }
            return true
        }
        if let provider = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier("public.file-url")
        }) {
            print("FADER D19 DROP: decoding native file URL")
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, error in
                guard error == nil else { completion(nil); return }
                let url: URL?
                if let value = item as? URL { url = value }
                else if let value = item as? Data {
                    url = URL(dataRepresentation: value, relativeTo: nil)
                } else if let value = item as? String { url = URL(string: value) }
                else { url = nil }
                guard let url, url.isFileURL else {
                    print("FADER D19 DROP: native URL decode failed")
                    completion(nil)
                    return
                }
                completion([url.standardizedFileURL.path])
            }
            return true
        }
        print("FADER D19 DROP: no supported representation")
        return false
    }


    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            // FADER_UI_V1_SIDEBAR_STAGE2 — macOS adaptive appearance, visual-only.
            // STAGE10D24_V52_LIBRARY_VISUAL — typography only; system theme/accent unchanged.
            Text("Library")
                .font(.system(size: 16, weight: .bold, design: .rounded))
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.top, 18)
                .padding(.bottom, 12)

            Button {
                globalSearchSelected = false
                selectedPlaylistID = nil
                FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
                selectedFolder = nil
            } label: {
                Label("Current Queue", systemImage: "music.note.list")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(
                        !globalSearchSelected && selectedFolder == nil && selectedPlaylistID == nil
                        ? selectionAccent.opacity(0.30)
                        : Color.clear,
                        in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                    )
            }
    // FADER_QUEUE_SIDEBAR_DROP_V1
            .contentShape(Rectangle())
            .onDrop(
                of: ["public.file-url", "public.utf8-plain-text", "public.plain-text"],
                isTargeted: nil
            ) { providers in
                acceptCurrentQueueSidebarDrop(providers)
            }
            .buttonStyle(.plain)

            VSplitView {
                // Music Folders — independently scrollable
                VStack(alignment: .leading, spacing: 0) {
            HStack {
                Label("Music Folders", systemImage: "folder")
                    .font(.system(size: 11, weight: .bold))
                    .textCase(.uppercase)
                    .tracking(1.0)
                    .foregroundStyle(.secondary)

                Spacer()

                Button {
                    chooseFolder()
                } label: {
                    Image(systemName: "plus")
                }
                .buttonStyle(.borderless)
                .help("Add Music Folder")
            }
            .padding(.horizontal, 16)
            .padding(.top, 17)
            .padding(.bottom, 10)

            if folders.isEmpty {
                Text("Click + to add a folder")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 35)
                    .padding(.bottom, 10)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(folders, id: \.path) { folder in
                        folderRow(folder)
                    }
                }
            }

                }
                .frame(minHeight: 110)
                // STAGE10D33: keep the internal split line and zero spacing, but
                // clip the folders section to rounded corners so the seam matches
                // the rest of the premium layout.
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                // FADER_P08_EXPLICIT_DROP_V1: Finder -> saved Music Folders
                .contentShape(Rectangle())
                .dropDestination(for: URL.self) { urls, _ in
                    addDroppedFoldersToLibrary(urls)
                    return !urls.isEmpty
                }
                .background {
                    SplitPositionMemory(
                        name: "Fader.split.folders",
                        isVertical: false
                    )
                }


                // Playlists — independently scrollable
                VStack(alignment: .leading, spacing: 0) {


            HStack {
                Label("Playlists", systemImage: "list.bullet")
                    .font(.system(size: 11, weight: .bold))
                    .textCase(.uppercase)
                    .tracking(1.0)
                    .foregroundStyle(.secondary)

                Spacer()

                Button {
                    playlistBeingRenamed = nil
                    playlistNameDraft = ""
                    showPlaylistEditor = true
                } label: {
                    Image(systemName: "plus")
                }
                .buttonStyle(.borderless)
                .help("New Playlist")
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 9)

                    ScrollView {
                        VStack(alignment: .leading, spacing: 2) {
            ForEach(playlists) { playlist in
                playlistRow(playlist)
            }

            if playlists.isEmpty {
                Text("Click + to create a playlist")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .padding(.leading, 35)
                    .padding(.top, 8)
            }

                        }
                        .frame(maxWidth: .infinity,
                               alignment: .leading)
                    }
                }
                .frame(minHeight: 110)
                // STAGE10D33: keep the internal split line and zero spacing, but
                // clip the playlist section to rounded corners so the seam matches
                // the rest of the premium layout.
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .windowBackgroundColor),
                    in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
    }



    // MARK: - Playlist Management


    // MARK: - FADER_PLAYLIST_SAFETY_V1

    private var playlistSafetyDirectory: URL {
        FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        )[0]
        .appendingPathComponent(FaderStorage.supportComponent, isDirectory: true)
    }

    private var playlistSafetyURL: URL {
        playlistSafetyDirectory
            .appendingPathComponent("Playlists.json")
    }

    private var playlistPreviousURL: URL {
        playlistSafetyDirectory
            .appendingPathComponent("Playlists.previous.json")
    }

    private func validPlaylistData(_ data: Data) -> Bool {
        (try? JSONDecoder().decode(
            [FaderPlaylist].self,
            from: data
        )) != nil
    }

    private func loadPlaylists() {
        let defaults = FaderStorage.defaults

        let sources: [(String, Data?)] = [
            ("UserDefaults", defaults.data(forKey: playlistStorageKey)),
            ("Playlists.json", try? Data(contentsOf: playlistSafetyURL)),
            ("Playlists.previous.json",
             try? Data(contentsOf: playlistPreviousURL))
        ]

        for (source, data) in sources {
            guard let data,
                  let saved = try? JSONDecoder().decode(
                    [FaderPlaylist].self,
                    from: data
                  ) else {
                continue
            }

            playlists = saved

            if source != "UserDefaults" {
                defaults.set(data, forKey: playlistStorageKey)
                print("FADER PLAYLISTS: recovered from \\(source)")
            }

            return
        }

        // Do not overwrite invalid stored data with an empty list.
        if defaults.object(forKey: playlistStorageKey) != nil {
            print("FADER PLAYLISTS: WARNING - saved data unreadable")
        }
    }

    private func savePlaylists() {
        do {
            let data = try JSONEncoder().encode(playlists)
            let fm = FileManager.default

            try fm.createDirectory(
                at: playlistSafetyDirectory,
                withIntermediateDirectories: true
            )

            // Preserve the previous valid version.
            let previousData =
                (try? Data(contentsOf: playlistSafetyURL))
                ?? FaderStorage.defaults.data(
                    forKey: playlistStorageKey
                )

            if let previousData,
               validPlaylistData(previousData) {
                try previousData.write(
                    to: playlistPreviousURL,
                    options: .atomic
                )
            }

            // Write the new version atomically.
            try data.write(
                to: playlistSafetyURL,
                options: .atomic
            )

            FaderStorage.defaults.set(
                data,
                forKey: playlistStorageKey
            )

        } catch {
            print("FADER PLAYLISTS: backup failed: \\(error)")

            // Keep the existing persistence mechanism operational.
            if let data = try? JSONEncoder().encode(playlists) {
                FaderStorage.defaults.set(
                    data,
                    forKey: playlistStorageKey
                )
            }
        }

        // FADER_P298B_PLAYLIST_SEARCH_SYNC
        // Refresh all search sources after a playlist change.
        // The existing pending-sync mechanism handles overlapping requests.
        publishSearchSources()
        print("FADER SEARCH SYNC REQUESTED: Playlist change")
    }

    private func savePlaylistName() {
        let name = playlistNameDraft.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard !name.isEmpty else { return }

        if let id = playlistBeingRenamed,
           let index = playlists.firstIndex(where: { $0.id == id }) {
            playlists[index].name = name
        } else {
            let playlist = FaderPlaylist(
                id: UUID(), name: name, trackPaths: []
            )
            playlists.append(playlist)
            selectPlaylist(playlist)
        }

        playlistBeingRenamed = nil
        savePlaylists()
    }

    // FADER_GO_TO_PLAYING_P07_V1
    // Navigation only: do not change playback or current track.
    private func goToPlayingSource() {
        guard player.currentTrack != nil, !player.isFinderPlayback else { return }
        // FADER_GO_TO_PLAYING_GLOBAL_SEARCH_FIX_P276_V1
        // The content pane prioritizes Global Search over queue/folder/playlist.
        // Leave search before routing to the actual playing source.
        globalSearchSelected = false

        let defaults = FaderStorage.defaults
        let kind = defaults.string(forKey: "Fader.playingSourceKind") ?? ""
        let identifier = defaults.string(forKey: "Fader.playingSourceID") ?? ""

        if !player.isFolderPlayback {
            // The actual playback mode takes precedence over stale saved labels.
            selectedPlaylistID = nil
            selectedFolder = nil
            FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
            return
        }

        switch kind {
        case "playlist":
            guard let uuid = UUID(uuidString: identifier),
                  let playlist = playlists.first(where: { $0.id == uuid })
            else {
                player.status = "Playing playlist is no longer available."
                return
            }
            selectPlaylist(playlist)

        case "folder":
            guard let folder = folders.first(where: {
                $0.standardizedFileURL.path == identifier
            }) else {
                player.status = "Playing folder is no longer in Music Folders."
                return
            }
            selectedPlaylistID = nil
            FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
            selectFolder(folder)

        default:
            player.status = "Playing source is unknown. Start a track from a playlist or folder first."
        }
    }

    private func selectPlaylist(_ playlist: FaderPlaylist) {
        globalSearchSelected = false
        selectedPlaylistSongIndices.removeAll()
        selectedFolder = nil
        playlistSearchText = ""
        selectedPlaylistID = playlist.id

        FaderStorage.defaults.set(
            playlist.id.uuidString,
            forKey: lastPlaylistKey
        )
    }

    private func removePlaylist(_ playlist: FaderPlaylist) {
        playlists.removeAll { $0.id == playlist.id }

        if selectedPlaylistID == playlist.id {
            selectedPlaylistID = nil
            FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
        }

        playlistPendingRemoval = nil
        savePlaylists()
    }

    private func playlistRow(_ playlist: FaderPlaylist) -> some View {
        HStack(spacing: 12) {
            Button {
                selectPlaylist(playlist)
            } label: {
                Label(playlist.name, systemImage: "music.note.list")
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 34)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                playlistBeingRenamed = playlist.id
                playlistNameDraft = playlist.name
                showPlaylistEditor = true
            } label: {
                Image(systemName: "pencil")
            }
            .buttonStyle(.borderless)
            .help("Rename Playlist")

            Button {
                playlistPendingRemoval = playlist.id
            } label: {
                Image(systemName: "trash")
                    .foregroundStyle(.red)
            }
            .buttonStyle(.borderless)
            .help("Remove Playlist")
        }
        .padding(.trailing, 10)
        .background(
            selectedPlaylistID == playlist.id
                ? selectionAccent.opacity(0.30)
                : Color.clear,
            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
        )
        // STAGE10D16_PLAYLIST_DROP_SOURCE: use the complete playlist row as the drop target.
        // Existing acceptPlaylistTrackDrop enforces per-playlist deduplication.
        .contentShape(Rectangle())
        .onDrop(
            of: ["public.utf8-plain-text", "public.plain-text", "public.file-url"],
            isTargeted: nil
        ) { providers in
            acceptPlaylistTrackDrop(
                providers,
                into: playlist.id
            )
        }
        .confirmationDialog(
            "Remove this playlist from Fader?",
            isPresented: Binding(
                get: { playlistPendingRemoval == playlist.id },
                set: { if !$0 { playlistPendingRemoval = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove Playlist", role: .destructive) {
                removePlaylist(playlist)
            }
            Button("Cancel", role: .cancel) {
                playlistPendingRemoval = nil
            }
        } message: {
            Text("The original audio files will NOT be deleted!")
        }
    }



    private func initializeLibraryIfReady() {
        guard !player.isRestoringSession, !libraryStartupComplete else { return }
        libraryStartupComplete = true
            restoreFolders()
            loadPlaylists()
            preloadPlaylistMetadataFromCache()
            loadGlobalSearchIndex()

            if let savedID = FaderStorage.defaults.string(
                forKey: lastPlaylistKey
            ),
            let id = UUID(uuidString: savedID),
            playlists.contains(where: { $0.id == id }) {
                selectedPlaylistID = id
                selectedFolder = nil
            } else if let savedPath = FaderStorage.defaults.string(
                forKey: "Fader.lastSelectedFolder"
            ),
            let savedFolder = folders.first(where: {
                $0.path == savedPath
            }) {
                selectFolder(savedFolder)
            }
            loadRemainingFoldersInBackground()
    }

    // MARK: - FADER_GLOBAL_SEARCH_SAFE_P21_V1
    // Only read existing FolderCache records; NEVER scan Synology or write user data.
    private func loadGlobalSearchIndex() {
        if globalSearchLoading {
            globalSearchRefreshPending = true
            print("FADER SEARCH SYNC: queued while indexing")
            return
        }
        globalSearchLoading = true
        let savedFolders = folders
        // FADER_P274_PLAYLIST_ONLY_RECOVERY_V1
        // Snapshot persisted playlist paths on main, before background metadata reads.
        let playlistPaths = Set(playlists.flatMap { $0.trackPaths })
        DispatchQueue.global(qos: .utility).async {
            var unique: [String: Track] = [:]
            for folder in savedFolders {
                // This method only reads the local FolderCache on disk.
                // Prefer autonomous metadata only when enabled and valid.
                // Otherwise preserve the existing legacy JSON behavior.
                guard let tracks = (FolderCacheStore.loadDedupAutonomous(for: folder)
                    ?? FolderCacheStore.load(for: folder)) else { continue }
                for track in tracks {
                    let key = track.normalizedPath
                    unique[key] = track
                }
            }
            let indexed = unique.values.sorted {
                let a = ($0.artist + " " + $0.title).localizedLowercase
                let b = ($1.artist + " " + $1.title).localizedLowercase
                return a < b
            }
            // Recover metadata of songs referenced by saved playlists but absent
            // from the Music Folders cache. Only inspect these specific files;
            // never scan folders or modify original audio / playlist storage.
            var recovered: [String: Track] = [:]
            var unavailable = 0
            for path in playlistPaths where unique[path] == nil {
                let url = URL(fileURLWithPath: path)
                guard Library.audioExtensions.contains(url.pathExtension.lowercased()),
                      FileManager.default.fileExists(atPath: url.path) else {
                    unavailable += 1
                    continue
                }
                recovered[path] = Library.makeTrack(url: url)
            }
            // FADER_SQLITE_CACHE_SYNC_P271_V1
            // Existing Music Folders-only snapshot; keep P271 available unchanged.
            // Only the unified catalog is used by Global Search.
            DispatchQueue.main.async {
                globalSearchTracks = indexed
                globalSearchLoading = false
                // Populate the existing UI metadata cache; playlist-only songs
                // remain discoverable even after Fader is restarted.
                playlistMetadataCache.merge(recovered) { _, fresh in fresh }
                publishSearchSources()
                print("FADER PLAYLIST RECOVERY P274: recovered=\(recovered.count); " +
                      "unavailable=\(unavailable); playlistPaths=\(playlistPaths.count)")

                // Coalesce multiple requests into one fresh rebuild.
                if globalSearchRefreshPending {
                    globalSearchRefreshPending = false
                    print("FADER SEARCH SYNC: running queued refresh")
                    loadGlobalSearchIndex()
                }
            }
        }
    }

    private func publishSearchSources() {
        let activePaths = Set(playlists.flatMap { $0.trackPaths })
        let snapshot = FaderSearchSources.collect(folders: globalSearchTracks, queue: player.queue,
            activePlaylistPaths: activePaths, metadata: playlistMetadataCache)
        if snapshot.metadata.count != playlistMetadataCache.count { playlistMetadataCache = snapshot.metadata }
        searchModel.updateSources(snapshot.tracks)
    }

    // SQLite search: validate the unified index, then query off the main thread.
    private func scheduleSQLiteBetaSearch(_ raw: String) {
        searchModel.search(raw)
    }

    // FADER_SELECTION_TOGGLE_ALL_P29137
    // Selection only: never changes playback, the SQLite query or membership.
    private func selectGlobalSearchTrack(_ track: Track, shown: [Track]) {
        let path = track.normalizedPath
        let flags = NSEvent.modifierFlags
        if flags.contains(.shift),
           let anchor = globalSearchSelectionAnchor,
           let start = shown.firstIndex(where: { $0.normalizedPath == anchor }),
           let end = shown.firstIndex(where: { $0.normalizedPath == path }) {
            let range = min(start, end)...max(start, end)
            let paths = Set(range.map { shown[$0].normalizedPath })
            if flags.contains(.command) {
                selectedGlobalSearchPaths.formUnion(paths)
            } else {
                selectedGlobalSearchPaths = paths
            }
        } else if flags.contains(.command) {
            if !selectedGlobalSearchPaths.insert(path).inserted {
                selectedGlobalSearchPaths.remove(path)
            }
            globalSearchSelectionAnchor = path
        } else {
            if selectedGlobalSearchPaths.count == 1 && selectedGlobalSearchPaths.contains(path) {
                selectedGlobalSearchPaths.removeAll()
                globalSearchSelectionAnchor = nil
            } else {
                selectedGlobalSearchPaths = [path]
                globalSearchSelectionAnchor = path
            }
        }
    }

    private var globalSearchContent: some View {
        VStack(spacing: 0) {
            let query = globalSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
            let result = searchModel.result
            let resultsUpdating = result != nil && result?.query != query
            let shown = result?.shown ?? []
            let matchCount = result?.count ?? 0
            if query.isEmpty {
                ContentUnavailableView("Search your music", systemImage: "magnifyingglass",
                    description: Text("Indexed \(globalSearchTracks.count) songs from Music Folders. Enter an artist, title, or album."))
            } else if let error = searchModel.error {
                ContentUnavailableView("Search unavailable", systemImage: "exclamationmark.magnifyingglass", description: Text(error))
            } else if result == nil {
                ContentUnavailableView("Preparing music search…", systemImage: "exclamationmark.magnifyingglass",
                    description: Text("Preparing your library for search."))
            } else if matchCount == 0 {
                ContentUnavailableView("No results", systemImage: "music.note",
                    description: Text("Try a different title, artist, or album."))
            } else {
                VStack(spacing: 0) {
                    HStack {
                        Text(resultsUpdating
                             ? "Updating results… (previous: \(matchCount))"
                             : "\(matchCount) matches (showing \(shown.count))")
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    // FADER_P284_GLOBAL_SEARCH_SCROLLVIEW (diagnostic; reversible)
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(shown, id: \.url) { track in
                        HStack(spacing: 10) {
                            if let image = FaderArtworkImageCache.image(for: track) {
                                Image(nsImage: image)
                                    .resizable().scaledToFit()
                                    .frame(width: 40, height: 40)
                                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                            } else {
                                Image(systemName: "music.note")
                                    .foregroundStyle(.secondary)
                                    .frame(width: 40, height: 40)
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                Text(track.title)
                                    .font(.system(size: 13, weight: .medium))
                                    .lineLimit(1)
                                Text(track.artist).font(.caption)
                                    .foregroundStyle(.secondary).lineLimit(1)
                                if !track.album.isEmpty {
                                    Text(track.album).font(.caption2)
                                        .foregroundStyle(.tertiary).lineLimit(1)
                                }
                            }
                            Spacer(minLength: 0)
                            // FADER_GLOBAL_SEARCH_PLAY_P23_V1
                            Button {
                                toggleGlobalSearchTrack(track)
                            } label: {
                                Image(systemName: player.currentTrack?.normalizedPath == track.normalizedPath && player.isPlaying ? "pause.circle" : "play.circle")
                            }
                            .buttonStyle(.borderless)
                            .help("Play this song from Current Queue")
                            // FADER_GLOBAL_SEARCH_PLAYLIST_P24_V1
                            Menu {
                                let path = track.normalizedPath
                                let destinations = playlists.filter { playlist in
                                    !playlist.trackPaths.contains(path)
                                }
                                if destinations.isEmpty {
                                    Text("Already in all playlists")
                                } else {
                                    ForEach(destinations) { destination in
                                        Button(destination.name) {
                                            addQueueTracksToPlaylist(
                                                [track], id: destination.id
                                            )
                                        }
                                    }
                                }
                            } label: {
                                Image(systemName: "text.badge.plus")
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                            .disabled(playlists.isEmpty)
                            .help("Add to Playlist (no duplicates)")
                            // FADER_GLOBAL_SEARCH_GO_TO_SONG_P25_V1
                            Menu {
                                let songPath = track.normalizedPath
                                let containingFolders = folders.filter { folder in
                                    let folderPath = folder.standardizedFileURL.path
                                    return songPath.hasPrefix(folderPath + "/")
                                }
                                let containingPlaylists = playlists.filter { playlist in
                                    playlist.trackPaths.contains(songPath)
                                }
                                if containingFolders.isEmpty && containingPlaylists.isEmpty {
                                    Text("Not in saved Music Folders or Playlists")
                                } else {
                                    ForEach(containingFolders, id: \.path) { folder in
                                        Button("Folder: " + folder.lastPathComponent) {
                                            goToSongInFolder(track, folder: folder)
                                        }
                                    }
                                    if !containingFolders.isEmpty && !containingPlaylists.isEmpty {
                                        Divider()
                                    }
                                    ForEach(containingPlaylists) { playlist in
                                        Button("Playlist: " + playlist.name) {
                                            goToSongInPlaylist(track, playlist: playlist)
                                        }
                                    }
                                }
                            } label: {
                                Image(systemName: "arrow.up.right.square")
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                            .help("Go to Song in folder or playlist (does not play)")
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 3)
                        .contentShape(Rectangle())
                        .background(
                            selectedGlobalSearchPaths.contains(track.normalizedPath)
                                ? selectionAccent.opacity(0.30)
                                : (player.currentTrack?.normalizedPath == track.normalizedPath
                                    ? selectionAccent.opacity(0.08) : Color.clear),
                            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                        )
                        // FADER_SELECTION_TOGGLE_FIX_P29138
                        // Double-click uses the existing Global Search Play action.
                        .onTapGesture(count: 2) {
                            // STAGE10D39: maintain only the pale Now Playing highlight.
                            playGlobalSearchTrack(track)
                            // Clear selection on the next UI pass so the first click of
                            // a double-click cannot leave an ordinary selection behind.
                            DispatchQueue.main.async {
                                selectedGlobalSearchPaths.removeAll()
                                globalSearchSelectionAnchor = nil
                            }
                        }
                        .onTapGesture(count: 1) {
                            selectGlobalSearchTrack(track, shown: shown)
                        }
                        // FADER_GLOBAL_SEARCH_DRAG_P291310
                        // Same drag payload as Music Folders/Playlists; does not play or modify data.
                        .onDrag {
                            let clicked = track.normalizedPath
                            let paths = selectedGlobalSearchPaths.contains(clicked)
                                ? shown.map { $0.normalizedPath }
                                    .filter { selectedGlobalSearchPaths.contains($0) }
                                : [clicked]
                            let data = (try? JSONEncoder().encode(paths)) ?? Data("[]".utf8)
                            let payload = "fader-tracks:" + data.base64EncodedString()
                            return NSItemProvider(object: payload as NSString)
                        }

                        // FADER_ROW_SEPARATOR_P2944
                        Divider()
                            .opacity(0.35)
                            .padding(.leading, 28)
                    }
                        } // LazyVStack
                    } // ScrollView
                    .padding(.horizontal, 8)
                }
            }
        }
        // Keep the search field in the same top position for empty/results/loading.
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }


    // STAGE10D41: toolbar lives with the existing search state; no second engine.
    private var libraryToolbar: some View {
        HStack(spacing: 10) {
                Button {
                    showSidebar.toggle()
                } label: {
                    Image(systemName: "sidebar.left")
                }
                .buttonStyle(.borderless)
                .help(showSidebar ? "Hide Sidebar" : "Show Sidebar")

                Text("Music Library")
                    .foregroundStyle(.secondary)

                Spacer(minLength: 8)
                globalSearchToolbarField
                    .frame(minWidth: 200, idealWidth: 430, maxWidth: 600)
                Spacer(minLength: 8)

                Button {
                    showQueuePanel.toggle()
                } label: {
                    Label(showQueuePanel ? "Hide Queue Panel" : "Show Queue Panel",
                          systemImage: "sidebar.right")
                }
                .buttonStyle(.borderless)
                .help(showQueuePanel ? "Hide Playback & Queue panel" : "Show Playback & Queue panel")
            }
            // STAGE10D33: keep toolbar actions unchanged; refine the approved visual system.
            .font(.system(size: 12, weight: .medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(Color(nsColor: .windowBackgroundColor),
                        in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 2)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)

    }

    private var globalSearchToolbarCounts: (total: Int, matches: Int?) {
        let query = globalSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return (searchModel.total, searchModel.result?.query == query ? searchModel.result?.count : nil)
    }

    private var globalSearchToolbarField: some View {
        let counts = globalSearchToolbarCounts
        let searching = !globalSearchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Global Search · title, artist or album", text: $globalSearchText)
                .textFieldStyle(.plain)
                .accessibilityLabel("Global Search")
                .onExitCommand { resetGlobalSearch() }
            if globalSearchLoading || searchModel.isIndexing || searchModel.isSearching {
                ProgressView().controlSize(.mini)
            }
            Text(searching
                 ? "\(counts.matches.map { String($0) } ?? "…") / \(counts.total)"
                 : "\(counts.total) songs")
                .font(.caption).foregroundStyle(.secondary).monospacedDigit().fixedSize()
                .accessibilityLabel(searching ? "Search results / total songs" : "Total songs")
            if !globalSearchText.isEmpty {
                Button(action: resetGlobalSearch) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
                .help("Reset search")
                .accessibilityLabel("Reset search")
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color(nsColor: .textBackgroundColor),
                    in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous)
            .stroke(Color(nsColor: .separatorColor), lineWidth: 0.5))
    }

    private func resetGlobalSearch() {
        globalSearchText = ""
        globalSearchSelected = false
        selectedGlobalSearchPaths.removeAll()
        globalSearchSelectionAnchor = nil
    }

    // FADER_GLOBAL_SEARCH_PLAY_P23_V1
    // Reuse a queue entry by normalized file path. Never add a duplicate.
    // Audio playback is delegated to the existing Player implementation.
    private func toggleGlobalSearchTrack(_ track: Track) {
        if player.currentTrack?.normalizedPath == track.normalizedPath {
            player.togglePlay()
        } else {
            playGlobalSearchTrack(track)
        }
    }

    private func playGlobalSearchTrack(_ track: Track) {
        let path = track.normalizedPath
        if let existing = player.queue.firstIndex(where: {
            $0.normalizedPath == path
        }) {
            player.playQueueIndex(existing)
        } else {
            let newIndex = player.queue.count
            player.append([track])
            player.playQueueIndex(newIndex)
        }
    }

    // FADER_GLOBAL_SEARCH_GO_TO_SONG_P25_V1
    // Navigation only. No audio playback, queue mutations, or file writes.
    private func goToSongInFolder(_ track: Track, folder: URL) {
        let path = track.normalizedPath
        goToSongPath = path
        goToSongPlaylistID = nil
        folderSearchText = "" // Ensure the destination song isn't filtered out.
        selectedPlaylistID = nil
        FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
        selectedFolderTrackPaths = [path]
        folderSelectionAnchorPath = path
        selectFolder(folder) // Existing method loads cached tracks in background.
    }

    private func goToSongInPlaylist(_ track: Track, playlist: FaderPlaylist) {
        let path = track.normalizedPath
        guard let index = playlist.trackPaths.firstIndex(of: path) else { return }
        goToSongPath = path
        goToSongPlaylistID = playlist.id
        selectPlaylist(playlist)
        selectedPlaylistSongIndices = [index]
    }

    private func scrollToGoToSong(
        _ proxy: ScrollViewProxy,
        playlistID: UUID? = nil
    ) {
        guard let path = goToSongPath,
              goToSongPlaylistID == playlistID else { return }
        if playlistID == nil {
            guard selectedFolder != nil,
                  visibleFolderTracks.contains(where: {
                      $0.normalizedPath == path
                  }) else { return }
        } else {
            guard let id = playlistID,
                  selectedPlaylistID == id,
                  playlists.first(where: { $0.id == id })?.trackPaths.contains(path) == true
            else { return }
        }
        // Allow the List to layout before scrolling to the stable path ID.
        for delay in [0.12, 0.40] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard goToSongPath == path,
                      goToSongPlaylistID == playlistID else { return }
                proxy.scrollTo(path, anchor: .center)
                if delay == 0.40 {
                    goToSongPath = nil
                    goToSongPlaylistID = nil
                }
            }
        }
    }

    // MARK: - Background playlist metadata cache

    private func preloadPlaylistMetadataFromCache() {
        // Capture only the necessary information on the main thread.
        let savedFolders = folders

        let wantedPaths = Set(
            playlists.flatMap { $0.trackPaths }
        )

        guard !savedFolders.isEmpty, !wantedPaths.isEmpty else {
            return
        }

        // FADER_P29131_PROGRESSIVE_PLAYLIST_ARTWORK
        // Read the existing disk caches off the UI thread. Publish each
        // folder's matching metadata as soon as it is ready, instead of
        // waiting for every saved folder to finish loading.
        DispatchQueue.global(qos: .utility).async {
            for folder in savedFolders {
                // Same fail-closed autonomous read for playlist metadata.
                guard let tracks = (FolderCacheStore.loadDedupAutonomous(for: folder)
                    ?? FolderCacheStore.load(for: folder))
                else { continue }

                var batch: [String: Track] = [:]
                for track in tracks {
                    let path = track.normalizedPath
                    if wantedPaths.contains(path) {
                        batch[path] = track
                    }
                }

                guard !batch.isEmpty else { continue }
                let recovered = batch
                DispatchQueue.main.async {
                    playlistMetadataCache.merge(recovered) { _, fresh in fresh }
                    print("FADER PLAYLIST CACHE: restored batch of \(recovered.count) tracks")
                }
            }
        }
    }

    // MARK: - Playlist songs

    // FADER_QUEUE_TO_PLAYLIST_V2
    private var queueWithPlaylistActions: some View {
        PlaylistView(
            onAddFolder: chooseFolder,
            playlistDestinations: playlists.map {
                QueuePlaylistDestination(id: $0.id, name: $0.name)
            },
            addToPlaylist: { id, tracks in
                addQueueTracksToPlaylist(tracks, id: id)
            }
        )
    }

    private func addQueueTracksToPlaylist(_ tracks: [Track], id: UUID, insertionSlot: Int? = nil) {
        guard let index = playlists.firstIndex(where: { $0.id == id }) else { return }
        var existing = Set(playlists[index].trackPaths)
        var added = 0
        var slot = min(max(0, insertionSlot ?? playlists[index].trackPaths.count), playlists[index].trackPaths.count)
        for track in tracks {
            let path = track.normalizedPath
            if existing.insert(path).inserted {
                playlists[index].trackPaths.insert(path, at: slot)
                slot += 1
                added += 1
            }
            // Preserve metadata for songs imported directly from Finder
            // while the app is running, even if Current Queue is later cleared.
            playlistMetadataCache[path] = track
        }
        if added > 0 { savePlaylists() }
        player.status = "Added \(added) song(s) to \(playlists[index].name)."
    }

    private func addQueueToPlaylist(_ id: UUID) {
        guard let index = playlists.firstIndex(where: { $0.id == id })
        else { return }

        var existing = Set(playlists[index].trackPaths)

        for track in player.queue {
            let path = track.normalizedPath

            if existing.insert(path).inserted {
                playlists[index].trackPaths.append(path)
            }
        }

        savePlaylists()
    }

    private func removePlaylistSong(_ id: UUID, at index: Int) {
        guard let playlistIndex = playlists.firstIndex(
            where: { $0.id == id }
        ),
        playlists[playlistIndex].trackPaths.indices.contains(index)
        else { return }

        playlists[playlistIndex].trackPaths.remove(at: index)
        selectedPlaylistSongIndices.removeAll()
        savePlaylists()
    }


    private func removeSelectedPlaylistSongs(_ id: UUID) {
        guard let playlistIndex = playlists.firstIndex(
            where: { $0.id == id }
        ) else { return }

        let indices = selectedPlaylistSongIndices
            .filter {
                playlists[playlistIndex].trackPaths.indices.contains($0)
            }
            .sorted(by: >)

        guard !indices.isEmpty else { return }

        for index in indices {
            playlists[playlistIndex].trackPaths.remove(at: index)
        }

        selectedPlaylistSongIndices.removeAll()
        savePlaylists()

        player.status = "Removed \(indices.count) song(s) from playlist."
    }




    // MARK: - FADER_AUTO_CLEAN_REFRESH_V1


    // MARK: - FADER_PLAYLIST_SAFE_REFRESH_V2

    private func refreshSelectedPlaylist(_ id: UUID) {
        guard refreshingPlaylistID == nil,
              pendingPlaylistCleanup == nil,
              let playlist = playlists.first(where: { $0.id == id })
        else { return }

        let paths = playlist.trackPaths
        refreshingPlaylistID = id

        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default

            var directoryContents: [String: Set<String>] = [:]
            var inaccessible = Set<String>()
            var missing = Set<String>()
            var unchecked = 0

            for path in paths {
                let url = URL(fileURLWithPath: path)
                let directory = url.deletingLastPathComponent().path

                if directoryContents[directory] == nil &&
                   !inaccessible.contains(directory) {

                    do {
                        let names = try fm.contentsOfDirectory(
                            atPath: directory
                        )

                        // An unexpectedly empty network directory
                        // is not trusted as evidence of deletion.
                        if names.isEmpty {
                            inaccessible.insert(directory)
                        } else {
                            directoryContents[directory] = Set(names)
                        }
                    } catch {
                        inaccessible.insert(directory)
                    }
                }

                if let names = directoryContents[directory] {
                    if !names.contains(url.lastPathComponent) {
                        missing.insert(path)
                    }
                } else {
                    unchecked += 1
                }
            }

            DispatchQueue.main.async {
                guard refreshingPlaylistID == id else { return }
                refreshingPlaylistID = nil

                guard let current = playlists.first(where: {
                    $0.id == id
                }) else { return }

                // Never act on outdated results.
                guard current.trackPaths == paths else {
                    player.status =
                        "Playlist changed during refresh. Nothing removed."
                    return
                }

                // Any incomplete check blocks all removals.
                if unchecked > 0 {
                    player.status =
                        "Refresh incomplete: \(unchecked) song(s) " +
                        "could not be checked. Nothing removed."
                    return
                }

                if missing.isEmpty {
                    player.status =
                        "Playlist checked: all songs found."
                    return
                }

                pendingPlaylistCleanup = (
                    id: id,
                    missing: missing,
                    originalPaths: paths
                )

                player.status =
                    "\(missing.count) missing song(s). " +
                    "Confirmation required."
            }
        }
    }

    private func confirmPlaylistCleanup() {
        guard let pending = pendingPlaylistCleanup else {
            return
        }

        pendingPlaylistCleanup = nil

        guard let index = playlists.firstIndex(where: {
            $0.id == pending.id
        }) else {
            return
        }

        // Protect edits made after the check.
        guard playlists[index].trackPaths == pending.originalPaths else {
            player.status =
                "Playlist changed. Refresh again before removing songs."
            return
        }

        let before = playlists[index].trackPaths.count

        playlists[index].trackPaths.removeAll {
            pending.missing.contains($0)
        }

        let removed = before - playlists[index].trackPaths.count

        if removed > 0 {
            selectedPlaylistSongIndices.removeAll()
            savePlaylists()
        }

        player.status =
            "Removed \(removed) missing song(s) from playlist."
    }

    private func playlistPlaybackTracks(
        _ playlist: FaderPlaylist
    ) -> [(originalIndex: Int, track: Track)] {

        // Use metadata already loaded into memory.
        // Never access audio files or Synology inside SwiftUI body.

        let availableTracks = player.queue
            + player.folderPlaybackTracks
            + folderCache.values.flatMap { $0 }

        var tracksByPath: [String: Track] = [:]

        for track in availableTracks {
            let path = track.normalizedPath
            tracksByPath[path] = track
        }

        return playlist.trackPaths.enumerated().map {
            index, path in

            if let track = tracksByPath[path] {
                return (originalIndex: index, track: track)
            }

            if let cached = playlistMetadataCache[path] {
                return (originalIndex: index, track: cached)
            }

            // Lightweight placeholder: no file access.
            let url = URL(fileURLWithPath: path)

            let track = Track(
                url: url,
                title: url.deletingPathExtension().lastPathComponent,
                artist: "",
                album: ""
            )

            return (originalIndex: index, track: track)
        }
    }


    // FADER_PLAYING_SOURCE_P07_V1
    private func recordPlaybackSource(
        kind: String,
        name: String,
        identifier: String
    ) {
        FaderStorage.defaults.set(kind, forKey: "Fader.playingSourceKind")
        FaderStorage.defaults.set(name, forKey: "Fader.playingSourceName")
        FaderStorage.defaults.set(identifier, forKey: "Fader.playingSourceID")
    }

    private func playPlaylist(
        _ id: UUID,
        startingAt originalIndex: Int? = nil
    ) {
        guard let playlist = playlists.first(where: { $0.id == id })
        else { return }

        let available = playlistPlaybackTracks(playlist)
        let tracks = available.map { $0.track }

        guard !tracks.isEmpty else {
            player.status = "Playlist files are unavailable."
            return
        }

        let startIndex: Int

        if let originalIndex {
            guard let matchedIndex = available.firstIndex(where: {
                $0.originalIndex == originalIndex
            }) else {
                player.status = "Selected audio file is unavailable."
                return
            }

            startIndex = matchedIndex
        } else {
            startIndex = 0
        }

        // Uses the existing independent playback mechanism.
        // Current Queue is not modified.
        player.playFolder(tracks, startingAt: startIndex)
        recordPlaybackSource(
            kind: "playlist",
            name: playlist.name,
            identifier: playlist.id.uuidString
        )
    }

    // FADER_PLAYLIST_IDENTICAL_FOLDERS_V5: playlist-only helpers.
    private func playlistPlayingPath(
        playlist: FaderPlaylist,
        tracks: [(originalIndex: Int, track: Track)]
    ) -> String? {
        guard player.isFolderPlayback, !player.isFinderPlayback,
              player.folderPlaybackTracks.count == tracks.count,
              zip(player.folderPlaybackTracks, tracks).allSatisfy({ pair in
                  pair.0.normalizedPath ==
                      pair.1.track.normalizedPath
              }) else { return nil }
        return player.currentTrack?.normalizedPath
    }

    private func scrollToPlayingPlaylist(
        _ proxy: ScrollViewProxy,
        playlistID: UUID
    ) {
        guard let playlist = playlists.first(where: { $0.id == playlistID }),
              let path = playlistPlayingPath(
                  playlist: playlist,
                  tracks: playlistPlaybackTracks(playlist)
              ) else { return }
        for delay in [0.15, 0.45] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                guard let currentPlaylist = playlists.first(where: {
                    $0.id == playlistID
                }), playlistPlayingPath(
                    playlist: currentPlaylist,
                    tracks: playlistPlaybackTracks(currentPlaylist)
                ) == path else { return }
                if delay == 0.15 {
                    withAnimation(.easeInOut(duration: 0.20)) {
                        proxy.scrollTo(path, anchor: .center)
                    }
                } else {
                    proxy.scrollTo(path, anchor: .center)
                }
            }
        }
    }

    @State private var playlistInsertionPath: String? = nil
    @State private var playlistInsertionAfter = false

    // FADER_P299D2_NATIVE_EXPERIMENT

    private func selectPlaylistSong(_ index: Int, count: Int) {
        guard index >= 0 && index < count else { return }
        let flags = NSEvent.modifierFlags
        if flags.contains(.shift), let anchor = selectedPlaylistSongIndices.min() {
            let span = Set(min(anchor, index)...max(anchor, index))
            if flags.contains(.command) {
                selectedPlaylistSongIndices.formUnion(span)
            } else {
                selectedPlaylistSongIndices = span
            }
        } else if flags.contains(.command) {
            if !selectedPlaylistSongIndices.insert(index).inserted {
                selectedPlaylistSongIndices.remove(index)
            }
        } else {
            selectedPlaylistSongIndices = [index]
        }
    }

    private func playlistDetails(_ id: UUID) -> some View {
        Group {
            if let playlist = playlists.first(where: { $0.id == id }) {
                let tracks = playlistPlaybackTracks(playlist)
                let search = playlistSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
                let visibleTracks = search.isEmpty ? tracks : tracks.filter { item in
                    item.track.title.localizedStandardContains(search) ||
                    item.track.artist.localizedStandardContains(search) ||
                    item.track.album.localizedStandardContains(search)
                }

                VStack(spacing: 0) {
                    // STAGE10D44: playlist header presentation only.
                    Text(playlist.name)
                        .font(.title2).fontWeight(.semibold)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.top, 14)
                        .padding(.bottom, 10)

                    HStack(spacing: 8) {
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                            TextField("Search in this playlist", text: $playlistSearchText)
                                .textFieldStyle(.plain)
                                .accessibilityLabel("Search in this playlist")
                            if !playlistSearchText.isEmpty {
                                Button { playlistSearchText = "" } label: {
                                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                                }
                                .buttonStyle(.borderless)
                                .help("Clear search")
                                .accessibilityLabel("Clear playlist search")
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(Color(nsColor: .textBackgroundColor),
                                    in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                        .frame(minWidth: 60, maxWidth: .infinity)

                        Text(search.isEmpty
                             ? "\(playlist.trackPaths.count) tracks"
                             : "\(visibleTracks.count) / \(playlist.trackPaths.count)")
                            .font(.caption).foregroundStyle(.secondary)
                            .monospacedDigit().fixedSize()

                        HStack(spacing: 10) {
                            Button {
                                playPlaylist(id)
                            } label: {
                                Image(systemName: "play.circle")
                                    .font(.system(size: 22))
                                    .frame(width: 30, height: 30)
                            }
                            .disabled(playlist.trackPaths.isEmpty)
                            .help("Play Playlist")
                            .accessibilityLabel("Play Playlist")

                            Button {
                                addQueueToPlaylist(id)
                            } label: {
                                Image(systemName: "text.badge.plus")
                                    .font(.system(size: 13))
                                    .frame(width: 30, height: 30)
                            }
                            .disabled(player.queue.isEmpty)
                            .help("Add all Current Queue tracks to this playlist")
                            .accessibilityLabel("Add Current Queue")

                            Button {
                                refreshSelectedPlaylist(id)
                            } label: {
                                Group {
                                    if refreshingPlaylistID == id {
                                        ProgressView().controlSize(.small)
                                    } else {
                                        Image(systemName: "arrow.clockwise")
                                            .font(.system(size: 13))
                                    }
                                }
                                .frame(width: 30, height: 30)
                            }
                            .disabled(refreshingPlaylistID != nil)
                            .help("Remove deleted songs from this playlist")
                            .accessibilityLabel("Refresh Playlist")

                            Divider().frame(height: 18)

                            Button {
                                showRemoveSelectedConfirmation = true
                            } label: {
                                Image(systemName: "text.badge.minus")
                                    .font(.system(size: 13))
                                    .frame(width: 30, height: 30)
                            }
                            .disabled(selectedPlaylistSongIndices.isEmpty)
                            .help("Remove selected songs from this playlist")
                            .accessibilityLabel("Remove Selected (\(selectedPlaylistSongIndices.count))")
                        }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .fixedSize()
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 12)
                    .confirmationDialog(
                        "Remove selected songs from this playlist?",
                        isPresented: $showRemoveSelectedConfirmation,
                        titleVisibility: .visible
                    ) {
                        Button("Remove Selected", role: .destructive) {
                            removeSelectedPlaylistSongs(id)
                        }

                        Button("Cancel", role: .cancel) {
                            showRemoveSelectedConfirmation = false
                        }
                    } message: {
                        Text("The original audio files will NOT be deleted!")
                    }

                    Divider()

                    if playlist.trackPaths.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "music.note.list")
                                .font(.system(size: 42))
                                .foregroundStyle(.secondary)

                            Text("This playlist is empty.")
                                .foregroundStyle(.secondary)

                            Text("Add songs from Current Queue to begin.")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                        .frame(
                            maxWidth: .infinity,
                            maxHeight: .infinity
                        )
                        // Empty playlists have no native table to register a drop target.
                        // Make the ENTIRE central empty area receive the existing import
                        // handlers, including Finder and Fader's internal track payloads.
                        .contentShape(Rectangle())
                        .onDrop(of: ["public.file-url", "public.utf8-plain-text", "public.plain-text"],
                                isTargeted: nil) { providers in
                            acceptPlaylistTrackDrop(providers, into: id)
                        }
                    } else {
                        // FADER_PLAYLIST_PERFORMANCE_P09_V1
                        // Calculate active song once, not for every row.
                        let activePlaylistPath = playlistPlayingPath(
                            playlist: playlist,
                            tracks: tracks
                        )

                        // FADER_PLAYLIST_IDENTICAL_FOLDERS_V5
                        // Same SwiftUI List, row layout and separator styling
                        // as Music Folders; no changes to folderBrowser.
                            PlaylistNativeTable(
                                playlistID: id,
                                items: visibleTracks.map { item in
                                    PlaylistNativeTable.Item(
                                        path: item.track.normalizedPath,
                                        track: item.track
                                    )
                                },
                                playingPath: activePlaylistPath,
                                playbackRunning: player.isPlaying,
                                scrollRequest: goToPlayingRequest,
                                selectedIndices: Binding(
                                    get: { Set(visibleTracks.indices.filter {
                                        selectedPlaylistSongIndices.contains(visibleTracks[$0].originalIndex)
                                    }) },
                                    set: { rows in
                                        selectedPlaylistSongIndices = Set(rows.compactMap { row in
                                            visibleTracks.indices.contains(row) ? visibleTracks[row].originalIndex : nil
                                        })
                                    }
                                ),
                                play: { row in
                                    guard visibleTracks.indices.contains(row) else { return }
                                    playPlaylist(id, startingAt: visibleTracks[row].originalIndex)
                                },
                                playPause: { row in
                                    guard visibleTracks.indices.contains(row) else { return }
                                    let track = visibleTracks[row].track
                                    if activePlaylistPath == track.normalizedPath,
                                       player.currentTrack?.normalizedPath == track.normalizedPath {
                                        player.togglePlay()
                                    } else {
                                        playPlaylist(id, startingAt: visibleTracks[row].originalIndex)
                                    }
                                },
                                remove: { row in
                                    guard visibleTracks.indices.contains(row) else { return }
                                    removePlaylistSong(id, at: visibleTracks[row].originalIndex)
                                },
                                move: { rows, slot in
                                    guard search.isEmpty else { return }
                                    guard let sourceRow = rows.first,
                                          tracks.indices.contains(sourceRow),
                                          slot >= 0,
                                          slot <= tracks.count else { return }

                                    let sourcePath = tracks[sourceRow]
                                        .track.normalizedPath

                                    if slot < tracks.count {
                                        let targetPath = tracks[slot]
                                            .track.normalizedPath
                                        _ = reorderPlaylistUsingEngine(
                                            playlistID: id,
                                            sourcePath: sourcePath,
                                            destinationPath: targetPath,
                                            after: false
                                        )
                                    } else if let last = tracks.last {
                                        _ = reorderPlaylistUsingEngine(
                                            playlistID: id,
                                            sourcePath: sourcePath,
                                            destinationPath: last.track.url
                                                .standardizedFileURL.path,
                                            after: true
                                        )
                                    }
                                },
                                addToQueue: { row in
                                    guard visibleTracks.indices.contains(row) else { return }
                                    player.append([visibleTracks[row].track])
                                },
                                copyToPlaylist: { row, destinationID in
                                    guard visibleTracks.indices.contains(row) else { return }
                                    addFolderTracksToPlaylist(
                                        [visibleTracks[row].track],
                                        playlistID: destinationID
                                    )
                                },
                                copyDestinations: playlists
                                    .filter { $0.id != id }
                                    .map {
                                        PlaylistNativeTable.CopyDestination(
                                            id: $0.id,
                                            name: $0.name
                                        )
                                    },
                                 allowsReordering: search.isEmpty,
                                 receiveExternalPaths: { paths, dropRow in
                                     // External COPY only. Native same-playlist MOVE is untouched.
                                     let normalized = paths.map {
                                         URL(fileURLWithPath: $0).standardizedFileURL.path
                                     }
                                     let known = folderTracks + player.queue
                                         + player.folderPlaybackTracks
                                         + folderCache.values.flatMap { $0 }
                                         + globalSearchTracks
                                     var byPath = Dictionary(
                                         known.map { ($0.normalizedPath, $0) },
                                         uniquingKeysWith: { first, _ in first }
                                     )
                                     for (path, track) in playlistMetadataCache {
                                         if byPath[path] == nil { byPath[path] = track }
                                     }
                                     let incoming: [Track] = normalized.map { path in
                                         if let track = byPath[path] { return track }
                                         let url = URL(fileURLWithPath: path)
                                         return Track(url: url,
                                                      title: url.deletingPathExtension().lastPathComponent,
                                                      artist: "", album: "")
                                     }
                                     // A filtered table uses visible indices; convert to
                                     // the original playlist insertion slot.
                                     let slot = dropRow < visibleTracks.count
                                         ? visibleTracks[dropRow].originalIndex
                                         : playlist.trackPaths.count
                                     addQueueTracksToPlaylist(incoming, id: id, insertionSlot: slot)
                                 }
                            )


                    }
                }
            } else {
                queueWithPlaylistActions
            }
        }
        .onDrop(of: ["public.file-url"], isTargeted: nil) { providers in
            acceptFinderPlaylistDrop(providers, into: id)
        }
    }
// MARK: - Add Music Folder to Playlist

    private func addFolderTracksToPlaylist(
        _ tracks: [Track],
        playlistID: UUID
    ) {
        guard let index = playlists.firstIndex(
            where: { $0.id == playlistID }
        ) else { return }

        var existing = Set(playlists[index].trackPaths)
        var added = 0

        for track in tracks {
            let path = track.normalizedPath

            if existing.insert(path).inserted {
                playlists[index].trackPaths.append(path)
                added += 1
            }
        }

        savePlaylists()

        player.status = "Added \\(added) track(s) to \\(playlists[index].name)."
    }


    // MARK: - Playlist Drag & Drop

    // FADER_MULTI_FOLDER_DROP_V1
    private func folderDragProvider(for track: Track) -> NSItemProvider {
        let clickedPath = track.normalizedPath
        let paths: [String]
        if selectedFolderTrackPaths.contains(clickedPath) {
            paths = folderTracks.map { $0.normalizedPath }
                .filter { selectedFolderTrackPaths.contains($0) }
        } else {
            paths = [clickedPath]
        }

        let data = (try? JSONEncoder().encode(paths)) ?? Data("[]".utf8)
        let payload = "fader-tracks:" + data.base64EncodedString()
        return NSItemProvider(object: payload as NSString)
    }

    // FADER_FINDER_PLAYLIST_DROP_V1
    // Accept Finder files and folders without touching Current Queue or audio playback.
    private func acceptFinderPlaylistDrop(
        _ providers: [NSItemProvider],
        into playlistID: UUID
    ) -> Bool {
        guard playlists.contains(where: { $0.id == playlistID }) else { return false }

        let fileProviders = providers.enumerated().filter {
            $0.element.hasItemConformingToTypeIdentifier("public.file-url")
        }
        guard !fileProviders.isEmpty else { return false }

        let group = DispatchGroup()
        let serial = DispatchQueue(label: "Fader.FinderPlaylistDrop.URLs")
        var resolved: [(Int, URL)] = []

        for (position, provider) in fileProviders {
            group.enter()
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) {
                item, error in
                defer { group.leave() }
                guard error == nil, let item = item else { return }

                let url: URL?
                if let value = item as? URL {
                    url = value
                } else if let value = item as? Data {
                    url = URL(dataRepresentation: value, relativeTo: nil)
                } else if let value = item as? String {
                    url = URL(string: value)
                } else {
                    url = nil
                }
                guard let file = url, file.isFileURL else { return }
                serial.sync { resolved.append((position, file)) }
            }
        }

        group.notify(queue: DispatchQueue.global(qos: .userInitiated)) {
            let urls = serial.sync {
                resolved.sorted { $0.0 < $1.0 }.map { $0.1 }
            }
            var imported: [Track] = []

            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }

                var isDirectory: ObjCBool = false
                guard FileManager.default.fileExists(
                    atPath: url.path,
                    isDirectory: &isDirectory
                ) else { continue }

                if isDirectory.boolValue {
                    imported.append(contentsOf: Library.scan(folder: url))
                } else if Library.isPlaylist(url: url) {
                    imported.append(contentsOf: Library.loadPlaylist(url: url))
                } else if Library.audioExtensions.contains(url.pathExtension.lowercased()) {
                    imported.append(Library.makeTrack(url: url))
                }
            }

            DispatchQueue.main.async {
                guard playlists.contains(where: { $0.id == playlistID }) else { return }
                if imported.isEmpty {
                    player.status = "No supported audio files found in Finder drop."
                    return
                }
                // Cache metadata for files not already included in Music Folders.
                for track in imported {
                    playlistMetadataCache[track.normalizedPath] = track
                }
                // Reuse the existing path deduplication and persistent save logic.
                addFolderTracksToPlaylist(imported, playlistID: playlistID)
            }
        }
        return true
    }


    // FADER_QUEUE_SIDEBAR_DROP_V1
    // Music Folders -> Current Queue, using the sidebar.
    // Original audio files remain untouched.
    private func acceptCurrentQueueSidebarDrop(
        _ providers: [NSItemProvider]
    ) -> Bool {
        // Native single-file URL, or the encoded multi-track list.
        guard loadD19DropPaths(providers, completion: { paths in
            DispatchQueue.main.async {
                guard let paths else { return }


                // Resolve both folder and playlist-sourced drag payloads.
                // No filesystem reads, playlist edits, or metadata scans.
                let known = folderTracks + folderCache.values.flatMap { $0 }
                    + player.folderPlaybackTracks + player.queue
                    + globalSearchTracks // FADER_GLOBAL_SEARCH_DRAG_P291310
                var tracksByPath = Dictionary(
                    known.map { ($0.normalizedPath, $0) },
                    uniquingKeysWith: { first, _ in first }
                )
                for (path, track) in playlistMetadataCache {
                    if tracksByPath[path] == nil { tracksByPath[path] = track }
                }
                // Playlist-only entries might not be in Music Folders.
                // playlistPlaybackTracks uses in-memory metadata or safe placeholders.
                for playlist in playlists {
                    for entry in playlistPlaybackTracks(playlist) {
                        let track = entry.track
                        let key = track.normalizedPath
                        if tracksByPath[key] == nil { tracksByPath[key] = track }
                    }
                }

                // FADER_QUEUE_SIDEBAR_DEDUP_V2
                // Exclude paths already in Current Queue and
                // duplicates within the same incoming selection.
                var knownPaths = Set(player.queue.map {
                    $0.normalizedPath
                })

                let tracks = paths.compactMap { path -> Track? in
                    let normalized = URL(fileURLWithPath: path)
                        .standardizedFileURL.path

                    guard let track = tracksByPath[normalized],
                          knownPaths.insert(normalized).inserted
                    else { return nil }

                    return track
                }

                let skipped = paths.count - tracks.count

                if !tracks.isEmpty {
                    player.append(tracks)
                }

                if skipped > 0 {
                    player.status = "Added \\(tracks.count) song(s); skipped \\(skipped) duplicate(s)."
                } else {
                    player.status = "Added \\(tracks.count) song(s) to Current Queue."
                }

                print(
                    "FADER QUEUE SIDEBAR DROP: added",
                    tracks.count,
                    "track(s), skipped",
                    skipped,
                    "duplicate(s)"
                )
            }
        }) else { return false }
        return true
    }

    private func acceptPlaylistTrackDrop(
        _ providers: [NSItemProvider],
        into playlistID: UUID
    ) -> Bool {
        guard playlists.contains(where: { $0.id == playlistID }) else { return false }
        // Finder URLs still use the existing importer; never touch audio originals.
        if providers.contains(where: {
            $0.hasItemConformingToTypeIdentifier("public.file-url")
        }) {
            return acceptFinderPlaylistDrop(providers, into: playlistID)
        }
        guard loadD19DropPaths(providers, completion: { rawPaths in
            DispatchQueue.main.async {
                guard let rawPaths else { return }
                guard let index = playlists.firstIndex(where: { $0.id == playlistID }) else { return }
                // Resolve only metadata already known to Fader; never scan files on drop.
                let known = folderTracks + player.queue + player.folderPlaybackTracks
                    + folderCache.values.flatMap { $0 } + globalSearchTracks
                var knownPaths = Set(known.map { $0.normalizedPath })
                knownPaths.formUnion(playlistMetadataCache.keys)
                // Preserve references from saved playlists, including playlist-only tracks.
                knownPaths.formUnion(playlists.flatMap { $0.trackPaths })
                var existing = Set(playlists[index].trackPaths.map {
                    URL(fileURLWithPath: $0).standardizedFileURL.path
                })
                var added = 0
                for rawPath in rawPaths {
                    let path = URL(fileURLWithPath: rawPath).standardizedFileURL.path
                    guard knownPaths.contains(path), existing.insert(path).inserted else { continue }
                    playlists[index].trackPaths.append(path)
                    added += 1
                }
                if added > 0 { savePlaylists() }
                player.status = "Added \(added) track(s) to \(playlists[index].name)."
                print("FADER D18 PLAYLIST DROP: added=\(added) incoming=\(rawPaths.count)")
            }
        }) else { return false }
        return true
    }


    // FADER_P299B3_PROVIDER_SOURCE
    // Read the source identity from the drag payload itself.
    // Never infer the source from SwiftUI selection or hover state.
    private func acceptPlaylistEngineDrop(
        _ providers: [NSItemProvider],
        playlistID: UUID,
        destinationPath: String,
        after: Bool
    ) -> Bool {
        guard let provider = providers.first(where: {
            $0.canLoadObject(ofClass: NSString.self)
        }) else {
            print("FADER REORDER: missing drag payload")
            return false
        }

        let prefix = "fader-reorder:" + playlistID.uuidString + ":"

        _ = provider.loadObject(ofClass: NSString.self) { object, error in
            guard let value = object as? String,
                  value.hasPrefix(prefix) else {
                print("FADER REORDER: invalid drag payload")
                return
            }

            let sourcePath = String(value.dropFirst(prefix.count))

            DispatchQueue.main.async {
                print(
                    "FADER REORDER PAYLOAD: "
                    + URL(fileURLWithPath: sourcePath).lastPathComponent
                )

                _ = reorderPlaylistUsingEngine(
                    playlistID: playlistID,
                    sourcePath: sourcePath,
                    destinationPath: destinationPath,
                    after: after
                )
            }
        }

        return true
    }

    // FADER_P299B2_LOCKED_SOURCE
    // Reorder references only. No audio file operations.
    private func reorderPlaylistUsingEngine(
        playlistID: UUID,
        sourcePath: String,
        destinationPath: String,
        after: Bool
    ) -> Bool {
        guard let index = playlists.firstIndex(where: { $0.id == playlistID }) else {
            print("FADER REORDER: playlist not found")
            return false
        }

        let original = playlists[index].trackPaths

        guard let destinationIndex = original.firstIndex(of: destinationPath),
              original.contains(sourcePath) else {
            print("FADER REORDER: source or destination missing")
            return false
        }

        let slot = destinationIndex + (after ? 1 : 0)

        guard let reordered = FaderReorderEngine.reorder(
            original,
            moving: sourcePath,
            toInsertionSlot: slot
        ) else {
            print("FADER REORDER: engine rejected move")
            return false
        }

        guard reordered != original else {
            print("FADER REORDER: unchanged position")
            return true
        }

        // FADER_P299C_SMOOTH_DROP
        // Animate only the visible reorder. Preserve persistence logic.
        withAnimation(.easeInOut(duration: 0.18)) {
            playlists[index].trackPaths = reordered
            selectedPlaylistSongIndices.removeAll()
        }
        savePlaylists()
        player.status = "Playlist order updated."

        let finalIndex = reordered.firstIndex(of: sourcePath) ?? -1

        print(
            "FADER REORDER SUCCESS:"
            + " destination=\(destinationIndex)"
            + " after=\(after)"
            + " final=\(finalIndex)"
            + " count=\(reordered.count)"
        )

        return true
    }

    private func acceptPlaylistReorderDrop(
        _ providers: [NSItemProvider],
        playlistID: UUID,
        destinationPath: String,
        placeAfter: Bool
    ) -> Bool {
        guard let provider = providers.first(where: {
            $0.canLoadObject(ofClass: NSString.self)
        }) else {
            return false
        }

        _ = provider.loadObject(ofClass: NSString.self) {
            object, _ in

            guard let value = object as? String else { return }

            // Build the prefix explicitly to avoid escaping errors.
            let prefix = "fader-reorder:"
                + playlistID.uuidString + ":"

            guard value.hasPrefix(prefix) else { return }

            let sourcePath = String(value.dropFirst(prefix.count))

            DispatchQueue.main.async {
                guard let playlistIndex = playlists.firstIndex(
                    where: { $0.id == playlistID }
                ) else {
                    return
                }

                guard let sourceIndex = playlists[playlistIndex]
                    .trackPaths.firstIndex(of: sourcePath),
                      sourcePath != destinationPath else {
                    return
                }

                // FADER_P299A_V23_EXPLICIT_INSERTION
                // Calculate the insertion slot before removing the source.
                let paths = playlists[playlistIndex].trackPaths

                guard let destinationOriginalIndex = paths.firstIndex(
                    of: destinationPath
                ) else {
                    print("FADER MOVE: destination not found")
                    return
                }

                let insertionSlot = destinationOriginalIndex
                    + (placeAfter ? 1 : 0)

                // Removing an earlier row shifts the insertion slot left.
                let adjustedSlot = insertionSlot
                    - (sourceIndex < insertionSlot ? 1 : 0)

                guard adjustedSlot >= 0,
                      adjustedSlot < paths.count else {
                    print("FADER MOVE: invalid insertion position")
                    return
                }

                if adjustedSlot == sourceIndex {
                    print(
                        "FADER MOVE: NO-OP source=\(sourceIndex)"
                        + " destination=\(destinationOriginalIndex)"
                        + " after=\(placeAfter)"
                    )
                    return
                }

                let movedPath = playlists[playlistIndex]
                    .trackPaths.remove(at: sourceIndex)

                playlists[playlistIndex].trackPaths.insert(
                    movedPath,
                    at: adjustedSlot
                )

                print(
                    "FADER MOVE RESULT:"
                    + " source=\(sourceIndex)"
                    + " destination=\(destinationOriginalIndex)"
                    + " after=\(placeAfter)"
                    + " final=\(adjustedSlot)"
                    + " count=\(playlists[playlistIndex].trackPaths.count)"
                )

                selectedPlaylistSongIndices.removeAll()
                savePlaylists()

                player.status = "Playlist order updated."
            }
        }

        return true
    }

    @State private var folderPendingFullRescan: URL? = nil

    @State private var folderActionsMenuPath: String? = nil

    // MARK: - Folder action buttons

    private func folderRow(_ folder: URL) -> some View {
        HStack(spacing: 12) {
            Button {
                selectedPlaylistID = nil
                FaderStorage.defaults.removeObject(forKey: lastPlaylistKey)
                selectFolder(folder)
            } label: {
                Label(folder.lastPathComponent, systemImage: "folder")
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 34)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(folder.path)

            // FADER_P2944O_CUSTOM_POPOVER
            Button {
                folderActionsMenuPath =
                    folderActionsMenuPath == folder.path
                    ? nil : folder.path
            } label: {
                Image(systemName: "ellipsis")
                    .frame(width: 28, height: 24)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .help("Folder Actions")
            .popover(
                isPresented: Binding(
                    get: { folderActionsMenuPath == folder.path },
                    set: {
                        if !$0 { folderActionsMenuPath = nil }
                    }
                ),
                arrowEdge: .trailing
            ) {
                VStack(alignment: .leading, spacing: 0) {
                    Button {
                        folderActionsMenuPath = nil
                        refreshFolder(folder)
                    } label: {
                        Label {
                            Text("Smart Refresh")
                        } icon: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }

                    Button {
                        folderActionsMenuPath = nil
                        DispatchQueue.main.async {
                            folderPendingFullRescan = folder
                        }
                    } label: {
                        Label {
                            Text("Full Rescan")
                        } icon: {
                            Image(systemName: "arrow.triangle.2.circlepath")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }

                    Divider()
                        .padding(.vertical, 5)

                    Button {
                        folderActionsMenuPath = nil
                        DispatchQueue.main.async {
                            folderPendingRemoval = folder
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                                .frame(width: 18)

                            Text("Remove from Library")
                                .foregroundStyle(.primary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                }
                .buttonStyle(.plain)
                .padding(.vertical, 6)
                .frame(minWidth: 210)
            }
        }
        .padding(.trailing, 10)
        .background(
            selectedFolder == folder.path
            ? selectionAccent.opacity(0.30)
            : Color.clear,
            in: RoundedRectangle(cornerRadius: 9, style: .continuous)
        )
        .confirmationDialog(
            "Are you sure you want to remove this folder from Fader?",
            isPresented: Binding(
                get: { folderPendingRemoval?.path == folder.path },
                set: { if !$0 { folderPendingRemoval = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove Folder", role: .destructive) {
                if let target = folderPendingRemoval {
                    removeFolder(target)
                }
                folderPendingRemoval = nil
            }
            Button("Cancel", role: .cancel) {
                folderPendingRemoval = nil
            }
        } message: {
            Text("Removing this folder will NOT delete the original audio files!")
        }
        .confirmationDialog(
            "Full Rescan this folder?",
            isPresented: Binding(
                get: { folderPendingFullRescan?.path == folder.path },
                set: { if !$0 { folderPendingFullRescan = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Full Rescan") {
                if let target = folderPendingFullRescan {
                    fullRescanFolder(target)
                }
                folderPendingFullRescan = nil
            }
            Button("Cancel", role: .cancel) {
                folderPendingFullRescan = nil
            }
        } message: {
            Text("This will re-read all audio files and rebuild the folder cache. It may take some time.")
        }
        .contextMenu {
            Button("Refresh Folder", systemImage: "arrow.clockwise") {
                refreshFolder(folder)
            }

            // FADER_P2944L_FULL_RESCAN_MENU
            Button("Full Rescan Folder", systemImage: "arrow.triangle.2.circlepath") {
                folderPendingFullRescan = folder
            }

            Divider()

            Button("Remove from Library", systemImage: "trash") {
                folderPendingRemoval = folder
            }
        }
    }


    // MARK: - FADER_MANUAL_MULTI_SELECTION_V1

    private func selectFolderTrack(_ track: Track) {
        let path = track.normalizedPath
        let flags = NSEvent.modifierFlags

        if flags.contains(.shift),
           let anchor = folderSelectionAnchorPath,
           let first = folderTracks.firstIndex(where: {
               $0.normalizedPath == anchor
           }),
           let last = folderTracks.firstIndex(where: {
               $0.normalizedPath == path
           }) {

            let range = min(first, last)...max(first, last)

            let paths = Set(range.map {
                folderTracks[$0].normalizedPath
            })

            if flags.contains(.command) {
                selectedFolderTrackPaths.formUnion(paths)
            } else {
                selectedFolderTrackPaths = paths
            }

        } else if flags.contains(.command) {
            if !selectedFolderTrackPaths.insert(path).inserted {
                selectedFolderTrackPaths.remove(path)
            }
            folderSelectionAnchorPath = path

        } else {
            selectedFolderTrackPaths = [path]
            folderSelectionAnchorPath = path
        }
    }

    // Filters cached metadata only; never reads files from Synology on the UI thread.
    private var visibleFolderTracks: [Track] {
        let query = folderSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return folderTracks }
        return folderTracks.filter { track in
            track.title.localizedStandardContains(query) ||
            track.artist.localizedStandardContains(query) ||
            track.album.localizedStandardContains(query)
        }
    }

    // STAGE10D42: folder-only search and existing actions, presented as icons.
    private func folderBrowser(path: String) -> some View {
        let visible = visibleFolderTracks
        return VStack(spacing: 0) {
            Text(URL(fileURLWithPath: path).lastPathComponent)
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 10)

            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Search in this folder", text: $folderSearchText)
                        .textFieldStyle(.plain)
                        .accessibilityLabel("Search in this folder")
                    if !folderSearchText.isEmpty {
                        Button { folderSearchText = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .help("Clear folder search")
                        .accessibilityLabel("Clear folder search")
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Color(nsColor: .textBackgroundColor),
                            in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                .frame(minWidth: 60, maxWidth: .infinity)

                Text(folderSearchText.isEmpty
                     ? "\(folderTracks.count) tracks"
                     : "\(visible.count) / \(folderTracks.count)")
                    .font(.caption).foregroundStyle(.secondary)
                    .monospacedDigit().fixedSize()

                // STAGE10D43: three distinct controls; existing action bodies retained.
                HStack(spacing: 12) {
                    Button {
                        player.playFolder(folderTracks)
                        recordPlaybackSource(
                            kind: "folder",
                            name: URL(fileURLWithPath: path).lastPathComponent,
                            identifier: path
                        )
                    } label: {
                        Image(systemName: "play.circle")
                            .font(.system(size: 22))
                            .frame(width: 30, height: 30)
                    }
                    .disabled(folderTracks.isEmpty)
                    .help("Play Folder")
                    .accessibilityLabel("Play Folder")

                    Menu {
                        Menu("All songs (\(folderTracks.count))") {
                            ForEach(playlists) { playlist in
                                Button(playlist.name) {
                                    addFolderTracksToPlaylist(
                                        folderTracks,
                                        playlistID: playlist.id
                                    )
                                }
                            }
                        }
                        .disabled(folderTracks.isEmpty || playlists.isEmpty)

                        Menu("Selected songs (\(selectedFolderTrackPaths.count))") {
                            ForEach(playlists) { playlist in
                                Button(playlist.name) {
                                    let selectedTracks = folderTracks.filter {
                                        selectedFolderTrackPaths.contains(
                                            $0.normalizedPath
                                        )
                                    }
                                    addFolderTracksToPlaylist(
                                        selectedTracks,
                                        playlistID: playlist.id
                                    )
                                }
                            }
                        }
                        .disabled(selectedFolderTrackPaths.isEmpty || playlists.isEmpty)
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "text.badge.plus")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 22, height: 22)
                                .frame(width: 30, height: 30)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .semibold))
                        }
                    }
                    .disabled(folderTracks.isEmpty || playlists.isEmpty)
                    .menuStyle(.borderlessButton)
                    .controlSize(.large)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Add to Playlist — all or selected songs")
                    .accessibilityLabel("Add to Playlist")

                    Menu {
                        Button("All songs (\(folderTracks.count))") {
                            player.append(folderTracks)
                        }
                        .disabled(folderTracks.isEmpty)

                        Button("Selected songs (\(selectedFolderTrackPaths.count))") {
                            let chosen = folderTracks.filter {
                                selectedFolderTrackPaths.contains($0.normalizedPath)
                            }
                            guard !chosen.isEmpty else { return }
                            player.append(chosen)
                        }
                        .disabled(selectedFolderTrackPaths.isEmpty)
                    } label: {
                        HStack(spacing: 3) {
                            Image(systemName: "music.note")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 22, height: 22)
                                .overlay(alignment: .topTrailing) {
                                    Image(systemName: "plus")
                                        .font(.system(size: 9, weight: .bold))
                                        .offset(x: 6, y: -2)
                                }
                                .frame(width: 30, height: 30)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 8, weight: .semibold))
                        }
                    }
                    .disabled(folderTracks.isEmpty)
                    .menuStyle(.borderlessButton)
                    .controlSize(.large)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("Add to Queue — all or selected songs")
                    .accessibilityLabel("Add to Queue")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .fixedSize()
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 12)

            Divider()

            if isLoading {
                VStack {
                    Spacer()
                    if let progress = initialImportProgress, progress.total > 0 {
                        ProgressView(value: Double(progress.completed), total: Double(progress.total))
                            .frame(maxWidth: 280)
                        Text("Reading songs: \(progress.completed) / \(progress.total)")
                            .foregroundStyle(.secondary)
                    } else {
                        ProgressView("Finding audio files...")
                    }
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            } else if folderTracks.isEmpty {
                ContentUnavailableView(
                    "No Audio Files",
                    systemImage: "music.note",
                    description: Text(
                        initialImportError ?? "The folder is empty or unavailable."
                    )
                )

            } else {
                // FADER_P29111_FOLDER_NATIVE_CONNECTED
                FolderNativeTable(
                    tracks: visible,
                    playingPath: player.isFolderPlayback && !player.isFinderPlayback
                        ? player.currentTrack?.normalizedPath : nil,
                    playbackRunning: player.isPlaying,
                    scrollToPath: (goToSongPath != nil && goToSongPlaylistID == nil)
                        ? goToSongPath
                        : (player.isFolderPlayback && !player.isFinderPlayback
                            ? player.currentTrack?.normalizedPath : nil),
                    scrollRequest: goToPlayingRequest,
                    selectedPaths: $selectedFolderTrackPaths,
                    play: { track in
                        guard let index = folderTracks.firstIndex(of: track) else { return }
                        selectedFolderTrackPaths = [track.normalizedPath]
                        folderSelectionAnchorPath = track.normalizedPath
                        player.playFolder(folderTracks, startingAt: index)
                        recordPlaybackSource(
                            kind: "folder",
                            name: URL(fileURLWithPath: path).lastPathComponent,
                            identifier: path
                        )
                    },
                    playPause: { track in
                        guard let index = folderTracks.firstIndex(of: track) else { return }
                        if player.isFolderPlayback, !player.isFinderPlayback,
                           player.currentTrack?.normalizedPath == track.normalizedPath {
                            player.togglePlay()
                        } else {
                            selectedFolderTrackPaths = [track.normalizedPath]
                            folderSelectionAnchorPath = track.normalizedPath
                            player.playFolder(folderTracks, startingAt: index)
                            recordPlaybackSource(
                                kind: "folder",
                                name: URL(fileURLWithPath: path).lastPathComponent,
                                identifier: path
                            )
                        }
                    },
                    addToQueue: { track in player.append([track]) },
                    addToPlaylist: { track, playlistID in
                        let clicked = track.normalizedPath
                        let chosen = (selectedFolderTrackPaths.contains(clicked)
                            && selectedFolderTrackPaths.count > 1)
                            ? folderTracks.filter { selectedFolderTrackPaths.contains($0.normalizedPath) }
                            : [track]
                        addFolderTracksToPlaylist(chosen, playlistID: playlistID)
                    },
                    destinations: playlists.map {
                        PlaylistNativeTable.CopyDestination(id: $0.id, name: $0.name)
                    }
                )

            }
        }
    }


    // MARK: - FADER_SCROLL_RELIABILITY_V2

    private func scrollToPlayingTrack(_ proxy: ScrollViewProxy) {
        guard player.isFolderPlayback,
              let path = player.currentTrack?
                  .url.standardizedFileURL.path,
              visibleFolderTracks.contains(where: {
                  $0.normalizedPath == path
              })
        else { return }

        for delay in [0.15, 0.45] {
            DispatchQueue.main.asyncAfter(
                deadline: .now() + delay
            ) {
                guard player.isFolderPlayback,
                      player.currentTrack?
                          .url.standardizedFileURL.path == path,
                      visibleFolderTracks.contains(where: {
                          $0.normalizedPath == path
                      })
                else { return }

                if delay == 0.15 {
                    withAnimation(.easeInOut(duration: 0.20)) {
                        proxy.scrollTo(path, anchor: .center)
                    }
                } else {
                    // Correct the final position after List layout.
                    proxy.scrollTo(path, anchor: .center)
                }
            }
        }
    }


    // MARK: - FADER_REPAIR_MISSING_COVERS_V1

    private func repairMissingCovers(in path: String) {
        guard selectedFolder == path,
              !repairingArtworkFolders.contains(path),
              !scanningFolders.contains(path) else {
            return
        }

        let sourceTracks = folderTracks

        let missing = sourceTracks.filter {
            $0.artworkData == nil ||
            $0.artworkData?.isEmpty == true
        }

        guard !missing.isEmpty else {
            player.status = "All cached songs have artwork data."
            return
        }

        repairingArtworkFolders.insert(path)

        player.status =
            "Checking artwork for \(missing.count) song(s)..."

        DispatchQueue.global(qos: .utility).async {
            var recovered: [String: Data] = [:]

            for track in missing {
                // Runs off the main thread.
                // Library.makeTrack uses the corrected ID3 parser
                // and creates 256 px artwork thumbnails.
                let refreshed = Library.makeTrack(url: track.url)

                if let artwork = refreshed.artworkData,
                   !artwork.isEmpty {
                    recovered[
                        track.normalizedPath
                    ] = artwork
                }
            }

            DispatchQueue.main.async {
                repairingArtworkFolders.remove(path)

                guard folders.contains(where: {
                    $0.path == path
                }) else {
                    return
                }

                var updated = folderCache[path] ?? sourceTracks
                var count = 0

                for index in updated.indices {
                    let key = updated[index]
                        .url.standardizedFileURL.path

                    if let data = recovered[key],
                       updated[index].artworkData == nil ||
                       updated[index].artworkData?.isEmpty == true {
                        updated[index].artworkData = data
                        count += 1
                    }
                }

                folderCache[path] = updated

                if selectedFolder == path {
                    folderTracks = updated
                }

                if count > 0 {
                    let folderURL = URL(fileURLWithPath: path)

                    FolderCacheStore.save(
                        updated,
                        for: folderURL
                    )
                }

                player.status =
                    "Artwork repair: \(count) restored, " +
                    "\(missing.count - count) still unavailable."
            }
        }
    }

    // FADER_P08_EXPLICIT_DROP_V1
    private func addDroppedFoldersToLibrary(_ urls: [URL]) {
        let droppedURLs = urls
        DispatchQueue.global(qos: .userInitiated).async {
            var directories: [URL] = []
            for url in droppedURLs where url.isFileURL {
                var isDirectory: ObjCBool = false
                let normalized = url.standardizedFileURL
                if FileManager.default.fileExists(
                    atPath: normalized.path, isDirectory: &isDirectory
                ) && isDirectory.boolValue {
                    directories.append(normalized)
                }
            }
            DispatchQueue.main.async {
                var added = 0
                for url in directories {
                    if !folders.contains(where: { $0.path == url.path }) {
                        folders.append(url)
                        added += 1
                    }
                }
                if added > 0 { saveFolders(); loadRemainingFoldersInBackground() }
                player.status = directories.isEmpty
                    ? "Drop folders here (audio files go to Current Queue)."
                    : "Added \(added) music folder(s)."
            }
        }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = true
        panel.message = "Choose your music folders"

        guard panel.runModal() == .OK else { return }

        for url in panel.urls {
            let normalized = url.standardizedFileURL

            if !folders.contains(where: { $0.path == normalized.path }) {
                folders.append(normalized)
            }
        }

        saveFolders()

        if let last = panel.urls.last {
            selectFolder(last.standardizedFileURL)
        }
    }

    private func selectFolder(_ url: URL) {
        globalSearchSelected = false
        let path = url.path
        selectedFolder = path
        // FADER_P29134A_SELECTED_FOLDER_PRIORITY
        FolderCacheStore.preferSelectedFolder(url)
        FaderImportScheduler.shared.prefer(url)
        loadInitialFolder(url)
        loadRemainingFoldersInBackground()
    }

    private func loadRemainingFoldersInBackground() {
        for folder in folders where folderCache[folder.path] == nil && !scanningFolders.contains(folder.path) {
            loadInitialFolder(folder)
        }
    }

    private func loadInitialFolder(_ url: URL) {
        let path = url.path
        guard folders.contains(where: { $0.path == path }) else { return }

        // FADER_P29132_FOLDER_LOAD_DIAGNOSTICS
        let selectedAt = ProcessInfo.processInfo.systemUptime
        // Reuse tracks already loaded for this folder.
        if let cached = folderCache[path] {
            if selectedFolder == path {
                folderTracks = cached
                isLoading = false
            }
            print(String(format: "FADER PERF FOLDER UI: source=memory tracks=%d assign=%.0fms name=%@", cached.count, (ProcessInfo.processInfo.systemUptime-selectedAt)*1000, url.lastPathComponent))
            return
        }

        if selectedFolder == path {
            folderTracks = []
            initialImportProgress = nil
            initialImportError = nil
            isLoading = true
        }

        // Avoid starting a second scan while one is running.
        if scanningFolders.contains(path) {
            return
        }

        scanningFolders.insert(path)

        DispatchQueue.global(qos: .userInitiated).async {
            // FADER_P29132_FOLDER_LOAD_DIAGNOSTICS
            let workStartedAt = ProcessInfo.processInfo.systemUptime
            // First, try the persistent cache.
            // Prefer validated autonomous cache; preserve legacy fallback.
            let saved = FolderCacheStore.loadDedupAutonomous(for: url)
                ?? FolderCacheStore.load(for: url)

            let tracks: [Track]
            do {
                if let saved = saved {
                    tracks = saved
                    FaderStorage.log("CACHE HIT tracks=\(saved.count) folder=\(url.lastPathComponent)")
                } else {
                    tracks = try FaderInitialFolderImport.scan(folder: url) { progress in
                        DispatchQueue.main.async {
                            guard selectedFolder == path else { return }
                            if progress.completed >= (initialImportProgress?.completed ?? 0) {
                                initialImportProgress = progress
                            }
                        }
                    }
                }
            } catch {
                FaderStorage.log("IMPORT FAILED (no cache saved): " + error.localizedDescription)
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                        initialImportError = "Import stopped: " + error.localizedDescription
                    }
                }
                return
            }
            let readyAt = ProcessInfo.processInfo.systemUptime

            DispatchQueue.main.async {
                print(String(format: "FADER PERF FOLDER UI: source=%@ work=%.0fms mainWait=%.0fms total=%.0fms tracks=%d name=%@", saved == nil ? "scan" : "disk-cache", (readyAt-workStartedAt)*1000, (ProcessInfo.processInfo.systemUptime-readyAt)*1000, (ProcessInfo.processInfo.systemUptime-selectedAt)*1000, tracks.count, url.lastPathComponent))
                scanningFolders.remove(path)

                guard folders.contains(where: { $0.path == path }) else {
                    return
                }

                folderCache[path] = tracks

                if saved == nil {
                    // FADER_SQLITE_NEW_FOLDER_SYNC_P29123
                    // Synchronize only after the initial persistent cache write succeeds.
                    FolderCacheStore.save(tracks, for: url) { success in
                        guard success else {
                            print("FADER SEARCH SYNC SKIPPED: initial folder cache save failed")
                            return
                        }
                        // The folder may have been removed while the cache was being saved.
                        guard folders.contains(where: { $0.path == path }) else {
                            return
                        }
                        loadGlobalSearchIndex()
                        print("FADER SEARCH SYNC REQUESTED: initial folder scan")
                    }
                } else {
                    print("FADER CACHE: restored \(tracks.count) tracks")
                }

                if selectedFolder == path {
                    folderTracks = tracks
                    isLoading = false
                }
            }
        }
    }

    // MARK: - Refresh Folder

    // FADER_P2944G_SMART_REFRESH_ACTIVE
    private func refreshFolder(_ url: URL) {
        let path = url.path

        // Avoid two simultaneous refresh operations.
        guard !scanningFolders.contains(path) else { return }

        // Preserve the existing displayed tracks while refreshing.
        scanningFolders.insert(path)

        if selectedFolder == path {
            isLoading = true
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let result: FolderCacheStore.SmartRefreshResult

            do {
                result = try FolderCacheStore.smartRefresh(for: url)
            } catch {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)

                    guard folders.contains(where: { $0.path == path }) else {
                        return
                    }

                    if selectedFolder == path {
                        isLoading = false
                    }

                    // No cache write, no automatic full rescan.
                    player.status = "Smart Refresh stopped: \(error.localizedDescription)"
                    print("FADER SMART REFRESH STOPPED: folder=\(url.lastPathComponent) reason=\(error.localizedDescription)")
                }
                return
            }

            let refreshedTracks = result.tracks

            DispatchQueue.main.async {
                scanningFolders.remove(path)

                // Ignore results if the folder was removed meanwhile.
                guard folders.contains(where: { $0.path == path }) else {
                    return
                }

                folderCache[path] = refreshedTracks
                // FADER_P295A_SMART_REFRESH_SEARCH_SYNC
                FolderCacheStore.save(refreshedTracks, for: url) { success in
                    guard success else {
                        print("FADER SEARCH SYNC SKIPPED: cache save failed")
                        return
                    }
                    guard folders.contains(where: { $0.path == path }) else {
                        return
                    }
                    loadGlobalSearchIndex()
                    print("FADER SEARCH SYNC REQUESTED: Smart Refresh")
                }

                if selectedFolder == path {
                    folderTracks = refreshedTracks
                    isLoading = false
                }

                player.status = String(
                    format: "Smart Refresh: %d tracks (%d unchanged, %d updated, %d new) in %.2fs.",
                    refreshedTracks.count,
                    result.unchanged,
                    result.updated,
                    result.added,
                    result.elapsed
                )

                print(String(
                    format: "FADER SMART REFRESH: folder=%@ total=%d unchanged=%d updated=%d new=%d seconds=%.2f",
                    url.lastPathComponent,
                    refreshedTracks.count,
                    result.unchanged,
                    result.updated,
                    result.added,
                    result.elapsed
                ))
            }
        }
    }

    // MARK: - FADER_P2944L_FULL_RESCAN

    private func fullRescanFolder(_ url: URL) {
        let path = url.path

        guard !scanningFolders.contains(path) else { return }
        scanningFolders.insert(path)

        let previousCount = folderCache[path]?.count ?? 0

        if selectedFolder == path {
            isLoading = true
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let fm = FileManager.default
            var isDirectory: ObjCBool = false

            guard fm.fileExists(atPath: path, isDirectory: &isDirectory),
                  isDirectory.boolValue else {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                    }
                    player.status = "Full Rescan stopped: folder unavailable."
                    print("FADER FULL RESCAN STOPPED: folder unavailable")
                }
                return
            }

            // FADER_P2944Q_VERIFIED_RESCAN
            // Validate enumeration before and after the metadata scan.
            // Never replace cached music after an inventory error.

            let started = ProcessInfo.processInfo.systemUptime

            let before: SmartRefreshInventory.Result
            do {
                before = try SmartRefreshInventory.collect(folder: url)
            } catch {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                    }
                    player.status = "Full Rescan stopped: inventory error. Cache preserved."
                    print("FADER FULL RESCAN STOPPED: preflight error=\\(error.localizedDescription)")
                }
                return
            }

            // Preserve the existing empty-folder protection.
            if before.files.isEmpty {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                    }
                    player.status = "Full Rescan stopped: empty inventory. Cache preserved."
                    print("FADER FULL RESCAN STOPPED: empty inventory cache=preserved")
                }
                return
            }

            let refreshedTracks = Library.scan(folder: url)

            let after: SmartRefreshInventory.Result
            do {
                after = try SmartRefreshInventory.collect(folder: url)
            } catch {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                    }
                    player.status = "Full Rescan stopped: verification error. Cache preserved."
                    print("FADER FULL RESCAN STOPPED: postflight error=\\(error.localizedDescription)")
                }
                return
            }

            let beforePaths = Set(before.files.keys)
            let afterPaths = Set(after.files.keys)
            let scannedPaths = Set(
                refreshedTracks.map { $0.normalizedPath }
            )

            guard beforePaths == afterPaths,
                  afterPaths == scannedPaths,
                  refreshedTracks.count == afterPaths.count else {
                DispatchQueue.main.async {
                    scanningFolders.remove(path)
                    if selectedFolder == path {
                        isLoading = false
                    }
                    player.status = "Full Rescan stopped: inconsistent inventory. Cache preserved."
                    print("FADER FULL RESCAN STOPPED: inventory mismatch before=\\(beforePaths.count) after=\\(afterPaths.count) scanned=\\(scannedPaths.count) cache=preserved")
                }
                return
            }

            let elapsed = ProcessInfo.processInfo.systemUptime - started

            // FADER_P2944S_LARGE_REDUCTION
            // Keep empty results blocked. All nonempty reductions
            // require explicit confirmation below.
            let suspicious = refreshedTracks.isEmpty

            DispatchQueue.main.async {
                scanningFolders.remove(path)

                guard folders.contains(where: { $0.path == path }) else {
                    return
                }

                if selectedFolder == path {
                    isLoading = false
                }

                if suspicious {
                    player.status = "Full Rescan stopped: suspicious scan result. Cache preserved."
                    print("FADER FULL RESCAN STOPPED: folder=\(url.lastPathComponent) previous=\(previousCount) found=\(refreshedTracks.count) cache=preserved")
                    return
                }

                // FADER_P2944R_CONFIRM_REDUCTION
                // Never silently remove cached tracks.
                if previousCount > 0 &&
                   refreshedTracks.count < previousCount {

                    let removedCount = previousCount - refreshedTracks.count

                    let alert = NSAlert()
                    alert.alertStyle = .warning
                    alert.messageText = "Full Rescan: fewer songs found"
                    alert.informativeText =
                        "Folder: \(url.lastPathComponent)\n" +
                        "Previously: \(previousCount) songs\n" +
                        "Now: \(refreshedTracks.count) songs\n" +
                        "Missing: \(removedCount) songs\n\n" +
                        (previousCount >= 20 &&
                         refreshedTracks.count < previousCount * 80 / 100
                         ? "WARNING: More than 20% of the songs are missing. " +
                           "Verify that the folder and network connection are complete.\n\n"
                         : "") +
                        "Apply this change to the Fader library? " +
                        "Original audio files will not be deleted."

                    alert.addButton(withTitle: "Cancel")
                    alert.addButton(withTitle: "Apply Changes")

                    guard alert.runModal() == .alertSecondButtonReturn else {
                        player.status = "Full Rescan cancelled. Cache preserved."
                        print("FADER FULL RESCAN CANCELLED: folder=\(url.lastPathComponent) missing=\(removedCount) cache=preserved")
                        return
                    }
                }

                folderCache[path] = refreshedTracks

                // FADER_P295B_FULL_RESCAN_SEARCH_SYNC
                FolderCacheStore.save(refreshedTracks, for: url) { success in
                    guard success else {
                        print("FADER SEARCH SYNC SKIPPED: Full Rescan cache save failed")
                        return
                    }
                    guard folders.contains(where: { $0.path == path }) else {
                        return
                    }
                    loadGlobalSearchIndex()
                    print("FADER SEARCH SYNC REQUESTED: Full Rescan")
                }

                if selectedFolder == path {
                    folderTracks = refreshedTracks
                }

                player.status = "Full Rescan: \(refreshedTracks.count) tracks."

                print(String(
                    format: "FADER FULL RESCAN: folder=%@ previous=%d total=%d seconds=%.2f",
                    url.lastPathComponent,
                    previousCount,
                    refreshedTracks.count,
                    elapsed
                ))
            }
        }
    }

    private func removeFolder(_ url: URL) {
        folders.removeAll { $0.path == url.path }
        folderCache.removeValue(forKey: url.path)
        FolderCacheStore.remove(for: url)

        if selectedFolder == url.path {
            selectedFolder = nil
            folderTracks = []
            isLoading = false
        }

        saveFolders()

        // FADER_P295D_REMOVE_FOLDER_SEARCH_SYNC
        // Reindex using the remaining Music Folders only.
        // Original audio files are never touched.
        loadGlobalSearchIndex()
        print("FADER SEARCH SYNC REQUESTED: Remove from Library")
    }

    private func saveFolders() {
        FaderStorage.defaults.set(
            folders.map(\.path),
            forKey: storageKey
        )
    }

    private func restoreFolders() {
        let paths = FaderStorage.defaults.stringArray(
            forKey: storageKey
        ) ?? []

        folders = paths.map {
            URL(fileURLWithPath: $0)
        }
    }
}


// FADER_PREMIUM_STAGE10BC — source-aware read-only panel with artwork.
// No mutations: stage 10D will reuse native selection/DnD instead of cloning it.
private struct FaderPremiumQueuePreview: View {
    let onDropToQueue: ([NSItemProvider]) -> Bool
    let onDropToPlaylist: ([NSItemProvider], UUID) -> Bool
    let playlistTracks: (UUID) -> [Track]
    let playPlaylistAt: (UUID, Int) -> Void
    @EnvironmentObject var player: Player
    @AppStorage("Fader.playingSourceName", store: FaderStorage.defaults) private var playingSourceName = ""
    @AppStorage("Fader.playingSourceKind", store: FaderStorage.defaults) private var playingSourceKind = ""
    @State private var selectedMode = 0
    @State private var localQuery = ""
    @State private var showInteractionHelp = false
    @State private var selectedPaths: Set<String> = []
    @State private var selectionAnchor: Int? = nil
    private func rowArtwork(for track: Track) -> NSImage? {
        FaderArtworkImageCache.image(for: track)
    }

    // Editable only when Playing From is an actual saved Fader playlist.
    // Folder playback is read-only; Current Queue uses its existing drop handler.
    private var playingPlaylistID: UUID? {
        guard selectedMode == 0, !player.isFinderPlayback, player.isFolderPlayback,
              FaderStorage.defaults.string(forKey: "Fader.playingSourceKind") == "playlist",
              let rawID = FaderStorage.defaults.string(forKey: "Fader.playingSourceID")
        else { return nil }
        return UUID(uuidString: rawID)
    }

    private var sourceTracks: [Track] {
        if player.isFinderPlayback { return player.finderPlaybackTracks }
        // Saved playlists are live data, not the playback snapshot.
        if let id = playingPlaylistID { return playlistTracks(id) }
        return player.isFolderPlayback ? player.folderPlaybackTracks : player.queue
    }

    private var sourceName: String {
        if player.isFinderPlayback { return "Opened from Finder" }
        if player.isFolderPlayback {
            return playingSourceName.isEmpty ? "Unknown source" : playingSourceName
        }
        return "Current Queue"
    }

    private var playingFromLabel: String {
        if player.isFinderPlayback { return "Opened from Finder" }
        if !player.isFolderPlayback { return "Playing from Current Queue" }
        switch playingSourceKind {
        case "playlist": return "Playlist · \(sourceName)"
        case "folder": return "Music Folder · \(sourceName)"
        default: return "Playing From · \(sourceName)"
        }
    }

    private var visibleTracks: [Track] {
        selectedMode == 0 ? sourceTracks : player.queue
    }

    private var activePath: String? {
        guard selectedMode == 0 || (!player.isFolderPlayback && !player.isFinderPlayback) else { return nil }
        return player.currentTrack?.normalizedPath
    }

    private func select(_ index: Int, modifiers: EventModifiers) {
        guard visibleTracks.indices.contains(index) else { return }
        let path = visibleTracks[index].normalizedPath
        if modifiers.contains(.shift), let anchor = selectionAnchor {
            let lower = min(anchor, index)
            let upper = max(anchor, index)
            selectedPaths = Set(visibleTracks[lower...upper].map { $0.normalizedPath })
        } else if modifiers.contains(.command) {
            if !selectedPaths.insert(path).inserted { selectedPaths.remove(path) }
            selectionAnchor = index
        } else {
            selectedPaths = selectedPaths == Set([path]) ? [] : [path]
            selectionAnchor = index
        }
    }

    private func play(_ index: Int) {
        if selectedMode == 0 && player.isFinderPlayback {
            player.playIndex(index)
        } else if selectedMode == 1 || !player.isFolderPlayback {
            player.playQueueIndex(index)
        } else if let id = playingPlaylistID {
            playPlaylistAt(id, index)
        } else {
            player.playFolder(player.folderPlaybackTracks, startingAt: index)
        }
    }

    var body: some View {
        // Resolve/filter once per update. Row closures capture this immutable snapshot;
        // indexing a computed property here rebuilt the entire list for every row.
        let tracks = visibleTracks
        let entries = FaderTrackListing.entries(in: tracks, query: localQuery)
        return VStack(alignment: .leading, spacing: 0) {
            // STAGE10D49: presentation only; existing mode and query bindings retained.
            VStack(spacing: 12) {
                HStack(spacing: 7) {
                    Text("Playback & Queue").font(.headline)
                    Button { showInteractionHelp.toggle() } label: {
                        Image(systemName: "info.circle")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .help("Selection, playback and drag instructions")
                    .accessibilityLabel("Interaction instructions")
                    .accessibilityValue(showInteractionHelp ? "Expanded" : "Collapsed")
                }
                .frame(maxWidth: .infinity)

                Picker("Playback list", selection: $selectedMode) {
                    Text("Playing From").tag(0)
                    Text("Current Queue").tag(1)
                }
                .pickerStyle(.segmented)
                .controlSize(.small)
                .labelsHidden()
                .frame(maxWidth: 240)
                .frame(maxWidth: .infinity)

                HStack(spacing: 8) {
                    HStack(spacing: 7) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search in this list", text: $localQuery)
                            .textFieldStyle(.plain)
                            .font(.caption)
                            .accessibilityLabel("Search in this list")
                        if !localQuery.isEmpty {
                            Button { localQuery = "" } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.borderless)
                            .help("Clear Playback & Queue search")
                            .accessibilityLabel("Clear Playback & Queue search")
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(Color(nsColor: .textBackgroundColor),
                                in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                    .frame(minWidth: 60, maxWidth: .infinity)

                    Text("\(entries.count) / \(tracks.count)")
                        .font(.caption2).monospacedDigit()
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: true, vertical: false)
                    Divider().frame(height: 16)
                    Text(selectedMode == 0 ? "From: \(sourceName)" : "Current Queue")
                        .font(.caption2).foregroundStyle(.secondary)
                        .lineLimit(1)
                        .frame(maxWidth: 120, alignment: .leading)
                        .help(selectedMode == 0 ? playingFromLabel : "Current Queue")
                }

                if showInteractionHelp {
                    HStack {
                        Spacer(minLength: 0)
                        VStack(alignment: .leading, spacing: 7) {
                            Label("Click to select", systemImage: "cursorarrow")
                            Label("Cmd / Shift for multiple songs", systemImage: "command")
                            Label("Double-click to play", systemImage: "play.circle")
                            Label("Drag to a playlist or queue", systemImage: "hand.draw")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                }
            }
            .padding(12)
            Divider()
            if tracks.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: selectedMode == 0 ? "play.circle" : "music.note.list")
                        .font(.title2)
                        .foregroundStyle(.secondary)
                    Text(selectedMode == 0 ? "Nothing playing" : "Current Queue is empty")
                        .font(.callout)
                    Text(selectedMode == 0
                         ? "Play a song in a folder, playlist or queue"
                         : "Playing a folder does not add its tracks here")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(16)
            } else {
                ScrollViewReader { scrollProxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(entries) { entry in
                                VStack(spacing: 0) {
                                    premiumTrackRow(entry.track, index: entry.index)
                                    if entry.index != entries.last?.index {
                                        Rectangle()
                                            .fill(Color(nsColor: .separatorColor).opacity(0.65))
                                            .frame(height: 1)
                                            .padding(.horizontal, 8)
                                            .allowsHitTesting(false)
                                    }
                                }
                                    .id(entry.index)
                                    // STAGE10D15: the row also receives clicks on its empty right side.
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                    .onTapGesture(count: 2) {
                                        play(entry.index)
                                        // STAGE10D39: a simultaneous single-tap may select
                                        // the row; release it after the double-click.
                                        DispatchQueue.main.async {
                                            selectedPaths.removeAll()
                                            selectionAnchor = nil
                                        }
                                    }
                                    // STAGE10D14: observe single tap alongside the double-tap
                                    // recognizer, instead of forcing single tap to wait for double.
                                    .simultaneousGesture(TapGesture(count: 1).onEnded {
                                        select(entry.index, modifiers: NSEvent.modifierFlags.contains(.shift) ? [.shift] : (NSEvent.modifierFlags.contains(.command) ? [.command] : []))
                                    })
                                    .onDrag {
                                        let chosen = selectedPaths.contains(entry.track.normalizedPath)
                                            ? tracks.filter { selectedPaths.contains($0.normalizedPath) }
                                            : [entry.track]
                                        let paths = Array(Set(chosen.map { $0.normalizedPath })).sorted()
                                        let data = (try? JSONEncoder().encode(paths)) ?? Data("[]".utf8)
                                        let payload = "fader-tracks:" + data.base64EncodedString()
                                        // D20: always publish a declared UTF-8 representation.
                                        // NSItemProvider(contentsOf:) did not advertise a supported
                                        // type to these SwiftUI drop destinations (D19 regression).
                                        let provider = NSItemProvider(object: payload as NSString)
                                        provider.registerDataRepresentation(
                                            forTypeIdentifier: "public.utf8-plain-text", visibility: .all
                                        ) { completion in
                                            completion(Data(payload.utf8), nil)
                                            return nil
                                        }
                                        // A single track also exposes the native Finder-style URL.
                                        // The UTF-8 payload above stays available to the queue and
                                        // supports the complete selection when multiple rows are dragged.
                                        if paths.count == 1 {
                                            let fileURL = URL(fileURLWithPath: paths[0]).standardizedFileURL
                                            provider.registerDataRepresentation(
                                                forTypeIdentifier: "public.file-url", visibility: .all
                                            ) { completion in
                                                completion(Data(fileURL.absoluteString.utf8), nil)
                                                return nil
                                            }
                                        }
                                        print("FADER D20 DRAG: paths=\(paths.count) text=true nativeURL=\(paths.count == 1)")
                                        return provider
                                    }
                            }
                        }
                        .padding(7)
                    }
                    // Go to Playing from the bottom player should also reveal
                    // the active track here, even when the center pane navigates.
                    .onReceive(NotificationCenter.default.publisher(
                        for: Notification.Name("Fader.goToPlaying")
                    )) { _ in
                        let index = visibleTracks.firstIndex(where: {
                            $0.normalizedPath == player.currentTrack?.normalizedPath
                        }) ?? player.currentIndex
                        if visibleTracks.indices.contains(index) {
                            DispatchQueue.main.async {
                                withAnimation(.easeInOut(duration: 0.18)) {
                                    scrollProxy.scrollTo(index, anchor: .center)
                                }
                            }
                        }
                    }
                    .onChange(of: player.currentIndex) { _, newIndex in
                        let target = visibleTracks.firstIndex(where: {
                            $0.normalizedPath == player.currentTrack?.normalizedPath
                        }) ?? newIndex
                        if visibleTracks.indices.contains(target) {
                            withAnimation(.easeInOut(duration: 0.18)) {
                                scrollProxy.scrollTo(target, anchor: .center)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .controlBackgroundColor).opacity(0.38))
        // Route drag payloads to the EXISTING destination handlers.
        // Playlist playback: editing the saved playlist is allowed.
        // Folder playback: refuse drop; originals must never be modified.
        // Current Queue mode: use the existing queue add/dedup handler.
        .onDrop(of: ["public.utf8-plain-text", "public.plain-text", "public.file-url"],
                isTargeted: nil) { providers in
            if selectedMode == 1 { return onDropToQueue(providers) }
            if player.isFinderPlayback { return false }
            if let id = playingPlaylistID { return onDropToPlaylist(providers, id) }
            return false
        }
    }

    private func premiumTrackRow(_ track: Track, index: Int) -> some View {
        let isCurrent = activePath == track.normalizedPath
        let isSelected = selectedPaths.contains(track.normalizedPath)
        return HStack(alignment: .center, spacing: 8) {
            Image(systemName: isCurrent ? "speaker.wave.2.fill" : "music.note")
                .font(.system(size: 12))
                .foregroundStyle(isCurrent ? Color.accentColor : Color.secondary)
                .frame(width: 16, alignment: .center)
            ZStack {
                RoundedRectangle(cornerRadius: 5)
                    .fill(Color.accentColor.opacity(0.10))
                    .frame(width: 35, height: 35)
                if let cover = rowArtwork(for: track) {
                    Image(nsImage: cover)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 35, height: 35)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                } else {
                    Image(systemName: "music.note")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.callout)
                    .lineLimit(1)
                Text(track.artist)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            // Use the existing playback action; no new audio or queue logic.
            // A fixed-size button prevents the row width from shifting.
            Button {
                play(index)
            } label: {
                Image(systemName: isCurrent && player.isPlaying ? "pause.circle" : "play.circle")
                    .font(.system(size: 17, weight: .regular))
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 26, height: 26)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(isCurrent && player.isPlaying ? "Now Playing" : "Play this song")
            if track.duration > 0 {
                Text(Self.durationText(track.duration))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.tertiary)
                    .frame(width: 39, alignment: .trailing)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(isSelected ? Color.accentColor.opacity(0.19) : (isCurrent ? Color.accentColor.opacity(0.10) : Color.clear))
        .clipShape(RoundedRectangle(cornerRadius: 7))
    }

    private static func durationText(_ seconds: Double) -> String {
        let value = Int(max(0, seconds))
        return String(format: "%d:%02d", value / 60, value % 60)
    }
}

// Stable native hosting views; only their available geometry changes.
private struct FaderPersistentQueueSplit<Center: View, Queue: View>: View {
    let center: Center
    let queue: Queue
    var body: some View {
        FaderAccentNativeSplit(isVertical: true, storageKey: "Fader.split.queue.responsive",
            minFirst: 336, minSecond: 296, first: center, second: queue,
            initialFraction: 0.55, initialFirstMaximum: nil)
    }
}
