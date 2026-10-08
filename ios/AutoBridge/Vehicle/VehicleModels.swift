import Foundation

// The car's own paperwork: fill-ups, maintenance, other costs, the emergency card and the parking
// spot. Every type here is a port of its Android counterpart (`dev.autobridge.fuel`, `.maintenance`,
// `.expense`, `.emergency`, `.parking`) and is stored as the same JSON, keys included, so a backup
// file moves between the two apps unchanged. The maths is kept pure so it is unit tested.

/// Milliseconds since 1970, the unit the Android stores and the backup file use.
typealias EpochMs = Int64

extension Date {
    var epochMs: EpochMs { EpochMs((timeIntervalSince1970 * 1000).rounded()) }
    init(epochMs: EpochMs) { self.init(timeIntervalSince1970: TimeInterval(epochMs) / 1000) }
}

/// A year and month, for the per-month sums.
struct YearMonth: Hashable, Comparable {
    let year: Int
    let month: Int

    init(year: Int, month: Int) {
        self.year = year
        self.month = month
    }

    init(_ epochMs: EpochMs, calendar: Calendar = .current) {
        let parts = calendar.dateComponents([.year, .month], from: Date(epochMs: epochMs))
        self.init(year: parts.year ?? 1970, month: parts.month ?? 1)
    }

    static func < (lhs: YearMonth, rhs: YearMonth) -> Bool {
        (lhs.year, lhs.month) < (rhs.year, rhs.month)
    }
}

// MARK: - Fuel

/// One fill-up, always to a full tank (or one charge, always to the same level), which is what lets
/// the litres put in stand for the fuel burned since the previous one. `odometerKm` is nil when it
/// was not known; such an entry still counts towards spending, just not towards consumption.
struct FuelEntry: Codable, Equatable, Identifiable {
    var id: EpochMs
    var timeMs: EpochMs
    var liters: Double
    var totalBaht: Double
    var odometerKm: Double?
    var station: String = ""
    /// The grade bought (Gasohol 95, diesel...); empty for an EV or an old entry.
    var fuelType: String = ""

    var pricePerLiter: Double { liters > 0 ? totalBaht / liters : 0 }

    enum CodingKeys: String, CodingKey {
        case id, timeMs = "time", liters, totalBaht = "baht", odometerKm = "odo", station, fuelType = "type"
    }

    init(id: EpochMs, timeMs: EpochMs, liters: Double, totalBaht: Double, odometerKm: Double?,
         station: String = "", fuelType: String = "") {
        self.id = id
        self.timeMs = timeMs
        self.liters = liters
        self.totalBaht = totalBaht
        self.odometerKm = odometerKm
        self.station = station
        self.fuelType = fuelType
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(EpochMs.self, forKey: .id) ?? 0
        timeMs = try c.decodeIfPresent(EpochMs.self, forKey: .timeMs) ?? 0
        liters = try c.decodeIfPresent(Double.self, forKey: .liters) ?? 0
        totalBaht = try c.decodeIfPresent(Double.self, forKey: .totalBaht) ?? 0
        odometerKm = try c.decodeIfPresent(Double.self, forKey: .odometerKm)
        station = try c.decodeIfPresent(String.self, forKey: .station) ?? ""
        fuelType = try c.decodeIfPresent(String.self, forKey: .fuelType) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(timeMs, forKey: .timeMs)
        try c.encode(liters, forKey: .liters)
        try c.encode(totalBaht, forKey: .totalBaht)
        // Written as null, the way the Android store writes an unknown odometer.
        try c.encode(odometerKm, forKey: .odometerKm)
        try c.encode(station, forKey: .station)
        try c.encode(fuelType, forKey: .fuelType)
    }
}

/// What the log adds up to. Nil figures are ones there is not yet enough data for.
struct FuelSummary: Equatable {
    var fillUps: Int
    var totalLiters: Double
    var totalBaht: Double
    var averageKmPerLiter: Double?
    var lastKmPerLiter: Double?
    var bahtPerKm: Double?
    var thisMonthBaht: Double
    var last: FuelEntry?
}

