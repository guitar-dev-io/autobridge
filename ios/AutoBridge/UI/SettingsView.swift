import SwiftUI

/// Settings: the sources, the YouTube add-ons, the caches, and what this app deliberately cannot do.
///
/// The Android settings screen also carries modes, the parked gate and the mirror diagnostics. None
/// of those exist here, and the "Not on iOS" section says so rather than leaving the absence to be
/// discovered.
struct SettingsView: View {
    @EnvironmentObject private var sourceStore: IptvSourceStore
    @EnvironmentObject private var historyStore: IptvHistoryStore
    @EnvironmentObject private var catalog: IptvCatalog
    @EnvironmentObject private var youtube: YouTubeSettings
    @EnvironmentObject private var vehicle: VehicleStore

    @State private var logoBytes = 0
    @State private var breakHours = VehicleReminders.breakHours

    var body: some View {
        List {
            Section(NSLocalizedString("Sources", comment: "Settings")) {
                NavigationLink {
                    SourcesView(kind: .tv, accent: AutoBridgeDesign.accentTV)
                } label: {
                    row(
                        NSLocalizedString("TV sources", comment: "Settings"),
                        plural(sourceStore.sources(kind: .tv).count, "source")
                    )
                }
                NavigationLink {
                    SourcesView(kind: .radio, accent: AutoBridgeDesign.accentRadio)
                } label: {
                    row(
                        NSLocalizedString("Radio sources", comment: "Settings"),
                        plural(sourceStore.sources(kind: .radio).count, "source")
                    )
                }
            }

            // The driver's own paperwork on the car, the Android settings' Utilities group.
            Section(NSLocalizedString("Utilities", comment: "Settings")) {
                NavigationLink { FuelLogView() } label: {
                    ValueRow(title: NSLocalizedString(vehicle.isEv ? "Charging log" : "Fuel log", comment: "Fuel"),
                        detail: NSLocalizedString("Fill-ups, km/L and fuel spending", comment: "Settings"))
                }
                NavigationLink { MaintenanceView() } label: {
                    ValueRow(title: NSLocalizedString("Maintenance", comment: "Maintenance"),
                        detail: NSLocalizedString("Oil, tyres, road tax and what is due next", comment: "Settings"))
                }
                Picker(selection: $breakHours) {
                    ForEach(VehicleReminders.breakChoices, id: \.self) { hours in
                        Text(hours == 0
                            ? NSLocalizedString("Off", comment: "Break reminder")
                            : String(format: NSLocalizedString("Every %d hours", comment: "Break reminder"), hours))
                            .tag(hours)
                    }
                } label: {
                    ValueRow(title: NSLocalizedString("Break reminder", comment: "Reminder"),
                        detail: NSLocalizedString("While CarPlay is connected; no location, only time", comment: "Settings"))
                }
                .pickerStyle(.navigationLink)
                .onChange(of: breakHours) { hours in
                    VehicleReminders.breakHours = hours
                    if hours > 0 { VehicleReminders.requestPermission() }
                }
                NavigationLink { CostsView() } label: {
                    ValueRow(title: NSLocalizedString("Car costs", comment: "Costs"),
                        detail: NSLocalizedString("What the car costs each month", comment: "Settings"))
                }
                NavigationLink { ParkingView() } label: {
                    ValueRow(title: NSLocalizedString("Parking spot", comment: "Parking"),
                        detail: NSLocalizedString("Mark where you parked and navigate back", comment: "Settings"))
                }
                NavigationLink { EmergencyView() } label: {
                    ValueRow(title: NSLocalizedString("Emergency card", comment: "Emergency"),
                        detail: NSLocalizedString("Insurance, roadside help, emergency lines", comment: "Settings"))
                }
                NavigationLink { BackupView() } label: {
                    ValueRow(title: NSLocalizedString("Backup and restore", comment: "Backup"),
                        detail: NSLocalizedString("Fuel or charging log and maintenance in one file", comment: "Settings"))
                }
            }

            Section(NSLocalizedString("Browser", comment: "Settings")) {
                NavigationLink {
                    YouTubeAddOnsView()
                } label: {
                    row(
                        NSLocalizedString("YouTube add-ons", comment: "Settings"),
                        youtube.anyEnabled
                            ? NSLocalizedString("On", comment: "Settings")
                            : NSLocalizedString("All off", comment: "Settings")
                    )
                }
            }

            Section(NSLocalizedString("Storage", comment: "Settings")) {
                Button {
                    catalog.clearAll()
                } label: {
                    row(
                        NSLocalizedString("Clear catalog cache", comment: "Settings"),
                        plural(catalog.cachedSourceCount, "source", "sources") + " "
                            + NSLocalizedString("held", comment: "Settings")
                    )
                }
                Button {
                    ImageLoader.shared.clear()
                    logoBytes = 0
                } label: {
                    row(
                        NSLocalizedString("Clear logo cache", comment: "Settings"),
                        byteText(logoBytes)
                    )
                }
                Button {
                    StreamPing.clear()
                } label: {
                    row(
                        NSLocalizedString("Clear channel-check results", comment: "Settings"),
                        NSLocalizedString("Checks run again on the next page", comment: "Settings")
                    )
                }
            }

            Section(NSLocalizedString("History", comment: "Settings")) {
                Button {
                    historyStore.clearRecent()
                } label: {
                    row(
                        NSLocalizedString("Clear recently played", comment: "Settings"),
                        plural(historyStore.recent.count, "item")
                    )
                }
                Button(role: .destructive) {
                    historyStore.clearFavorites()
                } label: {
                    row(
                        NSLocalizedString("Clear favorites", comment: "Settings"),
                        plural(historyStore.favorites.count, "channel")
                    )
                }
            }

            CarScreenVideoSection()
            CarScreenWebSection()

            Section(NSLocalizedString("Not on iOS", comment: "Settings")) {
                Text(
                    "Screen mirroring, touch injection into other apps and an arbitrary video surface on the car display are absent, not hidden: ReplayKit only captures this app's own screen, the App Sandbox has no equivalent of Shizuku or an accessibility input backend, and CarPlay is template-based with no mirroring category."
                )
                .font(.footnote)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
                Text(
                    "CarPlay here is the audio surface. It needs Apple's CarPlay audio entitlement to run on real hardware; without it the phone app is unaffected and the car scene simply never activates."
                )
                .font(.footnote)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            }

            Section(NSLocalizedString("About", comment: "Settings")) {
                row(NSLocalizedString("Version", comment: "Settings"), version)
                Text(
                    "Playlists and channels are fetched live from the projects that publish them, under those projects' own terms. Nothing is hosted, bundled or redistributed by AutoBridge."
                )
                .font(.footnote)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        .navigationTitle(NSLocalizedString("Settings", comment: "Home section"))
        .navigationBarTitleDisplayMode(.inline)
        .task { logoBytes = ImageLoader.shared.diskBytes() }
    }

    private func row(_ title: String, _ detail: String) -> some View {
        HStack {
            Text(title).foregroundStyle(AutoBridgeDesign.primaryText)
            Spacer()
            Text(detail)
                .font(.caption)
                .foregroundStyle(AutoBridgeDesign.secondaryText)
        }
    }

    private var version: String {
        let bundle = Bundle.main
        let short = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "\(short) (\(build))"
    }

    private func byteText(_ bytes: Int) -> String {
        if bytes <= 0 { return NSLocalizedString("Empty", comment: "Settings") }
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// The YouTube add-ons, each off until the user turns it on.
///
/// Mirrors the Android `YouTubeSettingsActivity`, including why each one is opt-in: skipping parts
/// of a video and overriding the quality a site chose are changes to what someone asked to watch,
/// SponsorBlock's lookups go to a third party, and skipping ads is something YouTube detects and
/// may answer with an interstitial of its own.
struct YouTubeAddOnsView: View {
    @EnvironmentObject private var youtube: YouTubeSettings

    var body: some View {
        List {
            Section {
                Toggle(
                    NSLocalizedString("SponsorBlock", comment: "YouTube add-on"),
                    isOn: $youtube.sponsorBlockEnabled
                )
            } footer: {
                Text(
                    "Skips crowd-sourced segments. The lookup asks sponsor.ajay.app for a four-character hash prefix of the video id, so the server is told a bucket of roughly 1 in 65,536 videos rather than which one is playing."
                )
            }

            if youtube.sponsorBlockEnabled {
                Section(NSLocalizedString("Categories", comment: "YouTube add-on")) {
                    ForEach(SponsorCategory.allCases) { category in
                        Toggle(
                            isOn: Binding(
                                get: { youtube.isEnabled(category) },
                                set: { youtube.setEnabled(category, $0) }
                            )
                        ) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(category.label)
                                Text(category.caption)
                                    .font(.caption)
                                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                            }
                        }
                    }
                }
            }

            Section {
                Toggle(
                    NSLocalizedString("Highest quality", comment: "YouTube add-on"),
                    isOn: $youtube.autoHighestQuality
                )
            } footer: {
                Text(
                    "Asks the page's own player for its best level once the player exists. A player that exposes no quality API is left alone."
                )
            }

            if YouTubeSettings.adSkipAvailable {
                Section {
                    Toggle(
                        NSLocalizedString("Skip ads", comment: "YouTube add-on"),
                        isOn: $youtube.adSkipEnabled
                    )
                } footer: {
                    Text(
                        "Presses Skip when YouTube offers it, and otherwise seeks an unskippable ad to its end. The ad is still fetched and may flash up for a frame. Every selector is YouTube's own internal class name, so this stops working without notice when they change it, and YouTube may answer with its \"ad blockers are not allowed\" interstitial — nothing here tries to defeat that check."
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        .tint(AutoBridgeDesign.accent)
        .navigationTitle(NSLocalizedString("YouTube add-ons", comment: "Settings"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
