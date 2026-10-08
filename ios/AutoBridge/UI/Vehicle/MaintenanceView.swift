import SwiftUI

/// Maintenance: what is due by distance and by date, the most urgent first. The port of the
/// Android `MaintenanceActivity`.
struct MaintenanceView: View {
    @EnvironmentObject private var store: VehicleStore
    @State private var adding = false
    @State private var acting: MaintenanceItem?

    var body: some View {
        let statuses = store.maintenanceStatus()
        List {
            Section {
                if let odo = store.currentOdometer {
                    Text(String(format: NSLocalizedString("Odometer: %@ km", comment: "Maintenance"), VehicleText.number(odo)))
                } else {
                    Text(NSLocalizedString("Odometer unknown: log a fill-up with it", comment: "Maintenance"))
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            } footer: {
                Text(NSLocalizedString(store.isEv ? "Due by distance and by date, for an EV" : "Due by distance and by date", comment: "Maintenance"))
            }

            Section {
                if statuses.isEmpty {
                    EmptyState(
                        title: NSLocalizedString("Nothing tracked yet", comment: "Maintenance"),
                        message: NSLocalizedString("Add the suggested items for your vehicle, or your own. Each counts from the odometer and the date it was last done.", comment: "Maintenance")
                    )
                }
                ForEach(statuses, id: \.item.id) { status in
                    Button { acting = status.item } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("\(VehicleText.badge(status.state)) \(status.item.name)")
                                .foregroundStyle(AutoBridgeDesign.primaryText)
                            Text(VehicleText.standing(status))
                                .font(.callout)
                                .foregroundStyle(status.state == .overdue ? AutoBridgeDesign.danger
                                    : status.state == .soon ? AutoBridgeDesign.signalSlow : AutoBridgeDesign.secondaryText)
                            Text([VehicleText.interval(status.item), VehicleText.due(status)].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(AutoBridgeDesign.secondaryText)
                        }
                    }
                }
                if !store.missingPresets.isEmpty {
                    Button(NSLocalizedString("Add suggested", comment: "Maintenance")) {
                        store.addPresets()
                        VehicleReminders.requestPermission()
                    }
                }
            }
        }
        .vehicleListStyle(NSLocalizedString("Maintenance", comment: "Maintenance"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text(NSLocalizedString("+ Item", comment: "Maintenance")))
            }
        }
        .sheet(isPresented: $adding) { MaintenanceEditor() }
        .sheet(item: $acting) { MaintenanceDoneSheet(item: $0) }
    }
}

private struct MaintenanceEditor: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var km = ""
    @State private var months = ""
    @State private var lastKm = ""
    @State private var hasDueDate = false
    @State private var dueDate = Date()
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                TextField(NSLocalizedString("Name", comment: "Maintenance"), text: $name)
                TextField(NSLocalizedString("Every (km), 0 for none", comment: "Maintenance"), text: $km)
                    .keyboardType(.numberPad)
                TextField(NSLocalizedString("Every (months), 0 for none", comment: "Maintenance"), text: $months)
                    .keyboardType(.numberPad)
                TextField(NSLocalizedString("Last done at (km)", comment: "Maintenance"), text: $lastKm)
                    .keyboardType(.numberPad)
                Toggle(NSLocalizedString("Due date", comment: "Maintenance"), isOn: $hasDueDate)
                if hasDueDate {
                    DatePicker(NSLocalizedString("Due date", comment: "Maintenance"), selection: $dueDate, displayedComponents: .date)
                }
                if invalid {
                    Text(NSLocalizedString("Enter a name and a distance or a number of months.", comment: "Maintenance"))
                        .foregroundStyle(AutoBridgeDesign.danger)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("+ Item", comment: "Maintenance"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Action")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("Save", comment: "Action"), action: save)
                }
            }
            .onAppear { lastKm = store.currentOdometer.map { String(Int64($0.rounded())) } ?? "" }
        }
        .preferredColorScheme(.dark)
    }

    private func save() {
        let everyKm = Int(km.trimmingCharacters(in: .whitespaces)) ?? 0
        let everyMonths = Int(months.trimmingCharacters(in: .whitespaces)) ?? 0
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty, everyKm > 0 || everyMonths > 0 else {
            invalid = true
            return
        }
        store.addMaintenance(
            name: name, intervalKm: max(0, everyKm), intervalMonths: max(0, everyMonths),
            lastKm: Double(lastKm.replacingOccurrences(of: ",", with: "")), dueDate: hasDueDate ? dueDate : nil
        )
        VehicleReminders.requestPermission()
        dismiss()
    }
}

