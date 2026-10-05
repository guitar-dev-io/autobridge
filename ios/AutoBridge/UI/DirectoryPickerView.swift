import SwiftUI

/// One-tap add for the public lists in `IptvDirectory`.
///
/// The picker only fills in the address the user would otherwise type: the source is an ordinary
/// M3U entry afterwards, editable and removable like any other. A list already added is shown as
/// such rather than added twice. Mirrors the Android `LibraryActivity.addFromDirectory`.
struct DirectoryPickerView: View {
    let kind: IptvKind
    let accent: Color

    @EnvironmentObject private var sourceStore: IptvSourceStore
    @Environment(\.dismiss) private var dismiss

    private var offers: [IptvDirectory.Entry] { IptvDirectory.list(kind: kind) }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(offers) { offer in
                        let added = sourceStore.contains(url: offer.url)
                        Button {
                            guard !added else { return }
                            sourceStore.save(
                                IptvDirectory.toSource(offer, id: IptvSourceStore.newId())
                            )
                            dismiss()
                        } label: {
                            ContentRow(
                                title: offer.name,
                                subtitle: added
                                    ? NSLocalizedString("Already added", comment: "Directory picker")
                                    : offer.note,
                                accent: added ? AutoBridgeDesign.secondaryText : accent,
                                badge: added ? "✓" : "+",
                                trailing: added ? "" : "›"
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(added)
                    }
                    if offers.isEmpty {
                        EmptyState(
                            title: NSLocalizedString("No public lists", comment: "Directory picker"),
                            message: NSLocalizedString(
                                "There is no built-in list for this section.",
                                comment: "Directory picker"
                            ),
                            accent: accent
                        )
                    }
                    Text(
                        "AutoBridge stores addresses only and fetches each list live from the project that publishes it, under that project's own terms. Nothing is hosted, bundled or redistributed here."
                    )
                    .font(.caption2)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .padding(.top, 12)
                }
                .padding(16)
            }
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("Free public lists", comment: "Directory picker"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Dialog")) { dismiss() }
                }
            }
        }
    }
}
