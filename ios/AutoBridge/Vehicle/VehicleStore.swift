import Foundation
import UIKit

/// The car's paperwork, on this phone only: the fuel or charging log, the maintenance list, the
/// other costs, the emergency card and the parking spot.
///
/// Each list is a small JSON array in `UserDefaults`, in the Android shape, the way the Android
/// stores keep them in shared preferences: a car's worth of rows a decade needs no database.
@MainActor
final class VehicleStore: ObservableObject {
    @Published private(set) var fuel: [FuelEntry] = []
    @Published private(set) var maintenance: [MaintenanceItem] = []
    @Published private(set) var expenses: [Expense] = []
    @Published private(set) var emergency: [EmergencyEntry] = []
    @Published private(set) var parking: ParkingSpot?
    @Published private(set) var hasParkingPhoto = false
    /// The log counts kWh instead of litres; one vehicle is either one or the other.
    @Published var isEv: Bool {
        didSet { defaults.set(isEv, forKey: Keys.ev) }
    }

    private enum Keys {
        static let fuel = "vehicle.fuel"
        static let ev = "vehicle.ev"
        static let maintenance = "vehicle.maintenance"
        static let expenses = "vehicle.expenses"
        static let emergency = "vehicle.emergency"
        static let parking = "vehicle.parking"
        static let trips = "vehicle.trips"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isEv = defaults.bool(forKey: Keys.ev)
        fuel = load([FuelEntry].self, Keys.fuel)?.filter { $0.liters > 0 }.sorted { $0.timeMs > $1.timeMs } ?? []
        maintenance = load([MaintenanceItem].self, Keys.maintenance)?.filter(\.isValid) ?? []
        expenses = load([Expense].self, Keys.expenses)?.filter { $0.timeMs > 0 && $0.baht > 0 }
            .sorted { $0.timeMs > $1.timeMs } ?? []
        emergency = load([EmergencyEntry].self, Keys.emergency) ?? []
        parking = load(ParkingSpot.self, Keys.parking).flatMap { ParkingSpot.isValid(lat: $0.lat, lon: $0.lon) ? $0 : nil }
        hasParkingPhoto = FileManager.default.fileExists(atPath: Self.parkingPhotoURL.path)
    }

    /// A fresh id: its own, since a date can be in the past and two entries may share one.
    private func newId(after ids: [EpochMs]) -> EpochMs {
        max(Date().epochMs, (ids.max() ?? 0) + 1)
    }

    // MARK: Fuel

    func addFuel(liters: Double, baht: Double, odometerKm: Double?, station: String, fuelType: String, date: Date) {
        let entry = FuelEntry(
            id: newId(after: fuel.map(\.id)), timeMs: date.epochMs, liters: liters, totalBaht: baht,
            odometerKm: odometerKm, station: clean(station), fuelType: clean(fuelType)
        )
        saveFuel(fuel + [entry])
    }

    func updateFuel(_ entry: FuelEntry) {
        var edited = entry
        edited.station = clean(entry.station)
        edited.fuelType = clean(entry.fuelType)
        saveFuel(fuel.map { $0.id == entry.id ? edited : $0 })
    }

    func deleteFuel(_ id: EpochMs) { saveFuel(fuel.filter { $0.id != id }) }

    /// The grade of the latest fill-up that named one, offered again for the next.
    var lastFuelType: String { fuel.first { !$0.fuelType.isEmpty }?.fuelType ?? "" }

    /// The highest odometer logged; an odometer only goes up.
    var lastOdometer: Double? { fuel.compactMap(\.odometerKm).max() }

    private func saveFuel(_ entries: [FuelEntry]) {
        // Stored oldest first like the Android store; held newest first for the list.
        save(entries.sorted { $0.timeMs < $1.timeMs }, Keys.fuel)
        fuel = entries.sorted { $0.timeMs > $1.timeMs }
    }

    // MARK: Maintenance

    func addMaintenance(name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double?, dueDate: Date?) {
        let item = MaintenanceItem(
            id: newId(after: maintenance.map(\.id)), name: clean(name), intervalKm: intervalKm,
            intervalMonths: intervalMonths, lastKm: lastKm, lastTimeMs: Date().epochMs,
            dueDateMs: dueDate?.epochMs ?? 0
        )
        saveMaintenance(maintenance + [item])
    }

    /// The suggested items for this vehicle that are not on the list yet, by name.
    var missingPresets: [MaintenancePreset] {
        let have = Set(maintenance.map(\.name))
        return MaintenanceStats.presets(ev: isEv).filter { !have.contains(NSLocalizedString($0.name, comment: "Maintenance item")) }
    }

    func addPresets() {
        let odometer = currentOdometer
        var items = maintenance
        for preset in missingPresets {
            items.append(MaintenanceItem(
                id: newId(after: items.map(\.id)), name: NSLocalizedString(preset.name, comment: "Maintenance item"),
                intervalKm: preset.intervalKm, intervalMonths: preset.intervalMonths,
                lastKm: odometer, lastTimeMs: Date().epochMs
            ))
        }
        saveMaintenance(items)
    }

