import SwiftUI

extension View {
    /// The grouped-list look the utility screens share with Settings.
    func vehicleListStyle(_ title: String) -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .tint(AutoBridgeDesign.accent)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// A caption under a value, the second line of a utility row.
struct ValueRow: View {
    let title: String
    var detail: String?
    var tint: Color = AutoBridgeDesign.primaryText

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(tint)
            if let detail, !detail.isEmpty {
                Text(detail).font(.caption).foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
    }
}

/// The fuel or charging log: full tank every time (or the same charge level), average km/L, the
/// last tank, baht per km and this month's spending. The port of the Android `FuelLogActivity`.
struct FuelLogView: View {
    @EnvironmentObject private var store: VehicleStore
    @State private var editing: FuelEntry?
    @State private var adding = false
    @State private var pendingDelete: FuelEntry?

    var body: some View {
        let ev = store.isEv
        let summary = FuelStats.summarize(store.fuel)
        List {
            Section {
                Picker("", selection: $store.isEv) {
                    Text(NSLocalizedString("⛽ Petrol / diesel", comment: "Fuel")).tag(false)
                    Text(NSLocalizedString("⚡ EV", comment: "Fuel")).tag(true)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text(ev
                    ? NSLocalizedString("Charge to the same level each time", comment: "Fuel")
                    : NSLocalizedString("Full tank every time", comment: "Fuel"))
            }

            Section(NSLocalizedString("Average consumption", comment: "Fuel")) {
                if let average = summary.averageKmPerLiter {
                    Text(String(format: NSLocalizedString(ev ? "%@ km/kWh" : "%@ km/L", comment: "Fuel"),
                                VehicleText.number(average, digits: 2)))
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(AutoBridgeDesign.accentRadio)
                } else {
                    Text(NSLocalizedString(ev ? "Two charges needed" : "Two fill-ups needed", comment: "Fuel"))
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
                if let last = summary.lastKmPerLiter {
                    Text(String(format: NSLocalizedString(ev ? "Last charge: %@ km/kWh" : "Last tank: %@ km/L", comment: "Fuel"),
                                VehicleText.number(last, digits: 2)))
                }
                if let perKm = summary.bahtPerKm {
                    Text(String(format: NSLocalizedString("%@ baht/km", comment: "Fuel"), VehicleText.number(perKm, digits: 2)))
                }
                Text(String(format: NSLocalizedString("This month: %@ baht", comment: "Fuel"), VehicleText.money(summary.thisMonthBaht)))
                if summary.fillUps > 0 {
                    Text(String(
                        format: NSLocalizedString(ev ? "%d charges · %@ kWh · %@ baht in total" : "%d fill-ups · %@ L · %@ baht in total", comment: "Fuel"),
                        summary.fillUps, VehicleText.number(summary.totalLiters, digits: 1), VehicleText.money(summary.totalBaht)
                    ))
                    .font(.caption)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }

            Section(NSLocalizedString(ev ? "Charges" : "Fill-ups", comment: "Fuel")) {
                if store.fuel.isEmpty {
                    EmptyState(
                        title: NSLocalizedString(ev ? "No charges yet" : "No fill-ups yet", comment: "Fuel"),
                        message: NSLocalizedString(ev
                            ? "Log each charge, always to the same level (say 80 percent). From the second one on, the average km/kWh appears here."
                            : "Log every fill-up with a full tank. From the second one on, the average km/L appears here.", comment: "Fuel")
                    )
                }
                ForEach(store.fuel) { entry in
                    Button { editing = entry } label: { row(entry, ev: ev) }
                        .swipeActions {
                            Button(NSLocalizedString("Delete", comment: "Action"), role: .destructive) { pendingDelete = entry }
                        }
                }
            }
        }
        .vehicleListStyle(NSLocalizedString(ev ? "Charging log" : "Fuel log", comment: "Fuel"))
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !store.fuel.isEmpty {
                    ShareLink(item: csvFile(ev: ev)) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel(Text(NSLocalizedString("Export CSV", comment: "Fuel")))
                }
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text(NSLocalizedString(ev ? "+ Charge" : "+ Fill-up", comment: "Fuel")))
            }
        }
        .sheet(isPresented: $adding) { FuelEditor(entry: nil) }
        .sheet(item: $editing) { FuelEditor(entry: $0) }
        .alert(
            NSLocalizedString(ev ? "Delete this charge?" : "Delete this fill-up?", comment: "Fuel"),
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
        ) {
            Button(NSLocalizedString("Delete", comment: "Action"), role: .destructive) {
                if let entry = pendingDelete { store.deleteFuel(entry.id) }
            }
            Button(NSLocalizedString("Cancel", comment: "Action"), role: .cancel) {}
        }
    }

