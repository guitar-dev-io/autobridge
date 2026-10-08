import XCTest
@testable import AutoBridge

/// The car utilities' maths, checked against the same cases as the Android JVM tests.
final class VehicleTests: XCTestCase {
    private let day: EpochMs = 24 * 60 * 60 * 1000
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private func entry(_ id: EpochMs, day d: EpochMs, liters: Double, baht: Double, odo: Double?) -> FuelEntry {
        FuelEntry(id: id, timeMs: d * day, liters: liters, totalBaht: baht, odometerKm: odo)
    }

    func testFullToFullIntervalsCarryAnEntryWithoutAnOdometer() {
        let entries = [
            entry(1, day: 1, liters: 40, baht: 1400, odo: 10_000),
            entry(2, day: 5, liters: 10, baht: 350, odo: nil),
            entry(3, day: 10, liters: 30, baht: 1050, odo: 10_600),
        ]
        let intervals = FuelStats.intervals(entries)
        XCTAssertEqual(intervals.count, 1)
        XCTAssertEqual(intervals[0].distanceKm, 600)
        XCTAssertEqual(intervals[0].liters, 40)
        XCTAssertEqual(intervals[0].kmPerLiter, 15)
    }

    func testAnOdometerThatGoesBackIsLeftOut() {
        let entries = [
            entry(1, day: 1, liters: 40, baht: 1400, odo: 10_000),
            entry(2, day: 5, liters: 30, baht: 1000, odo: 9_000),
            entry(3, day: 9, liters: 40, baht: 1400, odo: 10_500),
        ]
        XCTAssertEqual(FuelStats.intervals(entries).map(\.distanceKm), [500])
    }

    func testSummaryNeedsTwoFillUpsForAnAverage() {
        let one = FuelStats.summarize([entry(1, day: 1, liters: 40, baht: 1400, odo: 10_000)])
        XCTAssertNil(one.averageKmPerLiter)
        XCTAssertEqual(one.fillUps, 1)
    }

    func testCsvHasTheAndroidColumns() {
        let csv = FuelStats.csv([entry(1, day: 1, liters: 40, baht: 1400, odo: 10_000)], ev: false,
                                timeZone: TimeZone(identifier: "UTC")!)
        let lines = csv.split(separator: "\n")
        XCTAssertEqual(lines[0], "date,liters,total_baht,price_per_liter,odometer_km,km_per_liter,station,fuel_type")
        XCTAssertEqual(lines[1], "1970-01-02 00:00,40.00,1400.00,35.00,10000,,\"\",\"\"")
    }

    func testFuelEntryReadsTheAndroidJson() throws {
        let json = #"[{"id":5,"time":1000,"liters":40.5,"baht":1500,"odo":null,"station":"PTT","type":"Gasohol 95"}]"#
        let entries = try JSONDecoder().decode([FuelEntry].self, from: Data(json.utf8))
        XCTAssertEqual(entries.first?.odometerKm, nil)
        XCTAssertEqual(entries.first?.fuelType, "Gasohol 95")
        XCTAssertEqual(entries.first?.totalBaht, 1500)
    }

    func testMaintenanceDueByDistanceAndDate() {
        let item = MaintenanceItem(id: 1, name: "Oil", intervalKm: 10_000, intervalMonths: 12, lastKm: 10_000, lastTimeMs: 1)
        let now = Date(epochMs: 100 * day)
        let ok = MaintenanceStats.status(item, odometerKm: 12_000, now: now, calendar: utc)
        XCTAssertEqual(ok.state, .ok)
        XCTAssertEqual(ok.kmLeft, 8_000)
        let soon = MaintenanceStats.status(item, odometerKm: 19_200, now: now, calendar: utc)
        XCTAssertEqual(soon.state, .soon)
        let over = MaintenanceStats.status(item, odometerKm: 20_100, now: now, calendar: utc)
        XCTAssertEqual(over.state, .overdue)
        XCTAssertEqual(over.kmLeft, -100)
        let late = MaintenanceStats.status(item, odometerKm: nil, now: Date(epochMs: 400 * day), calendar: utc)
        XCTAssertEqual(late.state, .overdue)
    }