/// The consumption maths, by the full-to-full method, in date order: each fill-up's litres refuel
/// the distance driven since the previous fill-up with an odometer reading. A fill-up without a
/// reading is carried into the next interval; a reading that does not move forward is left out.
enum FuelStats {
    struct Interval: Equatable {
        let entry: FuelEntry
        let distanceKm: Double
        let liters: Double
        let baht: Double
        var kmPerLiter: Double { distanceKm / liters }
    }

    static func intervals(_ entries: [FuelEntry]) -> [Interval] {
        var result: [Interval] = []
        var startKm: Double?
        var carriedLiters = 0.0
        var carriedBaht = 0.0
        for entry in entries.filter({ $0.liters > 0 }).sorted(by: { $0.timeMs < $1.timeMs }) {
            guard let odometer = entry.odometerKm else {
                if startKm != nil {
                    carriedLiters += entry.liters
                    carriedBaht += entry.totalBaht
                }
                continue
            }
            guard let start = startKm else {
                startKm = odometer
                continue
            }
            if odometer <= start { continue }
            result.append(Interval(
                entry: entry,
                distanceKm: odometer - start,
                liters: carriedLiters + entry.liters,
                baht: carriedBaht + entry.totalBaht
            ))
            startKm = odometer
            carriedLiters = 0
            carriedBaht = 0
        }
        return result
    }

    static func summarize(_ entries: [FuelEntry], now: Date = Date(), calendar: Calendar = .current) -> FuelSummary {
        let intervals = intervals(entries)
        let distance = intervals.reduce(0) { $0 + $1.distanceKm }
        let intervalLiters = intervals.reduce(0) { $0 + $1.liters }
        let intervalBaht = intervals.reduce(0) { $0 + $1.baht }
        let month = YearMonth(now.epochMs, calendar: calendar)
        let thisMonth = entries.filter { YearMonth($0.timeMs, calendar: calendar) == month }
        return FuelSummary(
            fillUps: entries.count,
            totalLiters: entries.reduce(0) { $0 + $1.liters },
            totalBaht: entries.reduce(0) { $0 + $1.totalBaht },
            averageKmPerLiter: intervalLiters > 0 ? distance / intervalLiters : nil,
            lastKmPerLiter: intervals.last?.kmPerLiter,
            bahtPerKm: distance > 0 ? intervalBaht / distance : nil,
            thisMonthBaht: thisMonth.reduce(0) { $0 + $1.totalBaht },
            last: entries.max(by: { $0.timeMs < $1.timeMs })
        )
    }

    /// The consumption of the interval ending at `entry`, if it closes one.
    static func kmPerLiter(of entry: FuelEntry, in entries: [FuelEntry]) -> Double? {
        intervals(entries).first { $0.entry.id == entry.id }?.kmPerLiter
    }

    /// The log as CSV, oldest first, with the same columns as the Android export.
    static func csv(_ entries: [FuelEntry], ev: Bool, timeZone: TimeZone = .current) -> String {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = timeZone
        format.dateFormat = "yyyy-MM-dd HH:mm"
        func two(_ value: Double) -> String { String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), value) }
        func quoted(_ text: String) -> String { "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var lines = [ev
            ? "date,kwh,total_baht,price_per_kwh,odometer_km,km_per_kwh,station,fuel_type"
            : "date,liters,total_baht,price_per_liter,odometer_km,km_per_liter,station,fuel_type"]
        for entry in entries.sorted(by: { $0.timeMs < $1.timeMs }) {
            lines.append([
                format.string(from: Date(epochMs: entry.timeMs)),
                two(entry.liters),
                two(entry.totalBaht),
                two(entry.pricePerLiter),
                entry.odometerKm.map { String(Int64($0.rounded())) } ?? "",
                kmPerLiter(of: entry, in: entries).map(two) ?? "",
                quoted(entry.station),
                quoted(entry.fuelType),
            ].joined(separator: ","))
        }
        return lines.joined(separator: "\n") + "\n"
    }

    /// The grades offered when logging a fill-up, the Thai pump names first.
    static let fuelTypes = [
        "Gasohol 95", "Gasohol 91", "Gasohol E20", "Gasohol E85", "Benzine 95",
        "Diesel (B7)", "Premium diesel", "LPG", "NGV",
    ]
}

