import SwiftUI

/// The Weather section: current conditions and today's high and low for one place the user picks
/// by name. Open-Meteo, no account and no location permission. The port of `WeatherActivity`.
struct WeatherView: View {
    @EnvironmentObject private var weather: WeatherStore
    @State private var query = ""
    @State private var results: [WeatherPlace] = []
    @State private var searching = false

    var body: some View {
        List {
            if let snapshot = weather.snapshot {
                Section {
                    HStack(alignment: .center, spacing: 16) {
                        Image(systemName: snapshot.symbol)
                            .symbolRenderingMode(.multicolor)
                            .font(.system(size: 48))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(Int(snapshot.temperatureC.rounded()))°C")
                                .font(.system(size: 40, weight: .semibold))
                                .foregroundStyle(AutoBridgeDesign.primaryText)
                            Text(snapshot.condition).foregroundStyle(AutoBridgeDesign.secondaryText)
                        }
                    }
                    .padding(.vertical, 6)
                    if let feels = snapshot.feelsLikeC {
                        Text(String(format: NSLocalizedString("Feels like %d°C", comment: "Weather"), Int(feels.rounded())))
                    }
                    if let high = snapshot.todayHighC, let low = snapshot.todayLowC {
                        Text(String(format: NSLocalizedString("Today: high %d°C, low %d°C", comment: "Weather"),
                                    Int(high.rounded()), Int(low.rounded())))
                    }
                    Text(String(format: NSLocalizedString("Wind %d km/h", comment: "Weather"), Int(snapshot.windKph.rounded())))
                } header: {
                    Text(snapshot.place.displayName)
                } footer: {
                    Text(String(format: NSLocalizedString("Updated %@ · Open-Meteo", comment: "Weather"),
                                snapshot.fetchedAt.formatted(date: .omitted, time: .shortened)))
                }
            } else if weather.place == nil {
                EmptyState(
                    title: NSLocalizedString("Pick a place", comment: "Weather"),
                    message: NSLocalizedString("Search for a city to see its current weather.", comment: "Weather")
                )
            } else if weather.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let failure = weather.failure {
                Text(failure).foregroundStyle(AutoBridgeDesign.danger)
            }

            Section(NSLocalizedString("Change place", comment: "Weather")) {
                TextField(NSLocalizedString("City name", comment: "Weather"), text: $query)
                    .submitLabel(.search)
                    .onSubmit(search)
                if searching { ProgressView() }
                ForEach(results) { place in
                    Button(place.displayName) {
                        weather.setPlace(place)
                        results = []
                        query = ""
                    }
                }
            }
        }
        .vehicleListStyle(NSLocalizedString("Weather", comment: "Home section"))
        .refreshable { await weather.refresh(force: true) }
        .task { await weather.refresh() }
    }

    private func search() {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        searching = true
        Task {
            results = (try? await WeatherClient.search(text)) ?? []
            searching = false
        }
    }
}