/// Marks an item done at the current odometer, optionally with what it cost, or deletes it.
private struct MaintenanceDoneSheet: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.dismiss) private var dismiss
    let item: MaintenanceItem
    @State private var km = ""
    @State private var cost = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(NSLocalizedString("Odometer (km)", comment: "Fuel"), text: $km)
                        .keyboardType(.numberPad)
                    TextField(NSLocalizedString("Cost (baht), optional", comment: "Maintenance"), text: $cost)
                        .keyboardType(.decimalPad)
                } footer: {
                    Text(NSLocalizedString("Mark as done at this odometer, or delete the item.", comment: "Maintenance"))
                }
                Section {
                    Button(NSLocalizedString("Done today", comment: "Maintenance")) {
                        store.markDone(item.id, km: Double(km.replacingOccurrences(of: ",", with: "")),
                                       cost: Double(cost.replacingOccurrences(of: ",", with: "")))
                        dismiss()
                    }
                    Button(NSLocalizedString("Delete", comment: "Action"), role: .destructive) {
                        store.deleteMaintenance(item.id)
                        dismiss()
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Action")) { dismiss() }
                }
            }
            .onAppear { km = store.currentOdometer.map { String(Int64($0.rounded())) } ?? "" }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
    }
}

/// Car costs: fuel or charging plus everything else, by month. The port of `CostsActivity`.
struct CostsView: View {
    @EnvironmentObject private var store: VehicleStore
    @State private var adding = false

    var body: some View {
        let costs = CostStats.monthly(fuel: store.fuel, expenses: store.expenses)
        let now = Date()
        let current = CostStats.thisMonth(costs, now: now)
        List {
            Section {
                ValueRow(
                    title: String(format: NSLocalizedString("%@ baht", comment: "Costs"), VehicleText.money(current.totalBaht)),
                    detail: String(format: NSLocalizedString("So far in %@", comment: "Costs"), monthName(current.month))
                )
                Text(String(format: NSLocalizedString("Fuel or charging %@ · other %@ baht", comment: "Costs"),
                            VehicleText.money(current.fuelBaht), VehicleText.money(current.otherBaht)))
                    .font(.callout)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                if let average = CostStats.average(costs, now: now) {
                    Text(String(format: NSLocalizedString("Usual month: %@ baht", comment: "Costs"), VehicleText.money(average.totalBaht)))
                }
            }
            if costs.isEmpty {
                EmptyState(
                    title: NSLocalizedString("No costs yet", comment: "Costs"),
                    message: NSLocalizedString("Fuel and charging come from your fuel log. Add a service, a wash, tolls, parking or a tax here, or give a cost when you mark a maintenance item done.", comment: "Costs")
                )
            } else {
                Section(NSLocalizedString("By month", comment: "Costs")) {
                    ForEach(costs, id: \.month) { month in
                        ValueRow(
                            title: "\(monthName(month.month)): " + String(format: NSLocalizedString("%@ baht", comment: "Costs"), VehicleText.money(month.totalBaht)),
                            detail: String(format: NSLocalizedString("Fuel or charging %@ · other %@ baht", comment: "Costs"),
                                           VehicleText.money(month.fuelBaht), VehicleText.money(month.otherBaht))
                        )
                    }
                }
            }
            if !store.expenses.isEmpty {
                Section(NSLocalizedString("Other expenses", comment: "Costs")) {
                    ForEach(store.expenses) { expense in
                        ValueRow(
                            title: (expense.label.isEmpty ? NSLocalizedString("Expense", comment: "Costs") : expense.label)
                                + " · " + String(format: NSLocalizedString("%@ baht", comment: "Costs"), VehicleText.money(expense.baht)),
                            detail: Date(epochMs: expense.timeMs).formatted(date: .abbreviated, time: .omitted)
                        )
                        .swipeActions {
                            Button(NSLocalizedString("Delete", comment: "Action"), role: .destructive) { store.deleteExpense(expense.id) }
                        }
                    }
                }
            }
        }
        .vehicleListStyle(NSLocalizedString("Car costs", comment: "Costs"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text(NSLocalizedString("+ Expense", comment: "Costs")))
            }
        }
        .sheet(isPresented: $adding) { ExpenseEditor() }
    }

    private func monthName(_ month: YearMonth) -> String {
        let date = Calendar.current.date(from: DateComponents(year: month.year, month: month.month)) ?? Date()
        return date.formatted(.dateTime.month(.wide).year())
    }
}