    private func row(_ entry: FuelEntry, ev: Bool) -> some View {
        let date = Date(epochMs: entry.timeMs).formatted(date: .abbreviated, time: .omitted)
        var parts = [date]
        if let odo = entry.odometerKm {
            parts.append(String(format: NSLocalizedString("%@ km", comment: "Fuel"), VehicleText.number(odo)))
        }
        if let kmL = FuelStats.kmPerLiter(of: entry, in: store.fuel) {
            parts.append(String(format: NSLocalizedString(ev ? "%@ km/kWh" : "%@ km/L", comment: "Fuel"), VehicleText.number(kmL, digits: 2)))
        }
        if !entry.fuelType.isEmpty { parts.append(NSLocalizedString(entry.fuelType, comment: "Fuel grade")) }
        if !entry.station.isEmpty { parts.append(entry.station) }
        return ValueRow(
            title: String(format: NSLocalizedString(ev ? "%@ kWh · %@ baht" : "%@ L · %@ baht", comment: "Fuel"),
                          VehicleText.number(entry.liters, digits: 2), VehicleText.money(entry.totalBaht)),
            detail: parts.joined(separator: " · ")
        )
    }

    /// The CSV in a temporary file, so the share sheet offers it as a file a spreadsheet opens.
    private func csvFile(ev: Bool) -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(ev ? "autobridge-charging.csv" : "autobridge-fuel.csv")
        try? FuelStats.csv(store.fuel, ev: ev).write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}

/// Adds or edits one fill-up or charge.
private struct FuelEditor: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.dismiss) private var dismiss
    let entry: FuelEntry?

    @State private var liters = ""
    @State private var baht = ""
    @State private var odometer = ""
    @State private var station = ""
    @State private var fuelType = ""
    @State private var date = Date()
    @State private var invalid = false

    var body: some View {
        let ev = store.isEv
        NavigationStack {
            Form {
                Section {
                    TextField(NSLocalizedString(ev ? "Energy (kWh)" : "Litres", comment: "Fuel"), text: $liters)
                        .keyboardType(.decimalPad)
                    TextField(NSLocalizedString("Total (baht)", comment: "Fuel"), text: $baht)
                        .keyboardType(.decimalPad)
                    TextField(NSLocalizedString("Odometer (km)", comment: "Fuel"), text: $odometer)
                        .keyboardType(.numberPad)
                    DatePicker(NSLocalizedString("Date", comment: "Fuel"), selection: $date, in: ...Date())
                } footer: {
                    if let last = store.lastOdometer, entry == nil {
                        Text(String(format: NSLocalizedString("Last odometer: %@ km", comment: "Fuel"), VehicleText.number(last)))
                    }
                }
                if !ev {
                    Section(NSLocalizedString("Fuel grade", comment: "Fuel")) {
                        Picker(NSLocalizedString("Fuel grade", comment: "Fuel"), selection: $fuelType) {
                            Text(NSLocalizedString("choose", comment: "Fuel")).tag("")
                            ForEach(gradeChoices, id: \.self) { grade in
                                Text(NSLocalizedString(grade, comment: "Fuel grade")).tag(grade)
                            }
                        }
                    }
                }
                Section {
                    TextField(NSLocalizedString("Station (optional)", comment: "Fuel"), text: $station)
                }
                if invalid {
                    Text(NSLocalizedString(ev ? "Enter the kWh and the total." : "Enter the litres and the total.", comment: "Fuel"))
                        .foregroundStyle(AutoBridgeDesign.danger)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(NSLocalizedString(
                entry == nil ? (ev ? "Charge" : "Full-tank fill-up") : (ev ? "Edit charge" : "Edit fill-up"), comment: "Fuel"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Action")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("Save", comment: "Action"), action: save)
                }
            }
            .onAppear(perform: fill)
        }
        .preferredColorScheme(.dark)
    }

    /// The usual grades plus whatever an older entry (or the Android app) wrote.
    private var gradeChoices: [String] {
        var choices = FuelStats.fuelTypes
        if !fuelType.isEmpty, !choices.contains(fuelType) { choices.append(fuelType) }
        return choices
    }

    private func fill() {
        guard let entry else {
            fuelType = store.lastFuelType
            return
        }
        liters = VehicleText.number(entry.liters, digits: 2).replacingOccurrences(of: ",", with: "")
        baht = String(entry.totalBaht)
        odometer = entry.odometerKm.map { String(Int64($0.rounded())) } ?? ""
        station = entry.station
        fuelType = entry.fuelType
        date = Date(epochMs: entry.timeMs)
    }

    private func parse(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces))
    }

    private func save() {
        guard let amount = parse(liters), amount > 0, let total = parse(baht), total > 0 else {
            invalid = true
            return
        }
        let odo = parse(odometer).flatMap { $0 > 0 ? $0 : nil }
        let grade = store.isEv ? "" : fuelType
        if var edited = entry {
            edited.liters = amount
            edited.totalBaht = total
            edited.odometerKm = odo
            edited.station = station
            edited.fuelType = grade
            edited.timeMs = date.epochMs
            store.updateFuel(edited)
        } else {
            store.addFuel(liters: amount, baht: total, odometerKm: odo, station: station, fuelType: grade, date: date)
        }
        dismiss()
    }
}