// MARK: - Maintenance

/// One thing to look after: due every `intervalKm` km, every `intervalMonths` months, or whichever
/// comes first; either may be 0. `dueDateMs`, when set, is a date the driver gave outright and
/// stands in for "last done + months".
struct MaintenanceItem: Codable, Equatable, Identifiable {
    var id: EpochMs
    var name: String
    var intervalKm: Int
    var intervalMonths: Int
    var lastKm: Double?
    var lastTimeMs: EpochMs
    var dueDateMs: EpochMs = 0

    enum CodingKeys: String, CodingKey {
        case id, name, intervalKm = "km", intervalMonths = "months", lastKm = "last_km"
        case lastTimeMs = "last_time", dueDateMs = "due_date"
    }

    init(id: EpochMs, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double?,
         lastTimeMs: EpochMs, dueDateMs: EpochMs = 0) {
        self.id = id
        self.name = name
        self.intervalKm = intervalKm
        self.intervalMonths = intervalMonths
        self.lastKm = lastKm
        self.lastTimeMs = lastTimeMs
        self.dueDateMs = dueDateMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(EpochMs.self, forKey: .id) ?? 0
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        intervalKm = try c.decodeIfPresent(Int.self, forKey: .intervalKm) ?? 0
        intervalMonths = try c.decodeIfPresent(Int.self, forKey: .intervalMonths) ?? 0
        lastKm = try c.decodeIfPresent(Double.self, forKey: .lastKm)
        lastTimeMs = try c.decodeIfPresent(EpochMs.self, forKey: .lastTimeMs) ?? 0
        dueDateMs = try c.decodeIfPresent(EpochMs.self, forKey: .dueDateMs) ?? 0
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(intervalKm, forKey: .intervalKm)
        try c.encode(intervalMonths, forKey: .intervalMonths)
        try c.encode(lastKm, forKey: .lastKm)
        try c.encode(lastTimeMs, forKey: .lastTimeMs)
        try c.encode(dueDateMs, forKey: .dueDateMs)
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && (intervalKm > 0 || intervalMonths > 0) }
}

enum DueState: Int, Comparable {
    case ok, soon, overdue
    static func < (lhs: DueState, rhs: DueState) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// `kmLeft` and `daysLeft` are nil when that interval is not set, negative once passed. `due` is
/// when it falls due: the date, or the day the distance is reached at the usual pace.
struct MaintenanceStatus: Equatable {
    let item: MaintenanceItem
    let state: DueState
    let kmLeft: Double?
    let daysLeft: Int?
    let due: Date?
}

/// What a new vehicle is offered to start with. `name` is the English key, localized by the UI.
struct MaintenancePreset: Equatable {
    let name: String
    let intervalKm: Int
    let intervalMonths: Int
}

enum MaintenanceStats {
    /// "Soon" is the last tenth of a distance interval (at least 300 km), or the last 30 days.
    static let soonDays = 30
    static let soonMinKm = 300.0
    private static let dayMs: Double = 24 * 60 * 60 * 1000

    static let fuelPresets = [
        MaintenancePreset(name: "Engine oil", intervalKm: 10_000, intervalMonths: 12),
        MaintenancePreset(name: "Tyre rotation", intervalKm: 10_000, intervalMonths: 0),
        MaintenancePreset(name: "Air filter", intervalKm: 20_000, intervalMonths: 24),
        MaintenancePreset(name: "Brake fluid", intervalKm: 40_000, intervalMonths: 24),
        MaintenancePreset(name: "Road tax", intervalKm: 0, intervalMonths: 12),
        MaintenancePreset(name: "Compulsory insurance (Por.Ror.Bor.)", intervalKm: 0, intervalMonths: 12),
    ]

