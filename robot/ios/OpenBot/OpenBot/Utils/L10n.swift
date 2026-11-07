//
// Created for in-app language switching.
//

import Foundation

enum L10n {
    static func string(_ key: String) -> String {
        let bundle = LocalizationManager.bundle
        let value = bundle.localizedString(forKey: key, value: key, table: nil)
        if value != key {
            return value
        }
        // Missing in the active language: fall back to the English table (EN-only keys,
        // matching Android's values/ resource fallback).
        if let path = Bundle.main.path(forResource: "en", ofType: "lproj"),
           let enBundle = Bundle(path: path) {
            return enBundle.localizedString(forKey: key, value: key, table: nil)
        }
        return value
    }
}