    /// Records the item as done now, at `km`. A date given outright is spent by doing it. A cost,
    /// when given, goes into the other expenses under the item's name.
    func markDone(_ id: EpochMs, km: Double?, cost: Double?) {
        let now = Date().epochMs
        saveMaintenance(maintenance.map {
            guard $0.id == id else { return $0 }
            var done = $0
            done.lastKm = km ?? $0.lastKm
            done.lastTimeMs = now
            done.dueDateMs = 0
            return done
        })
        if let cost, cost > 0, let item = maintenance.first(where: { $0.id == id }) {
            addExpense(label: item.name, baht: cost, date: Date())
        }
    }

    func deleteMaintenance(_ id: EpochMs) { saveMaintenance(maintenance.filter { $0.id != id }) }

    /// The best odometer known: the highest logged with a fill-up. (The Android app can also read
    /// the car's own report; CarPlay offers no such data to an audio app.)
    var currentOdometer: Double? { lastOdometer }

    var kmPerDay: Double? { MaintenanceStats.kmPerDay(fuel) }

    func maintenanceStatus(now: Date = Date()) -> [MaintenanceStatus] {
        MaintenanceStats.ordered(maintenance, odometerKm: currentOdometer, now: now, kmPerDay: kmPerDay)
    }

    private func saveMaintenance(_ items: [MaintenanceItem]) {
        save(items, Keys.maintenance)
        maintenance = items
    }

    // MARK: Costs

    @discardableResult
    func addExpense(label: String, baht: Double, date: Date) -> Bool {
        guard baht > 0, baht.isFinite else { return false }
        let expense = Expense(id: newId(after: expenses.map(\.id)), timeMs: date.epochMs, label: clean(label), baht: baht)
        saveExpenses(expenses + [expense])
        return true
    }

    func deleteExpense(_ id: EpochMs) { saveExpenses(expenses.filter { $0.id != id }) }

    private func saveExpenses(_ items: [Expense]) {
        save(items, Keys.expenses)
        expenses = items.sorted { $0.timeMs > $1.timeMs }
    }

    // MARK: Emergency card

    func addEmergency(label: String, value: String) {
        let entry = EmergencyEntry(id: newId(after: emergency.map(\.id)), label: clean(label), value: clean(value))
        saveEmergency(emergency + [entry])
    }

    /// The public lines not on the card yet.
    var missingPublicLines: [(label: String, number: String)] {
        let numbers = Set(emergency.compactMap(\.dialable))
        return EmergencyNumber.publicLines.filter { !numbers.contains($0.number) }
    }

    func addPublicLines() {
        var entries = emergency
        for line in missingPublicLines {
            entries.append(EmergencyEntry(
                id: newId(after: entries.map(\.id)),
                label: NSLocalizedString(line.label, comment: "Emergency line"),
                value: line.number
            ))
        }
        saveEmergency(entries)
    }

    func deleteEmergency(_ id: EpochMs) { saveEmergency(emergency.filter { $0.id != id }) }

    private func saveEmergency(_ entries: [EmergencyEntry]) {
        save(entries, Keys.emergency)
        emergency = entries
    }

    // MARK: Parking

    /// Saves the spot; false when the position is not a real place. Parking somewhere new retires
    /// the old spot's photo.
    @discardableResult
    func park(lat: Double, lon: Double, note: String = "") -> Bool {
        guard ParkingSpot.isValid(lat: lat, lon: lon) else { return false }
        if parking != nil { deleteParkingPhoto() }
        let spot = ParkingSpot(lat: lat, lon: lon, note: clean(note), savedMs: Date().epochMs)
        save(spot, Keys.parking)
        parking = spot
        return true
    }

    func setParkingNote(_ note: String) {
        guard var spot = parking else { return }
        spot.note = clean(note)
        save(spot, Keys.parking)
        parking = spot
    }

    func clearParking() {
        deleteParkingPhoto()
        defaults.removeObject(forKey: Keys.parking)
        parking = nil
    }

    /// The spot's photo: one file in Application Support, on this phone only, never shared.
    static var parkingPhotoURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("parking/spot.jpg")
    }

    func setParkingPhoto(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.8) else { return }
        let url = Self.parkingPhotoURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        hasParkingPhoto = (try? data.write(to: url, options: [.atomic, .completeFileProtection])) != nil
    }

    func deleteParkingPhoto() {
        try? FileManager.default.removeItem(at: Self.parkingPhotoURL)
        hasParkingPhoto = false
    }

    // MARK: Backup

    func backup() throws -> Data {
        try VehicleBackup(
            ev: isEv, fuel: fuel, maintenance: maintenance, expenses: expenses,
            tripsJSON: defaults.data(forKey: Keys.trips) ?? Data("[]".utf8)
        ).write()
    }

    /// Replaces the logs with the backup's; false, changing nothing, when it is not one of ours.
    func restore(_ data: Data) -> Bool {
        guard let backup = VehicleBackup.read(data) else { return false }
        isEv = backup.ev
        saveFuel(backup.fuel.filter { $0.liters > 0 })
        saveMaintenance(backup.maintenance.filter(\.isValid))
        saveExpenses(backup.expenses.filter { $0.timeMs > 0 && $0.baht > 0 })
        defaults.set(backup.tripsJSON, forKey: Keys.trips)
        return true
    }

    // MARK: Storage

    private func clean(_ text: String) -> String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func load<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }
}