private struct ExpenseEditor: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var baht = ""
    @State private var date = Date()
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                TextField(NSLocalizedString("What for (service, tolls, parking…)", comment: "Costs"), text: $label)
                TextField(NSLocalizedString("Amount (baht)", comment: "Costs"), text: $baht)
                    .keyboardType(.decimalPad)
                DatePicker(NSLocalizedString("Date", comment: "Fuel"), selection: $date, in: ...Date(), displayedComponents: .date)
                if invalid {
                    Text(NSLocalizedString("Enter an amount above zero.", comment: "Costs"))
                        .foregroundStyle(AutoBridgeDesign.danger)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("+ Expense", comment: "Costs"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Action")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("Save", comment: "Action")) {
                        if store.addExpense(label: label, baht: Double(baht.replacingOccurrences(of: ",", with: "")) ?? 0, date: date) {
                            dismiss()
                        } else {
                            invalid = true
                        }
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// The emergency card: numbers to ring (tap to call) and facts to quote. The port of
/// `EmergencyActivity`, with the same Thai public lines offered.
struct EmergencyView: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.openURL) private var openURL
    @State private var adding = false

    var body: some View {
        List {
            Section {
                if store.emergency.isEmpty {
                    EmptyState(
                        title: NSLocalizedString("Nothing on the card yet", comment: "Emergency"),
                        message: NSLocalizedString("Add your insurance hotline and policy number, roadside assistance and someone to call. Tap the emergency-lines button for the public numbers.", comment: "Emergency")
                    )
                }
                ForEach(store.emergency) { entry in
                    HStack {
                        ValueRow(title: entry.label, detail: entry.value)
                        Spacer()
                        if let number = entry.dialable, let url = URL(string: "tel:\(number)") {
                            Button { openURL(url) } label: {
                                Label(NSLocalizedString("Call", comment: "Emergency"), systemImage: "phone.fill")
                                    .labelStyle(.iconOnly)
                                    .frame(width: 40, height: 40)
                                    .background(AutoBridgeDesign.signalGood.opacity(0.2), in: Circle())
                                    .foregroundStyle(AutoBridgeDesign.signalGood)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .swipeActions {
                        Button(NSLocalizedString("Delete", comment: "Action"), role: .destructive) { store.deleteEmergency(entry.id) }
                    }
                }
                if !store.missingPublicLines.isEmpty {
                    Button(NSLocalizedString("Add emergency lines", comment: "Emergency")) { store.addPublicLines() }
                }
            } footer: {
                Text(NSLocalizedString("Numbers to ring and facts to quote", comment: "Emergency"))
            }
        }
        .vehicleListStyle(NSLocalizedString("Emergency card", comment: "Emergency"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text(NSLocalizedString("+ Add", comment: "Emergency")))
            }
        }
        .sheet(isPresented: $adding) { EmergencyEditor() }
    }
}

private struct EmergencyEditor: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.dismiss) private var dismiss
    @State private var label = ""
    @State private var value = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                TextField(NSLocalizedString("Label (e.g. Insurance hotline)", comment: "Emergency"), text: $label)
                TextField(NSLocalizedString("Number or text", comment: "Emergency"), text: $value)
                if invalid {
                    Text(NSLocalizedString("Enter a label and a number or text.", comment: "Emergency"))
                        .foregroundStyle(AutoBridgeDesign.danger)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("+ Add", comment: "Emergency"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(NSLocalizedString("Cancel", comment: "Action")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(NSLocalizedString("Save", comment: "Action")) {
                        guard !label.trimmingCharacters(in: .whitespaces).isEmpty,
                              !value.trimmingCharacters(in: .whitespaces).isEmpty else {
                            invalid = true
                            return
                        }
                        store.addEmergency(label: label, value: value)
                        dismiss()
                    }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}
