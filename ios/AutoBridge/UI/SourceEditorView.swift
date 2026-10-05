import SwiftUI

/// Add/edit form for one source.
///
/// For Xtream the URL field accepts a bare portal or a full `player_api.php`/`get.php` link;
/// credentials found in a pasted link fill the empty fields, which is how providers usually hand
/// accounts over. Mirrors the Android `LibraryActivity.addSource`.
struct SourceEditorView: View {
    /// What the sheet was opened for: a new source of `type`, or an edit of `existing`.
    struct Request: Identifiable {
        let type: IptvSourceType
        let existing: IptvSource?

        var id: String { (existing?.id ?? "new") + type.rawValue }
    }

    let request: Request
    let kind: IptvKind

    @EnvironmentObject private var sourceStore: IptvSourceStore
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var problem: Problem?

    private struct Problem: Identifiable {
        let title: String
        let message: String
        var id: String { title + message }
    }

    private var isXtream: Bool { request.type == .xtream }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(NSLocalizedString("Name", comment: "Source form"), text: $name)
                        .textInputAutocapitalization(.words)
                    TextField(urlPrompt, text: $url)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    if isXtream {
                        TextField(NSLocalizedString("Username", comment: "Source form"), text: $username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField(NSLocalizedString("Password", comment: "Source form"), text: $password)
                    }
                } footer: {
                    Text(footer)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Dialog")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("Save", comment: "Dialog")) { save() }
                }
            }
            .alert(
                problem?.title ?? "",
                isPresented: Binding(
                    get: { problem != nil },
                    set: { if !$0 { problem = nil } }
                )
            ) {
                Button(NSLocalizedString("OK", comment: "Dialog"), role: .cancel) {}
            } message: {
                Text(problem?.message ?? "")
            }
        }
        .onAppear(perform: fill)
    }

    private var navigationTitle: String {
        if request.existing != nil {
            return NSLocalizedString("Edit source", comment: "Source form")
        }
        return isXtream
            ? NSLocalizedString("Add Xtream account", comment: "Source form")
            : NSLocalizedString("Add M3U playlist", comment: "Source form")
    }

    private var urlPrompt: String {
        isXtream
            ? NSLocalizedString("Portal URL or full player_api.php link", comment: "Source form")
            : NSLocalizedString("M3U playlist URL", comment: "Source form")
    }

    private var footer: LocalizedStringKey {
        isXtream
            ? "A pasted link that already contains the username and password fills those fields in. Credentials are stored in the keychain, never in plain preferences."
            : "The playlist is fetched live from the address you enter; nothing is copied into the app."
    }

    private func fill() {
        guard let existing = request.existing else { return }
        name = existing.name
        url = existing.url
        username = existing.username
        password = existing.password
    }

    private func save() {
        let address = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if address.isEmpty {
            problem = Problem(
                title: NSLocalizedString("Missing URL", comment: "Source form"),
                message: NSLocalizedString("Enter the provider address first.", comment: "Source form")
            )
            return
        }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        if isXtream {
            guard let credentials = XtreamCredentials.parse(
                input: address,
                username: username,
                password: password
            ) else {
                problem = Problem(
                    title: NSLocalizedString("Incomplete account", comment: "Source form"),
                    message: NSLocalizedString(
                        "Enter a username and password, or paste a link that contains them.",
                        comment: "Source form"
                    )
                )
                return
            }
            sourceStore.save(
                IptvSource(
                    id: request.existing?.id ?? IptvSourceStore.newId(),
                    name: trimmedName.isEmpty ? hostOf(credentials.portal) : trimmedName,
                    kind: kind,
                    type: .xtream,
                    url: credentials.portal,
                    username: credentials.username,
                    password: credentials.password
                )
            )
        } else {
            let lower = address.lowercased()
            if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
                problem = Problem(
                    title: NSLocalizedString("Invalid URL", comment: "Source form"),
                    message: NSLocalizedString(
                        "The playlist address must start with http:// or https://.",
                        comment: "Source form"
                    )
                )
                return
            }
            sourceStore.save(
                IptvSource(
                    id: request.existing?.id ?? IptvSourceStore.newId(),
                    name: trimmedName.isEmpty ? hostOf(address) : trimmedName,
                    kind: kind,
                    type: .m3u,
                    url: address
                )
            )
        }
        dismiss()
    }
}
