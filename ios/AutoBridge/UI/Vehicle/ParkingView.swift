import CoreLocation
import MapKit
import SwiftUI
import UniformTypeIdentifiers

/// One location fix, asked for only when the driver taps "Mark here". The position is never
/// tracked otherwise.
@MainActor
final class OneShotLocation: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var finding = false
    private let manager = CLLocationManager()
    private var completion: ((CLLocation?) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func request(_ completion: @escaping (CLLocation?) -> Void) {
        self.completion = completion
        finding = true
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: finish(nil)
        default: manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard self.finding else { return }
            switch manager.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
            case .denied, .restricted: self.finish(nil)
            default: break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in self.finish(locations.last) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.finish(nil) }
    }

    private func finish(_ location: CLLocation?) {
        finding = false
        completion?(location)
        completion = nil
    }
}

/// The parking spot: mark it, add a note or a photo of the pillar, and get directions back in
/// Apple Maps. The port of the Android `ParkingActivity`.
struct ParkingView: View {
    @EnvironmentObject private var store: VehicleStore
    @Environment(\.openURL) private var openURL
    @StateObject private var location = OneShotLocation()
    @State private var note = ""
    @State private var noFix = false
    @State private var takingPhoto = false
    @State private var viewingPhoto = false

    var body: some View {
        List {
            if let spot = store.parking {
                Section {
                    Map(coordinateRegion: .constant(MKCoordinateRegion(
                        center: CLLocationCoordinate2D(latitude: spot.lat, longitude: spot.lon),
                        latitudinalMeters: 300, longitudinalMeters: 300
                    )), annotationItems: [spot.pin]) { pin in
                        MapMarker(coordinate: pin.coordinate, tint: .red)
                    }
                    .frame(height: 200)
                    .listRowInsets(EdgeInsets())
                    ValueRow(
                        title: spot.note.isEmpty ? NSLocalizedString("No note", comment: "Parking") : spot.note,
                        detail: Date(epochMs: spot.savedMs).formatted(date: .abbreviated, time: .shortened)
                    )
                    TextField(NSLocalizedString("Floor, pillar, anything to find the car", comment: "Parking"), text: $note)
                        .onSubmit { store.setParkingNote(note) }
                    Button {
                        if let url = spot.directionsURL { openURL(url) }
                    } label: {
                        Label(NSLocalizedString("Navigate", comment: "Parking"), systemImage: "car.fill")
                    }
                }
                Section {
                    if store.hasParkingPhoto {
                        Button(NSLocalizedString("View photo", comment: "Parking")) { viewingPhoto = true }
                        Button(NSLocalizedString("Take a new photo", comment: "Parking")) { takingPhoto = true }
                        Button(NSLocalizedString("Delete photo", comment: "Parking"), role: .destructive) { store.deleteParkingPhoto() }
                    } else {
                        Button(NSLocalizedString("Take a photo", comment: "Parking")) { takingPhoto = true }
                    }
                } footer: {
                    Text(NSLocalizedString(store.hasParkingPhoto
                        ? "Photo saved on this phone only. View it, replace it with a new one, or delete it."
                        : "A picture of the floor sign or the pillar number is the easiest way to find the car again. It stays on this phone.", comment: "Parking"))
                }
            } else {
                EmptyState(
                    title: NSLocalizedString("No parking spot saved", comment: "Parking"),
                    message: NSLocalizedString("Tap Mark here when you park. Add a note like the floor and pillar to find the car among others.", comment: "Parking")
                )
            }

            Section {
                Button(action: mark) {
                    Label(
                        location.finding
                            ? NSLocalizedString("Finding your location…", comment: "Parking")
                            : NSLocalizedString("Mark here", comment: "Parking"),
                        systemImage: "mappin.and.ellipse"
                    )
                }
                .disabled(location.finding)
                if store.parking != nil {
                    Button(NSLocalizedString("Clear", comment: "Parking"), role: .destructive) { store.clearParking() }
                }
                if noFix {
                    Text(NSLocalizedString("Could not get your location. Check that location is on and try again.", comment: "Parking"))
                        .foregroundStyle(AutoBridgeDesign.danger)
                }
            } footer: {
                Text(NSLocalizedString("Your location is read only when you tap Mark here. Only the spot you mark, your note and your photo are kept, on this phone; nothing is shared or sent.", comment: "Parking"))
            }
        }
        .vehicleListStyle(NSLocalizedString("Parking spot", comment: "Parking"))
        .onAppear { note = store.parking?.note ?? "" }
        .onDisappear { if store.parking != nil, note != store.parking?.note { store.setParkingNote(note) } }
        .sheet(isPresented: $takingPhoto) {
            CameraPicker { image in store.setParkingPhoto(image) }
                .ignoresSafeArea()
        }
        .sheet(isPresented: $viewingPhoto) {
            if let image = UIImage(contentsOfFile: VehicleStore.parkingPhotoURL.path) {
                Image(uiImage: image).resizable().scaledToFit().background(Color.black)
            }
        }
    }

