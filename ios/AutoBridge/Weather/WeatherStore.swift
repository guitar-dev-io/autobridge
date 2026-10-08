import Foundation

/// One geocoding match: a place name plus the coordinates a forecast call needs.
struct WeatherPlace: Codable, Equatable, Identifiable {
    var name: String
    var admin1: String?
    var country: String?
    var latitude: Double
    var longitude: Double

    var id: String { "\(latitude),\(longitude)" }

    /// "Bangkok, Thailand" when both parts exist, otherwise whatever is available.
    var displayName: String {
        var parts = [name]
        if let admin1, admin1 != name { parts.append(admin1) }
        if let country { parts.append(country) }
        var seen = Set<String>()
        return parts.filter { seen.insert($0).inserted }.joined(separator: ", ")
    }
}

/// Current conditions plus today's high and low for one place.
struct WeatherSnapshot: Equatable {
    var place: WeatherPlace
    var temperatureC: Double
    var feelsLikeC: Double?
    var windKph: Double
    var weatherCode: Int
    var isDay: Bool
    var todayHighC: Double?
    var todayLowC: Double?
    var fetchedAt: Date

    var condition: String { WeatherCodes.describe(weatherCode) }
    var symbol: String { WeatherCodes.symbol(weatherCode, isDay: isDay) }
}

/// WMO weather codes as Open-Meteo reports them, mapped to a short condition. The same ranges as
/// the Android `WeatherCodes`.
enum WeatherCodes {
    static func describe(_ code: Int) -> String {
        let key: String
        switch code {
        case 0: key = "Clear sky"
        case 1, 2, 3: key = "Partly cloudy"
        case 45, 48: key = "Fog"
        case 51, 53, 55: key = "Drizzle"
        case 56, 57: key = "Freezing drizzle"
        case 61, 63, 65: key = "Rain"
        case 66, 67: key = "Freezing rain"
        case 71, 73, 75: key = "Snow"
        case 77: key = "Snow grains"
        case 80, 81, 82: key = "Rain showers"
        case 85, 86: key = "Snow showers"
        case 95: key = "Thunderstorm"
        case 96, 99: key = "Thunderstorm with hail"
        default: key = "Weather"
        }
        return NSLocalizedString(key, comment: "Weather condition")
    }

    static func symbol(_ code: Int, isDay: Bool) -> String {
        switch code {
        case 0: return isDay ? "sun.max.fill" : "moon.stars.fill"
        case 1, 2, 3: return isDay ? "cloud.sun.fill" : "cloud.moon.fill"
        case 45, 48: return "cloud.fog.fill"
        case 51, 53, 55, 56, 57: return "cloud.drizzle.fill"
        case 61, 63, 65, 66, 67, 80, 81, 82: return "cloud.rain.fill"
        case 71, 73, 75, 77, 85, 86: return "cloud.snow.fill"
        case 95, 96, 99: return "cloud.bolt.rain.fill"
        default: return "cloud.fill"
        }
    }
}

/// Open-Meteo's free geocoding and forecast endpoints. No API key: they are keyless for
/// non-commercial use, which fits the app's no-account model.
enum WeatherClient {
    private static let geocoding = "https://geocoding-api.open-meteo.com/v1/search"
    private static let forecastURL = "https://api.open-meteo.com/v1/forecast"

    static func search(_ query: String) async throws -> [WeatherPlace] {
        var components = URLComponents(string: geocoding)!
        components.queryItems = [
            URLQueryItem(name: "name", value: query),
            URLQueryItem(name: "count", value: "5"),
            URLQueryItem(name: "language", value: "en"),
        ]
        let root = try await fetch(components.url!)
        let results = root["results"] as? [[String: Any]] ?? []
        return results.compactMap { entry in
            guard let lat = entry["latitude"] as? Double, let lon = entry["longitude"] as? Double else { return nil }
            func text(_ key: String) -> String? { (entry[key] as? String).flatMap { $0.isEmpty ? nil : $0 } }
            return WeatherPlace(name: text("name") ?? "", admin1: text("admin1"), country: text("country"),
                                latitude: lat, longitude: lon)
        }
    }

    static func forecast(_ place: WeatherPlace) async throws -> WeatherSnapshot {
        var components = URLComponents(string: forecastURL)!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(place.latitude)),
            URLQueryItem(name: "longitude", value: String(place.longitude)),
            URLQueryItem(name: "current", value: "temperature_2m,apparent_temperature,wind_speed_10m,weather_code,is_day"),
            URLQueryItem(name: "daily", value: "temperature_2m_max,temperature_2m_min"),
            URLQueryItem(name: "forecast_days", value: "1"),
            URLQueryItem(name: "timezone", value: "auto"),
        ]
        let root = try await fetch(components.url!)
        let current = root["current"] as? [String: Any] ?? [:]
        let daily = root["daily"] as? [String: Any]
        return WeatherSnapshot(
            place: place,
            temperatureC: (current["temperature_2m"] as? Double) ?? 0,
            feelsLikeC: current["apparent_temperature"] as? Double,
            windKph: (current["wind_speed_10m"] as? Double) ?? 0,
            weatherCode: (current["weather_code"] as? Int) ?? -1,
            isDay: ((current["is_day"] as? Int) ?? 1) == 1,
            todayHighC: (daily?["temperature_2m_max"] as? [Double])?.first,
            todayLowC: (daily?["temperature_2m_min"] as? [Double])?.first,
            fetchedAt: Date()
        )
    }

    private static func fetch(_ url: URL) async throws -> [String: Any] {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}

/// The Weather section's place and its latest snapshot, refreshed at most every 15 minutes unless
/// asked. Mirrors the Android `WeatherRepository` + `WeatherLocationStore`.
@MainActor
final class WeatherStore: ObservableObject {
    @Published private(set) var place: WeatherPlace?
    @Published private(set) var snapshot: WeatherSnapshot?
    @Published private(set) var loading = false
    @Published private(set) var failure: String?

    private static let key = "weather.place"
    private static let freshness: TimeInterval = 15 * 60
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key) {
            place = try? JSONDecoder().decode(WeatherPlace.self, from: data)
        }
    }

    func setPlace(_ place: WeatherPlace) {
        self.place = place
        snapshot = nil
        if let data = try? JSONEncoder().encode(place) { defaults.set(data, forKey: Self.key) }
        Task { await refresh(force: true) }
    }

    /// "31°" for the home card, or nil until a snapshot has loaded.
    var label: String? { snapshot.map { "\(Int($0.temperatureC.rounded()))°C" } }

    func refresh(force: Bool = false) async {
        guard let place, !loading else { return }
        if !force, let snapshot, snapshot.place == place, Date().timeIntervalSince(snapshot.fetchedAt) < Self.freshness {
            return
        }
        loading = true
        failure = nil
        defer { loading = false }
        do {
            snapshot = try await WeatherClient.forecast(place)
        } catch {
            failure = error.localizedDescription
        }
    }
}
