import SwiftUI
import AppKit
import UniformTypeIdentifiers

@main
struct FaderApp: App {
    @NSApplicationDelegateAdaptor(FaderFileOpenDelegate.self) private var fileOpenDelegate
    @StateObject private var player = Player()
    // FADER_UI_V1_STAGE6B: same preference used by ContentView.
    @AppStorage("Fader.showSidebar") private var showSidebar = true

    var body: some Scene {
        WindowGroup("Fader — Music Player") {
            ContentView()
                .environmentObject(player)
                .frame(minWidth: 900, minHeight: 480)
                .background(FaderWindowSizing().frame(width: 1, height: 1).allowsHitTesting(false))
                .onAppear {
                    FaderFileOpenRouter.shared.install { urls in player.openFinderFiles(urls) }
                    // STAGE8_NATIVE_ACCENT: selection and controls follow macOS.
                    UserDefaults.standard.set(true, forKey: "Fader.appearance.useSystemAccent")
                    // Make sure the window comes to the front even when
                    // launched from Finder or a terminal.
                    NSApp.setActivationPolicy(.regular)
                    NSApp.activate(ignoringOtherApps: true)
                    // Hide the top-level Edit menu, leaving its native commands intact.
                    // Defer until SwiftUI has installed the application menu bar.
                    DispatchQueue.main.async {
                        if let edit = NSApp.mainMenu?.items.first(where: { $0.title == "Edit" }) {
                            edit.isHidden = true
                        }
                    }
                }
        }
        .defaultSize(width: 1200, height: 760)
        .windowResizability(.contentMinSize)
        Settings {
            FaderAppearanceSettings()
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Open Audio Files…") { openAudioFiles() }
                    .keyboardShortcut("o", modifiers: .command)
                    .disabled(player.isRestoringSession)
                Divider()
                Button("Open Folder…") { open(directoriesOnly: true) }
                    .keyboardShortcut("o", modifiers: [.command, .shift])
                    .disabled(player.isRestoringSession)
                Divider()
                Button("Open Playlist…") { open(directoriesOnly: false) }
                    .keyboardShortcut("p", modifiers: [.command, .shift])
                    .disabled(player.isRestoringSession)
            }
            // FADER_UI_V1_STAGE6A — native Playback menu; default About Fader restored.
            CommandMenu("Playback") {
                Button("Play / Pause") { player.togglePlay() }
                    .keyboardShortcut("k", modifiers: .command)
                    .disabled(player.isRestoringSession && !player.isFinderPlayback)
                Button("Stop") { player.stop() }
                    .keyboardShortcut(".", modifiers: .command)
                    .disabled(player.isRestoringSession && !player.isFinderPlayback)
                Divider()
                Button("Next") { player.next(manual: true) }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                    .disabled(player.isRestoringSession && !player.isFinderPlayback)
                Button("Previous") { player.previous() }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
                    .disabled(player.isRestoringSession && !player.isFinderPlayback)
            }
            // FADER_UI_V1_STAGE6B — View menu uses existing application state/actions.
            CommandMenu("View") {
                Button(showSidebar ? "Hide Sidebar" : "Show Sidebar") {
                    showSidebar.toggle()
                }
                Divider()
                Button("Go to Playing") {
                    NotificationCenter.default.post(
                        name: Notification.Name("Fader.goToPlaying"),
                        object: nil
                    )
                }
            }
        }
    }

    // MARK: - File picking
    // FADER_FILEMENU_IMPORT_P291313: route library operations to library view.
    private func open(directoriesOnly: Bool) {
        guard !player.isRestoringSession else { return }
        let panel = NSOpenPanel()
        panel.canChooseDirectories = directoriesOnly
        panel.canChooseFiles = !directoriesOnly
        panel.allowsMultipleSelection = !directoriesOnly
        panel.message = directoriesOnly
            ? "Choose a music folder"
            : "Import playlist (.m3u, .m3u8, .pls, .xspf, .json)"
        if !directoriesOnly {
            panel.allowedContentTypes = ["m3u", "m3u8", "pls", "xspf", "json"]
                .compactMap { UTType(filenameExtension: $0) }
        }
        guard panel.runModal() == .OK else { return }
        if directoriesOnly {
            guard let folder = panel.urls.first?.standardizedFileURL else { return }
            NotificationCenter.default.post(
                name: Notification.Name("Fader.fileMenuAddFolder"),
                object: nil, userInfo: ["url": folder]
            )
        } else {
            let urls = panel.urls.filter { Library.isPlaylist(url: $0) }
            guard !urls.isEmpty else { return }
            player.status = "Importing playlist..."
            DispatchQueue.global(qos: .userInitiated).async {
                let imports = urls.map { url in
                    (url: url, tracks: Library.loadPlaylist(url: url))
                }
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: Notification.Name("Fader.fileMenuImportPlaylists"),
                        object: nil, userInfo: ["imports": imports.map { item in
                            ["url": item.url, "tracks": item.tracks] as [String: Any]
                        }]
                    )
                }
            }
        }
    }

    // MARK: - Audio file import

    private func openAudioFiles() {
        guard !player.isRestoringSession else { return }
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        panel.message = "Choose one or more audio files"

        panel.allowedContentTypes = [
            "mp3", "flac", "wav", "m4a", "aac", "aif", "aiff"
        ].compactMap { UTType(filenameExtension: $0) }

        guard panel.runModal() == .OK else { return }

        let tracks = panel.urls.map { Library.makeTrack(url: $0) }
        if !tracks.isEmpty {
            player.append(tracks)
        }
    }

}