    /// An EV has no oil or air filter; it has a cabin filter, a battery coolant and a 12 V battery.
    static let evPresets = [
        MaintenancePreset(name: "Tyre rotation", intervalKm: 10_000, intervalMonths: 0),
        MaintenancePreset(name: "Cabin air filter", intervalKm: 20_000, intervalMonths: 12),
        MaintenancePreset(name: "Brake fluid", intervalKm: 40_000, intervalMonths: 24),
        MaintenancePreset(name: "Battery coolant", intervalKm: 80_000, intervalMonths: 48),
        MaintenancePreset(name: "12 V battery", intervalKm: 0, intervalMonths: 36),
        MaintenancePreset(name: "Road tax", intervalKm: 0, intervalMonths: 12),
        MaintenancePreset(name: "Compulsory insurance (Por.Ror.Bor.)", intervalKm: 0, intervalMonths: 12),
    ]

    static func presets(ev: Bool) -> [MaintenancePreset] { ev ? evPresets : fuelPresets }

    /// The date `item` falls due by the calendar, or nil when it has no date side.
    static func dateDue(_ item: MaintenanceItem, calendar: Calendar = .current) -> Date? {
        if item.dueDateMs > 0 { return Date(epochMs: item.dueDateMs) }
        if item.intervalMonths > 0, item.lastTimeMs > 0 {
            return calendar.date(byAdding: .month, value: item.intervalMonths, to: Date(epochMs: item.lastTimeMs))
        }
        return nil
    }

    /// How far the car goes in a day, from the first and last fill-ups with an odometer, once they
    /// are at least two weeks apart and moved forward.
    static func kmPerDay(_ entries: [FuelEntry]) -> Double? {
        let dated = entries.filter { $0.odometerKm != nil }.sorted { $0.timeMs < $1.timeMs }
        guard dated.count >= 2, let first = dated.first, let last = dated.last,
              let firstKm = first.odometerKm, let lastKm = last.odometerKm else { return nil }
        let days = Double(last.timeMs - first.timeMs) / dayMs
        let km = lastKm - firstKm
        return days >= 14 && km > 0 ? km / days : nil
    }

    static func status(
        _ item: MaintenanceItem,
        odometerKm: Double?,
        now: Date,
        calendar: Calendar = .current,
        kmPerDay: Double? = nil
    ) -> MaintenanceStatus {
        var kmLeft: Double?
        if item.intervalKm > 0, let last = item.lastKm, let odometer = odometerKm {
            kmLeft = last + Double(item.intervalKm) - odometer
        }
        let dateDue = dateDue(item, calendar: calendar)
        // Rounded toward the past: a due date hours behind is already -1 days.
        let daysLeft = dateDue.map { Int((Double($0.epochMs - now.epochMs) / dayMs).rounded(.down)) }
        var kmDue: Date?
        if let left = kmLeft, left >= 0, let pace = kmPerDay, pace > 0 {
            kmDue = now.addingTimeInterval(left / pace * 86_400)
        }
        let overdue = (kmLeft.map { $0 < 0 } ?? false) || (daysLeft.map { $0 < 0 } ?? false)
        let soon = (kmLeft.map { $0 <= max(soonMinKm, Double(item.intervalKm) / 10) } ?? false)
            || (daysLeft.map { $0 <= soonDays } ?? false)
        return MaintenanceStatus(
            item: item,
            state: overdue ? .overdue : (soon ? .soon : .ok),
            kmLeft: kmLeft,
            daysLeft: daysLeft,
            due: [dateDue, kmDue].compactMap { $0 }.min()
        )
    }

