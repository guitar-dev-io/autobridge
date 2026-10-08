import Foundation

/// Which build this is: the full one for development, or the one sent to App Review.
///
/// `AUTOBRIDGE_STORE` is set for the Release configuration in `project.yml`, so an Archive (what
/// TestFlight and the App Store receive) is the store build, while running from Xcode (Debug) keeps
/// every feature. The store build leaves out what App Review is likely to reject; see
/// `DISTRIBUTION.md`, section 3.
enum BuildFlavor {
    #if AUTOBRIDGE_STORE
    static let isStore = true
    #else
    static let isStore = false
    #endif
}
