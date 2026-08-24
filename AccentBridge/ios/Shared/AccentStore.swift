import Foundation

enum AccentStore {
    static let appGroupID = "group.com.accentbridge.shared"
    private static let key = "active_accent"
    private static let fallbackAccentID = "scottish"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroupID) ?? .standard
    }

    static var activeAccentID: String {
        get { defaults.string(forKey: key) ?? fallbackAccentID }
        set { defaults.set(newValue, forKey: key) }
    }
}
