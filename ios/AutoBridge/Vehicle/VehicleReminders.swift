import Foundation
import UserNotifications

/// The two nudges the Android app gives, as local notifications.
///
/// **Maintenance:** when the app comes to the front, if anything is due or close to it, at most once
/// in 20 hours, the moments the driver is about to drive, as the Android reminder does.
/// **Break:** every N hours while CarPlay is connected (off by default). The Android app counts
/// the time the car is connected; here the CarPlay scene's connect and disconnect start and stop
/// a repeating notification. Neither uses location.
@MainActor
enum VehicleReminders {
    private static let lastMaintenanceKey = "vehicle.maintenanceReminder.last"
    private static let breakHoursKey = "vehicle.breakReminder.hours"
    private static let maintenanceId = "autobridge.maintenance"
    private static let breakId = "autobridge.break"
    private static let minGap: TimeInterval = 20 * 60 * 60

    /// The choices offered for the break reminder; 0 is off.
    static let breakChoices = [0, 1, 2, 3, 4]

    static var breakHours: Int {
        get { UserDefaults.standard.integer(forKey: breakHoursKey) }
        set { UserDefaults.standard.set(breakChoices.contains(newValue) ? newValue : 0, forKey: breakHoursKey) }
    }

    /// Asks once, when the driver first sets something up that would notify them.
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func checkMaintenance(_ store: VehicleStore, now: Date = Date()) {
        guard !store.maintenance.isEmpty else { return }
        let defaults = UserDefaults.standard
        let last = defaults.double(forKey: lastMaintenanceKey)
        guard now.timeIntervalSince1970 - last >= minGap else { return }
        let due = store.maintenanceStatus(now: now).filter { $0.state != .ok }
        guard !due.isEmpty else { return }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else { return }
            Task { @MainActor in
                let content = UNMutableNotificationContent()
                content.title = String(
                    format: NSLocalizedString("%d maintenance item(s) need attention", comment: "Reminder"), due.count
                )
                content.body = due.map { "\(VehicleText.badge($0.state)) \($0.item.name): \(VehicleText.standing($0))" }
                    .joined(separator: "\n")
                UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: maintenanceId, content: content, trigger: nil)
                )
                defaults.set(now.timeIntervalSince1970, forKey: lastMaintenanceKey)
            }
        }
    }

    /// Called when CarPlay connects: the first reminder comes after `breakHours`, then every
    /// `breakHours` after that, until `carDisconnected`.
    static func carConnected() {
        let hours = breakHours
        guard hours > 0 else { return }
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("Break reminder", comment: "Reminder")
        content.body = NSLocalizedString("You have been driving a while. Time for a break.", comment: "Reminder")
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(hours) * 3600, repeats: true)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: breakId, content: content, trigger: trigger))
    }

    static func carDisconnected() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [breakId])
    }
}

/// The wording shared by the maintenance list and its reminder.
enum VehicleText {
    static func badge(_ state: DueState) -> String {
        switch state {
        case .overdue: return "🔴"
        case .soon: return "🟡"
        case .ok: return "🟢"
        }
    }

    static func number(_ value: Double, digits: Int = 0) -> String {
        let format = NumberFormatter()
        format.numberStyle = .decimal
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.string(from: NSNumber(value: value)) ?? String(value)
    }

    static func money(_ baht: Double) -> String { number(baht, digits: baht.rounded() == baht ? 0 : 2) }

    /// "In 3,200 km · In 40 days": where an item stands, each interval that applies.
    static func standing(_ status: MaintenanceStatus) -> String {
        var parts: [String] = []
        if let km = status.kmLeft {
            let whole = number(abs(km))
            parts.append(km < 0
                ? String(format: NSLocalizedString("Overdue by %@ km", comment: "Maintenance"), whole)
                : String(format: NSLocalizedString("In %@ km", comment: "Maintenance"), whole))
        }
        if let days = status.daysLeft {
            parts.append(days < 0
                ? String(format: NSLocalizedString("Overdue by %d days", comment: "Maintenance"), -days)
                : String(format: NSLocalizedString("In %d days", comment: "Maintenance"), days))
        }
        if parts.isEmpty {
            return NSLocalizedString("Odometer unknown: log a fill-up with it", comment: "Maintenance")
        }
        return parts.joined(separator: " · ")
    }

    /// "Due 12 Dec 2026", or an estimate when the distance at the usual pace sets the day.
    static func due(_ status: MaintenanceStatus) -> String? {
        guard let due = status.due else { return nil }
        let date = due.formatted(date: .abbreviated, time: .omitted)
        let estimated = MaintenanceStats.dateDue(status.item) != due
        return String(
            format: estimated
                ? NSLocalizedString("Due around %@ at your usual driving", comment: "Maintenance")
                : NSLocalizedString("Due %@", comment: "Maintenance"),
            date
        )
    }

    /// "Every 10,000 km · every 12 months".
    static func interval(_ item: MaintenanceItem) -> String {
        var parts: [String] = []
        if item.intervalKm > 0 {
            parts.append(String(format: NSLocalizedString("Every %@ km", comment: "Maintenance"), number(Double(item.intervalKm))))
        }
        if item.intervalMonths > 0 {
            parts.append(String(format: NSLocalizedString("Every %d months", comment: "Maintenance"), item.intervalMonths))
        }
        return parts.joined(separator: " · ")
    }
}