    /// Every item with its status, the most urgent first.
    static func ordered(
        _ items: [MaintenanceItem],
        odometerKm: Double?,
        now: Date,
        calendar: Calendar = .current,
        kmPerDay: Double? = nil
    ) -> [MaintenanceStatus] {
        items.map { status($0, odometerKm: odometerKm, now: now, calendar: calendar, kmPerDay: kmPerDay) }
            .sorted { a, b in
                if a.state != b.state { return a.state > b.state }
                let ad = a.daysLeft ?? .max, bd = b.daysLeft ?? .max
                if ad != bd { return ad < bd }
                return (a.kmLeft ?? .greatestFiniteMagnitude) < (b.kmLeft ?? .greatestFiniteMagnitude)
            }
    }
}

// MARK: - Costs

/// Money spent on the car outside the fuel log: a service, a wash, tolls, parking, a tax.
struct Expense: Codable, Equatable, Identifiable {
    var id: EpochMs
    var timeMs: EpochMs
    var label: String
    var baht: Double

    enum CodingKeys: String, CodingKey { case id, timeMs = "time", label, baht }

    init(id: EpochMs, timeMs: EpochMs, label: String, baht: Double) {
        self.id = id
        self.timeMs = timeMs
        self.label = label
        self.baht = baht
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(EpochMs.self, forKey: .id) ?? 0
        timeMs = try c.decodeIfPresent(EpochMs.self, forKey: .timeMs) ?? 0
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        baht = try c.decodeIfPresent(Double.self, forKey: .baht) ?? 0
    }
}

/// One month's spending, split between fuel (or charging) and everything else.
struct MonthCost: Equatable {
    let month: YearMonth
    let fuelBaht: Double
    let otherBaht: Double
    var totalBaht: Double { fuelBaht + otherBaht }
}

enum CostStats {
    /// Spending per month, newest first, for every month that has any.
    static func monthly(fuel: [FuelEntry], expenses: [Expense], calendar: Calendar = .current) -> [MonthCost] {
        var fuelBy: [YearMonth: Double] = [:]
        var otherBy: [YearMonth: Double] = [:]
        for entry in fuel { fuelBy[YearMonth(entry.timeMs, calendar: calendar), default: 0] += entry.totalBaht }
        for expense in expenses { otherBy[YearMonth(expense.timeMs, calendar: calendar), default: 0] += expense.baht }
        return Set(fuelBy.keys).union(otherBy.keys).sorted(by: >).map {
            MonthCost(month: $0, fuelBaht: fuelBy[$0] ?? 0, otherBaht: otherBy[$0] ?? 0)
        }
    }

    /// The current month, with zeros when nothing was spent yet.
    static func thisMonth(_ costs: [MonthCost], now: Date, calendar: Calendar = .current) -> MonthCost {
        let current = YearMonth(now.epochMs, calendar: calendar)
        return costs.first { $0.month == current } ?? MonthCost(month: current, fuelBaht: 0, otherBaht: 0)
    }

    /// The average of up to `months` full months before the current one that have any spending.
    static func average(_ costs: [MonthCost], now: Date, months: Int = 3, calendar: Calendar = .current) -> MonthCost? {
        let current = YearMonth(now.epochMs, calendar: calendar)
        let past = Array(costs.filter { $0.month < current }.prefix(months))
        guard !past.isEmpty else { return nil }
        let count = Double(past.count)
        return MonthCost(
            month: current,
            fuelBaht: past.reduce(0) { $0 + $1.fuelBaht } / count,
            otherBaht: past.reduce(0) { $0 + $1.otherBaht } / count
        )
    }
}

// MARK: - Emergency card

/// One line of the emergency card: a label and a phone number to dial or text to quote.
struct EmergencyEntry: Codable, Equatable, Identifiable {
    var id: EpochMs
    var label: String
    var value: String