    func testMostUrgentFirst() {
        let a = MaintenanceItem(id: 1, name: "A", intervalKm: 0, intervalMonths: 12, lastKm: nil, lastTimeMs: 1)
        let b = MaintenanceItem(id: 2, name: "B", intervalKm: 0, intervalMonths: 1, lastKm: nil, lastTimeMs: 1)
        let ordered = MaintenanceStats.ordered([a, b], odometerKm: nil, now: Date(epochMs: 40 * day), calendar: utc)
        XCTAssertEqual(ordered.map(\.item.name), ["B", "A"])
    }

    func testKmPerDayNeedsTwoWeeks() {
        let short = [entry(1, day: 1, liters: 40, baht: 1, odo: 1_000), entry(2, day: 10, liters: 40, baht: 1, odo: 1_500)]
        XCTAssertNil(MaintenanceStats.kmPerDay(short))
        let long = [entry(1, day: 1, liters: 40, baht: 1, odo: 1_000), entry(2, day: 21, liters: 40, baht: 1, odo: 2_000)]
        XCTAssertEqual(MaintenanceStats.kmPerDay(long), 50)
    }

    func testMonthlyCostsSplitFuelAndOther() {
        let fuel = [entry(1, day: 1, liters: 40, baht: 1400, odo: nil), entry(2, day: 40, liters: 40, baht: 1500, odo: nil)]
        let other = [Expense(id: 3, timeMs: 2 * day, label: "Wash", baht: 200)]
        let months = CostStats.monthly(fuel: fuel, expenses: other, calendar: utc)
        XCTAssertEqual(months.count, 2)
        XCTAssertEqual(months.last?.fuelBaht, 1400)
        XCTAssertEqual(months.last?.otherBaht, 200)
        XCTAssertEqual(months.first?.totalBaht, 1500)
    }

    func testEmergencyNumbers() {
        XCTAssertEqual(EmergencyNumber.dialable("191"), "191")
        XCTAssertEqual(EmergencyNumber.dialable("02-123-4567"), "021234567")
        XCTAssertEqual(EmergencyNumber.dialable("+66 81 234 5678"), "+66812345678")
        XCTAssertNil(EmergencyNumber.dialable("AB-1234567"))
        XCTAssertNil(EmergencyNumber.dialable("12"))
    }

    func testParkingSpotValidity() {
        XCTAssertTrue(ParkingSpot.isValid(lat: 13.75, lon: 100.5))
        XCTAssertFalse(ParkingSpot.isValid(lat: 0, lon: 0))
        XCTAssertFalse(ParkingSpot.isValid(lat: 91, lon: 0))
    }

    func testBackupRoundTripKeepsTripsAndReadsAndroidFiles() throws {
        let backup = VehicleBackup(
            ev: true,
            fuel: [entry(1, day: 1, liters: 30, baht: 200, odo: 500)],
            maintenance: [MaintenanceItem(id: 2, name: "Tyres", intervalKm: 10_000, intervalMonths: 0, lastKm: nil, lastTimeMs: 5)],
            expenses: [Expense(id: 3, timeMs: 7, label: "Toll", baht: 50)],
            tripsJSON: Data(#"[{"id":9}]"#.utf8)
        )
        let read = try XCTUnwrap(VehicleBackup.read(backup.write()))
        XCTAssertEqual(read.ev, true)
        XCTAssertEqual(read.fuel, backup.fuel)
        XCTAssertEqual(read.maintenance, backup.maintenance)
        XCTAssertEqual(read.expenses, backup.expenses)
        XCTAssertTrue(String(decoding: read.tripsJSON, as: UTF8.self).contains("9"))

        // An early Android backup: no expenses array.
        let android = #"{"app":"autobridge","version":1,"ev":false,"fuel":[],"maintenance":[],"trips":[]}"#
        XCTAssertEqual(VehicleBackup.read(Data(android.utf8))?.expenses, [])
        XCTAssertNil(VehicleBackup.read(Data(#"{"app":"other","version":1}"#.utf8)))
        XCTAssertNil(VehicleBackup.read(Data(#"{"app":"autobridge","version":2,"fuel":[],"maintenance":[],"trips":[]}"#.utf8)))
    }

    func testWeatherPlaceName() {
        let place = WeatherPlace(name: "Bangkok", admin1: "Bangkok", country: "Thailand", latitude: 13.75, longitude: 100.5)
        XCTAssertEqual(place.displayName, "Bangkok, Thailand")
    }
}