    private func mark() {
        noFix = false
        location.request { fix in
            guard let fix else {
                noFix = true
                return
            }
            store.park(lat: fix.coordinate.latitude, lon: fix.coordinate.longitude, note: note)
        }
    }
}

private extension ParkingSpot {
    struct Pin: Identifiable {
        let coordinate: CLLocationCoordinate2D
        var id: String { "\(coordinate.latitude),\(coordinate.longitude)" }
    }

    var pin: Pin { Pin(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)) }
}

/// The camera, or the photo library where there is none (the simulator).
private struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// Back up and restore: the fuel or charging log, maintenance and costs as one JSON file, the same
/// file the Android app writes, so a log moves between the two apps.
struct BackupView: View {
    @EnvironmentObject private var store: VehicleStore
    @State private var importing = false
    @State private var pending: Data?
    @State private var message: String?

    var body: some View {
        List {
            Section {
                if let file = backupFile() {
                    ShareLink(item: file) {
                        ValueRow(
                            title: NSLocalizedString("Back up", comment: "Backup"),
                            detail: NSLocalizedString("Save the fuel or charging log and maintenance as a file", comment: "Backup")
                        )
                    }
                }
                Button { importing = true } label: {
                    ValueRow(
                        title: NSLocalizedString("Restore", comment: "Backup"),
                        detail: NSLocalizedString("Open a backup file; it replaces what is on this phone", comment: "Backup")
                    )
                }
            } footer: {
                Text(NSLocalizedString("Move your logs to another phone. The file is the same one the Android app writes.", comment: "Backup"))
            }
            if let message {
                Text(message).foregroundStyle(AutoBridgeDesign.secondaryText)
            }
        }
        .vehicleListStyle(NSLocalizedString("Backup and restore", comment: "Backup"))
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
            guard case let .success(url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url), VehicleBackup.read(data) != nil else {
                message = NSLocalizedString("That is not an AutoBridge backup.", comment: "Backup")
                return
            }
            pending = data
        }
        .alert(
            NSLocalizedString("Restore", comment: "Backup"),
            isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })
        ) {
            Button(NSLocalizedString("Replace", comment: "Backup"), role: .destructive) {
                if let data = pending, store.restore(data) {
                    message = NSLocalizedString("Restored.", comment: "Backup")
                }
            }
            Button(NSLocalizedString("Cancel", comment: "Action"), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("The fuel or charging log and maintenance list on this phone will be replaced by the file's.", comment: "Backup"))
        }
    }

    private func backupFile() -> URL? {
        guard let data = try? store.backup() else { return nil }
        let stamp = Date().formatted(.iso8601.year().month().day())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("autobridge-backup-\(stamp).json")
        return (try? data.write(to: url, options: .atomic)) != nil ? url : nil
    }
}