    var dialable: String? { EmergencyNumber.dialable(value) }
}

enum EmergencyNumber {
    private static let phone = try! NSRegularExpression(pattern: #"^\+?\(?[0-9][0-9 \-().]{1,18}$"#)

    /// `value` as digits (and a leading +) when it looks like a phone number: 191, 1669,
    /// 02-123-4567, +66 81 234 5678. A policy number with letters is not one.
    static func dialable(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        let range = NSRange(trimmed.startIndex..., in: trimmed)
        guard phone.firstMatch(in: trimmed, range: range) != nil else { return nil }
        let digits = trimmed.filter(\.isASCIIDigit)
        guard digits.count >= 3 else { return nil }
        return (trimmed.hasPrefix("+") ? "+" : "") + digits
    }

    /// The Thai public lines, offered with one tap. Names are English keys, localized by the UI.
    static let publicLines: [(label: String, number: String)] = [
        ("Police", "191"),
        ("Medical emergency", "1669"),
        ("Highway police", "1193"),
        ("Fire", "199"),
        ("Tourist police", "1155"),
    ]
}

private extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}

// MARK: - Parking

/// Where the car was left, and a note to find it by ("B2, pillar 14").
struct ParkingSpot: Codable, Equatable {
    var lat: Double
    var lon: Double
    var note: String
    var savedMs: EpochMs

    /// True for a latitude and longitude that can be a place on Earth.
    static func isValid(lat: Double, lon: Double) -> Bool {
        (-90...90).contains(lat) && (-180...180).contains(lon) && !(lat == 0 && lon == 0)
    }

    /// Apple Maps, driving directions to the spot.
    var directionsURL: URL? {
        URL(string: String(format: "https://maps.apple.com/?daddr=%.6f,%.6f&dirflg=d", locale: Locale(identifier: "en_US_POSIX"), lat, lon))
    }
}

// MARK: - Backup

/// The backup file, the same document the Android app writes: the fuel or charging log, the
/// maintenance list, the trips and the other expenses. Trips are an Android-only log; they are
/// carried through untouched so a file restored here and backed up again loses nothing.
struct VehicleBackup: Equatable {
    static let version = 1

    var ev: Bool
    var fuel: [FuelEntry]
    var maintenance: [MaintenanceItem]
    var expenses: [Expense]
    var tripsJSON: Data

    func write() throws -> Data {
        let encoder = JSONEncoder()
        let root: [String: Any] = [
            "app": "autobridge",
            "version": Self.version,
            "ev": ev,
            "fuel": try JSONSerialization.jsonObject(with: encoder.encode(fuel)),
            "maintenance": try JSONSerialization.jsonObject(with: encoder.encode(maintenance)),
            "trips": (try? JSONSerialization.jsonObject(with: tripsJSON)) as? [Any] ?? [],
            "expenses": try JSONSerialization.jsonObject(with: encoder.encode(expenses)),
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    /// The backup in `data`, or nil if it is not one of ours (or from a newer version).
    static func read(_ data: Data) -> VehicleBackup? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              root["app"] as? String == "autobridge",
              let version = root["version"] as? Int, (1...Self.version).contains(version),
              let fuel = root["fuel"] as? [Any],
              let maintenance = root["maintenance"] as? [Any],
              let trips = root["trips"] as? [Any] else { return nil }
        let decoder = JSONDecoder()
        func decode<T: Decodable>(_ value: [Any], as: T.Type) -> T? {
            guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
            return try? decoder.decode(T.self, from: data)
        }
        guard let fuelEntries = decode(fuel, as: [FuelEntry].self),
              let items = decode(maintenance, as: [MaintenanceItem].self) else { return nil }
        // Expenses came after the first backups, which simply have none.
        let expenses = (root["expenses"] as? [Any]).flatMap { decode($0, as: [Expense].self) } ?? []
        return VehicleBackup(
            ev: root["ev"] as? Bool ?? false,
            fuel: fuelEntries,
            maintenance: items,
            expenses: expenses,
            tripsJSON: (try? JSONSerialization.data(withJSONObject: trips)) ?? Data("[]".utf8)
        )
    }
}
