import AVKit
import SwiftUI

/// Video on a car screen over AirPlay: an aftermarket head unit or a CarPlay box that is an AirPlay
/// receiver. Off by default, like the Android advanced options that lift a protection: turning it
/// on is the driver's own choice, after a warning, and it can be turned off from the same place.
///
/// Off, a video's sound still goes to an AirPlay speaker but its picture stays on the phone. On,
/// the picture goes too and keeps playing on that screen while the phone is locked. What the
/// receiving screen does while the car moves is up to that screen; iOS does not gate AirPlay on
/// motion the way it gates video inside CarPlay.
enum CarScreenVideo {
    private static let key = "carScreen.airplayVideo"

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: key) }
        set {
            UserDefaults.standard.set(newValue, forKey: key)
            Task { @MainActor in AutoBridgeStores.shared.playback.applyExternalPlayback() }
        }
    }
}

/// The system AirPlay picker as a toolbar button. It lists video receivers first, so a car screen
/// is at the top of the list rather than under the speakers.
struct AirPlayButton: UIViewRepresentable {
    var tint: UIColor = UIColor(AutoBridgeDesign.primaryText)

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = true
        picker.tintColor = tint
        picker.activeTintColor = UIColor(AutoBridgeDesign.accent)
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// The Settings row for the option, with the warning shown before it turns on.
struct CarScreenVideoSection: View {
    @State private var enabled = CarScreenVideo.enabled
    @State private var confirming = false

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { enabled },
                set: { wanted in
                    if wanted {
                        confirming = true
                    } else {
                        enabled = false
                        CarScreenVideo.enabled = false
                    }
                }
            )) {
                ValueRow(
                    title: NSLocalizedString("Video on a car screen (AirPlay)", comment: "Advanced"),
                    detail: NSLocalizedString("Sends the picture, not only the sound, to an AirPlay screen in the car, and keeps it playing while the phone is locked.", comment: "Advanced")
                )
            }
        } header: {
            Text(NSLocalizedString("Advanced (off by default)", comment: "Advanced"))
        } footer: {
            Text(NSLocalizedString("Turning this on lifts a protection; you take the risk and the responsibility. Watching video the driver can see while the car moves is dangerous and against Thai traffic law.", comment: "Advanced"))
        }
        .alert(
            NSLocalizedString("Video on a car screen (AirPlay)", comment: "Advanced"),
            isPresented: $confirming
        ) {
            Button(NSLocalizedString("Turn on", comment: "Advanced"), role: .destructive) {
                enabled = true
                CarScreenVideo.enabled = true
            }
            Button(NSLocalizedString("Cancel", comment: "Action"), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("The car screen will show video whether the car is parked or moving; nothing here checks. Do not watch while driving. Turn it on only for passengers, or for when you are parked.", comment: "Advanced"))
        }
    }
}
