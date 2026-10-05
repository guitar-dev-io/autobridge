import Foundation
import Security

/// Keychain-backed string storage for the two files that hold credentials.
///
/// This is the iOS half of the Android credential policy (`SecretPrefs`). An Xtream stream URL
/// carries the account's username and password in its own path, so the source list and the playback
/// history are both secrets, not ordinary preferences, and neither belongs in `UserDefaults` — which
/// is a plist in the container, readable from a file-level backup.
///
/// Items are stored with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, which gives the same
/// three properties the Android store documents:
///
///  - `ThisDeviceOnly` keeps the item out of iCloud Keychain and out of an encrypted backup
///    restored onto another handset, so stored credentials never reach another device;
///  - deleting the app removes its keychain items with it, so they are unrecoverable afterwards;
///  - `AfterFirstUnlock` still lets the CarPlay scene read a source list when the phone is locked
///    in a pocket, which playback in a car needs.
enum SecretStore {
    /// The value for `account`, or "" when nothing is stored (or the read fails).
    static func read(service: String, account: String) -> String {
        var query = baseQuery(service: service, account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Stores `value` for `account`, replacing whatever was there. A blank value deletes the item.
    static func write(service: String, account: String, value: String) {
        guard !value.isEmpty else {
            delete(service: service, account: account)
            return
        }
        let data = Data(value.utf8)
        let query = baseQuery(service: service, account: account)
        let attributes: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    static func delete(service: String, account: String) {
        SecItemDelete(baseQuery(service: service, account: account) as CFDictionary)
    }

    private static func baseQuery(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