// FADER_UI_V1_APPEARANCE_STAGE1 — Settings / Appearance.
// No audio, library, queue, or playlist behavior is modified.
enum FaderAccentColor {
    static func fromHex(_ hex: String) -> Color {
        let trimmed = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        let value = trimmed.hasPrefix("#") ? String(trimmed.dropFirst()) : trimmed
        guard value.count == 6, let number = UInt32(value, radix: 16) else {
            return Color(red: 0.294, green: 0.553, blue: 0.973)
        }
        return Color(.sRGB,
                     red: Double((number >> 16) & 0xff) / 255.0,
                     green: Double((number >> 8) & 0xff) / 255.0,
                     blue: Double(number & 0xff) / 255.0,
                     opacity: 1)
    }

    static func hex(from color: Color) -> String? {
        guard let ns = NSColor(color).usingColorSpace(.deviceRGB) else { return nil }
        let red = Int((max(0, min(1, ns.redComponent)) * 255).rounded())
        let green = Int((max(0, min(1, ns.greenComponent)) * 255).rounded())
        let blue = Int((max(0, min(1, ns.blueComponent)) * 255).rounded())
        return String(format: "#%02X%02X%02X", red, green, blue)
    }
}

// FADER_UI_V1_STAGE8 — Appearance settings presentation only.
// Existing AppStorage keys and behavior are preserved.
private struct FaderAppearanceSettings: View {
    @AppStorage("Fader.appearance.mode") private var mode = "system"
    @AppStorage("Fader.appearance.useSystemAccent") private var useSystemAccent = true
    @AppStorage("Fader.appearance.accentHex") private var accentHex = "#4B8DF8"

    private var customAccent: Binding<Color> {
        Binding(
            get: { FaderAccentColor.fromHex(accentHex) },
            set: { if let hex = FaderAccentColor.hex(from: $0) { accentHex = hex } }
        )
    }

    private var currentAccent: Color {
        useSystemAccent ? Color.accentColor : FaderAccentColor.fromHex(accentHex)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            header
            Divider()
            appearanceSection
            Divider()
            accentSection
            Divider()
            HStack {
                Text("Changes are applied immediately")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Reset to System Defaults") {
                    mode = "system"
                    useSystemAccent = true
                    accentHex = "#4B8DF8"
                }
            }
        }
        .padding(26)
        .frame(width: 540)
        .background(Color(nsColor: .windowBackgroundColor))
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(currentAccent)
                .frame(width: 44, height: 44)
                .background(currentAccent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 3) {
                Text("Appearance")
                    .font(.title2.weight(.semibold))
                Text("Make Fader feel at home on your Mac")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("THEME")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                themeOption("system", title: "System", subtitle: "Follow macOS", symbol: "desktopcomputer")
                themeOption("light", title: "Light", subtitle: "Bright & clean", symbol: "sun.max")
                themeOption("dark", title: "Dark", subtitle: "Easy on the eyes", symbol: "moon.stars")
            }
        }
    }

    private func themeOption(_ value: String, title: String, subtitle: String, symbol: String) -> some View {
        let selected = mode == value
        return Button {
            mode = value
        } label: {
            VStack(spacing: 9) {
                Image(systemName: symbol)
                    .font(.system(size: 24, weight: .regular))
                    .frame(height: 31)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .foregroundStyle(selected ? currentAccent : Color.primary)
            .background(
                RoundedRectangle(cornerRadius: 13)
                    .fill(selected ? currentAccent.opacity(0.10) : Color(nsColor: .controlBackgroundColor))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 13)
                    .strokeBorder(selected ? currentAccent : Color(nsColor: .separatorColor), lineWidth: selected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 13))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Appearance: \(title)")
    }

    // Native accent only — configured in macOS System Settings > Appearance.
    private var accentSection: some View {
        VStack(alignment: .leading, spacing: 13) {
            Text("ACCENT COLOR")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Label("Uses the macOS accent color", systemImage: "paintpalette")
                .font(.subheadline)
            Text("Change the accent color in macOS System Settings → Appearance. Fader follows it automatically.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
    private func accentOption(_ hex: String, label: String) -> some View {
        let color = FaderAccentColor.fromHex(hex)
        let selected = !useSystemAccent && accentHex.uppercased() == hex.uppercased()
        return Button {
            accentHex = hex
        } label: {
            Circle()
                .fill(color)
                .frame(width: 28, height: 28)
                .padding(4)
                .overlay(Circle().strokeBorder(selected ? Color.primary : Color.clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .disabled(useSystemAccent)
        .help(label)
        .accessibilityLabel("Accent color: \(label)")
    }
}
