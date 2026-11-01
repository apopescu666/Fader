import SwiftUI
import AppKit

// FADER_P299A_V2_SHARED_DROP
// Shared drag-and-drop positioning mechanism.
// Does not modify playlist, queue, or filesystem data.

struct FaderInsertionDropDelegate: DropDelegate {
    let rowHeight: CGFloat
    let updateIndicator: (Bool?) -> Void
    let acceptDrop: ([NSItemProvider], Bool) -> Bool

    private func insertionAfter(_ info: DropInfo) -> Bool {
        info.location.y >= rowHeight / 2
    }

    private func isInternalReorder(_ info: DropInfo) -> Bool {
        info.hasItemsConforming(
            to: ["public.utf8-plain-text"]
        ) && !info.hasItemsConforming(
            to: ["public.file-url"]
        )
    }

    func dropEntered(info: DropInfo) {
        if isInternalReorder(info) {
            updateIndicator(insertionAfter(info))
        }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        if isInternalReorder(info) {
            let after = insertionAfter(info)
            // FADER_P299A_DROP_DIAGNOSTIC
            updateIndicator(after)
            return DropProposal(operation: .move)
        }

        updateIndicator(nil)
        return DropProposal(operation: .copy)
    }

    func dropExited(info: DropInfo) {
        updateIndicator(nil)
    }

    func performDrop(info: DropInfo) -> Bool {
        let after = insertionAfter(info)
        updateIndicator(nil)

        let providers = info.itemProviders(
            for: ["public.file-url", "public.utf8-plain-text"]
        )
        let accepted = acceptDrop(providers, after)
        print("FADER DND: DROP y=\(info.location.y) after=\(after) providers=\(providers.count) accepted=\(accepted)")
        return accepted
    }
}


// FADER_P299A_V2_PLAYLIST_INTEGRATION
// Only adds an insertion indicator during an internal playlist drag.
// Existing song row layout and controls remain unchanged.
struct FaderPlaylistInsertionModifier: ViewModifier {
    let path: String

    @Binding var insertionPath: String?
    @Binding var insertionAfter: Bool

    let acceptFinder: ([NSItemProvider]) -> Bool
    let acceptReorder: ([NSItemProvider], Bool) -> Bool

    func body(content: Content) -> some View {
        content
            .onDrop(
                of: ["public.utf8-plain-text", "public.file-url"],
                delegate: FaderInsertionDropDelegate(
                    rowHeight: 48,
                    updateIndicator: { after in
                        // FADER_P299C2_DEDUP_HOVER
                        if let after {
                            if insertionPath != path ||
                               insertionAfter != after {
                                insertionPath = path
                                insertionAfter = after
                            }
                        } else if insertionPath == path {
                            insertionPath = nil
                        }
                    },
                    acceptDrop: { providers, after in
                        if providers.contains(where: {
                            $0.hasItemConformingToTypeIdentifier(
                                "public.file-url"
                            )
                        }) {
                            return acceptFinder(providers)
                        }
                        return acceptReorder(providers, after)
                    }
                )
            )
            .overlay(alignment: .top) {
                if insertionPath == path && !insertionAfter {
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(height: 2)
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottom) {
                if insertionPath == path && insertionAfter {
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(height: 2)
                        .allowsHitTesting(false)
                }
            }
    }
}
