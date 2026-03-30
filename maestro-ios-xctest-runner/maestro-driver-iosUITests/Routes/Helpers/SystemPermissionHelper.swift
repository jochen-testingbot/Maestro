import XCTest

final class SystemPermissionHelper {

    // MARK: - Permission dialog definitions

    /// Defines how to detect and handle a specific iOS system permission dialog.
    struct PermissionDialog {
        let key: String            // Matches the key in the permissions map (e.g. "location")
        let labelPattern: String   // Case-insensitive substring to match in the alert/sheet label
        /// Maps a PermissionValue to the button index to tap. If the value is not in the map, no action is taken.
        let buttonMap: [PermissionValue: Int]
    }

    /// All known iOS system permission dialogs.
    /// Button indices are 0-based and correspond to the order buttons appear in the dialog.
    static let permissionDialogs: [PermissionDialog] = [
        // Notifications: [Don't Allow (0), Allow (1)]
        PermissionDialog(
            key: "notifications",
            labelPattern: "Would Like to Send You Notifications",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Location: [Allow Once (0), Allow While Using App (1), Don't Allow (2)]
        PermissionDialog(
            key: "location",
            labelPattern: "to use your location",
            buttonMap: [.allow: 1, .always: 1, .inuse: 1, .deny: 2, .never: 2]
        ),
        // Bluetooth: [Don't Allow (0), Allow (1)]
        // Dialog text varies: "Would Like to Use Bluetooth" or "to find Bluetooth devices"
        PermissionDialog(
            key: "bluetooth",
            labelPattern: "Bluetooth",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Camera: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "camera",
            labelPattern: "Would Like to Access the Camera",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Microphone: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "microphone",
            labelPattern: "Would Like to Access the Microphone",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Photos: [Don't Allow (0), Allow Limited Access (1), Allow Full Access (2)]
        PermissionDialog(
            key: "photos",
            labelPattern: "Would Like to Access Your Photos",
            buttonMap: [.allow: 2, .limited: 1, .deny: 0]
        ),
        // Contacts: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "contacts",
            labelPattern: "Would Like to Access Your Contacts",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Calendar: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "calendar",
            labelPattern: "Would Like to Access Your Calendar",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Reminders: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "reminders",
            labelPattern: "Would Like to Access Your Reminders",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Speech Recognition: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "speech",
            labelPattern: "Would Like to Access Speech Recognition",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Motion & Fitness: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "motion",
            labelPattern: "Would Like to Access Your Motion",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // HomeKit: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "homekit",
            labelPattern: "Would Like to Access Your Home Data",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Media Library: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "medialibrary",
            labelPattern: "Would Like to Access Apple Music",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Siri: [Don't Allow (0), OK (1)]
        PermissionDialog(
            key: "siri",
            labelPattern: "Would Like to Access Siri",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // User Tracking (App Tracking Transparency): [Ask App Not to Track (0), Allow (1)]
        PermissionDialog(
            key: "usertracking",
            labelPattern: "Would Like Permission to Track",
            buttonMap: [.allow: 1, .deny: 0]
        ),
        // Local Network: [Don't Allow (0), Allow (1)]
        // Dialog: "Allow X to find devices on local networks?"
        PermissionDialog(
            key: "localnetwork",
            labelPattern: "local network",
            buttonMap: [.allow: 1, .deny: 0]
        ),
    ]

    // MARK: - Helpers

    /// Permissions that are auto-allowed by default unless explicitly configured otherwise.
    /// These dialogs block the entire UI and are rarely intentionally denied in test flows.
    private static let autoAllowPermissions = ["bluetooth", "localnetwork"]

    /// Returns the stored permissions with auto-allow defaults applied.
    private static func effectivePermissions() -> [String: PermissionValue]? {
        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              var permissions = try? JSONDecoder().decode([String: PermissionValue].self, from: data) else {
            // Even with no permissions configured, still handle auto-allow permissions
            var defaults: [String: PermissionValue] = [:]
            for key in autoAllowPermissions { defaults[key] = .allow }
            return defaults
        }
        // Apply auto-allow defaults for permissions not explicitly set
        for key in autoAllowPermissions {
            if permissions[key] == nil {
                permissions[key] = .allow
            }
        }
        return permissions.isEmpty ? nil : permissions
    }

    // MARK: - Interruption monitor handler

    /// Handles a system interruption element (alert or sheet) delivered by addUIInterruptionMonitor.
    /// Returns true if the dialog was handled, false otherwise.
    static func handleInterruption(_ element: XCUIElement) -> Bool {
        guard let permissions = effectivePermissions() else {
            NSLog("[SystemPermissionHelper] Interruption received but no permissions configured")
            return false
        }

        // Collect all text from the interrupting element for matching
        let elementLabel = element.label
        NSLog("[SystemPermissionHelper] Interruption received — label: '\(elementLabel)'")

        // Log all static texts for debugging
        let texts = element.staticTexts.allElementsBoundByIndex
        let textLabels = texts.prefix(10).map { $0.label }
        NSLog("[SystemPermissionHelper] Interruption static texts: \(textLabels)")

        // Log all buttons for debugging
        let buttons = element.buttons.allElementsBoundByIndex
        let buttonLabels = buttons.enumerated().map { "[\($0.offset)]: '\($0.element.label)'" }
        NSLog("[SystemPermissionHelper] Interruption buttons: \(buttonLabels)")

        // Build a combined string from all static texts for more reliable matching
        let allText = textLabels.joined(separator: " ")

        for dialog in permissionDialogs {
            guard let permissionValue = permissions[dialog.key] else {
                continue
            }
            if permissionValue == .unset || permissionValue == .unknown {
                continue
            }

            // Check if the dialog label or any static text contains the pattern
            let pattern = dialog.labelPattern.lowercased()
            let matches = elementLabel.lowercased().contains(pattern) || allText.lowercased().contains(pattern)

            if matches {
                NSLog("[SystemPermissionHelper] Matched '\(dialog.key)' permission dialog (pattern: '\(dialog.labelPattern)')")

                guard let buttonIndex = dialog.buttonMap[permissionValue] else {
                    NSLog("[SystemPermissionHelper] No button mapping for '\(dialog.key)' with value '\(permissionValue.rawValue)', skipping")
                    continue
                }

                let button = element.buttons.element(boundBy: buttonIndex)
                if button.exists {
                    let buttonLabel = button.label
                    NSLog("[SystemPermissionHelper] Tapping button at index \(buttonIndex) ('\(buttonLabel)') for '\(dialog.key)' permission with value '\(permissionValue.rawValue)'")
                    button.tap()
                    NSLog("[SystemPermissionHelper] Successfully tapped '\(buttonLabel)' on '\(dialog.key)' permission dialog")
                    return true
                } else {
                    NSLog("[SystemPermissionHelper] Button at index \(buttonIndex) not found for '\(dialog.key)'. Available buttons (\(buttons.count)): \(buttonLabels.joined(separator: ", "))")
                }
            }
        }

        NSLog("[SystemPermissionHelper] No matching permission dialog found in interruption")
        return false
    }

    // MARK: - ViewHierarchy hook (called from ViewHierarchyHandler as additional safety net)

    /// Called from ViewHierarchyHandler when Springboard is the foreground app.
    /// Acts as a fallback in case the interruption monitor did not fire.
    ///
    /// Queries the alert/sheet ONCE and matches its label against all patterns — avoids
    /// running N predicate queries per element type, which causes XCUITest errors to accumulate
    /// and crash the test with "Interrupted by waiter."
    static func handleSystemPermissionAlertIfNeeded(foregroundApp: XCUIApplication) {
        guard foregroundApp.bundleID == "com.apple.springboard" else {
            return
        }

        guard let permissions = effectivePermissions() else {
            return
        }

        // Check the first alert once, then match its label against all dialog patterns
        let alert = foregroundApp.alerts.element
        if alert.exists {
            let alertLabel = alert.label.lowercased()
            NSLog("[SystemPermissionHelper] [Fallback] Found alert with label: '\(alert.label)'")

            for dialog in permissionDialogs {
                guard let permissionValue = permissions[dialog.key],
                      permissionValue != .unset && permissionValue != .unknown else {
                    continue
                }
                if alertLabel.contains(dialog.labelPattern.lowercased()) {
                    NSLog("[SystemPermissionHelper] [Fallback] Matched '\(dialog.key)' in alert")
                    tapButton(in: alert, dialog: dialog, permissionValue: permissionValue)
                    return
                }
            }
            NSLog("[SystemPermissionHelper] [Fallback] Alert did not match any known permission dialog")
        }

        // Check the first sheet once
        let sheet = foregroundApp.sheets.element
        if sheet.exists {
            let sheetLabel = sheet.label.lowercased()
            NSLog("[SystemPermissionHelper] [Fallback] Found sheet with label: '\(sheet.label)'")

            for dialog in permissionDialogs {
                guard let permissionValue = permissions[dialog.key],
                      permissionValue != .unset && permissionValue != .unknown else {
                    continue
                }
                if sheetLabel.contains(dialog.labelPattern.lowercased()) {
                    NSLog("[SystemPermissionHelper] [Fallback] Matched '\(dialog.key)' in sheet")
                    tapButton(in: sheet, dialog: dialog, permissionValue: permissionValue)
                    return
                }
            }
            NSLog("[SystemPermissionHelper] [Fallback] Sheet did not match any known permission dialog")
        }

        // For custom system dialogs (e.g. Bluetooth "find devices", Local Network)
        // that are neither alerts nor sheets: check static texts with targeted predicates.
        // Only check auto-allow permissions here to minimize queries.
        for key in autoAllowPermissions {
            guard let dialog = permissionDialogs.first(where: { $0.key == key }),
                  let permissionValue = permissions[key],
                  permissionValue != .unset && permissionValue != .unknown else {
                continue
            }
            let predicate = NSPredicate(format: "label CONTAINS[c] %@", dialog.labelPattern)
            let matchingText = foregroundApp.staticTexts.matching(predicate).element
            if matchingText.exists {
                NSLog("[SystemPermissionHelper] [Fallback] Detected '\(dialog.key)' via Springboard static text (custom dialog)")
                let targetLabel: String
                switch permissionValue {
                case .deny, .never:
                    targetLabel = "Don\u{2019}t Allow"
                default:
                    targetLabel = "Allow"
                }
                let button = foregroundApp.buttons[targetLabel]
                if button.exists {
                    NSLog("[SystemPermissionHelper] [Fallback] Tapping '\(targetLabel)' for '\(dialog.key)'")
                    button.tap()
                    NSLog("[SystemPermissionHelper] [Fallback] Successfully tapped '\(targetLabel)' on '\(dialog.key)'")
                } else {
                    NSLog("[SystemPermissionHelper] [Fallback] Button '\(targetLabel)' not found on Springboard for '\(dialog.key)'")
                }
                return
            }
        }
    }

    /// Tap a button by index within a dialog element (alert or sheet).
    private static func tapButton(in dialogElement: XCUIElement, dialog: PermissionDialog, permissionValue: PermissionValue) {
        guard let buttonIndex = dialog.buttonMap[permissionValue] else {
            NSLog("[SystemPermissionHelper] [Fallback] No button mapping for '\(dialog.key)' with value '\(permissionValue.rawValue)', skipping")
            return
        }

        let button = dialogElement.buttons.element(boundBy: buttonIndex)
        if button.exists {
            let buttonLabel = button.label
            NSLog("[SystemPermissionHelper] [Fallback] Tapping '\(buttonLabel)' for '\(dialog.key)'")
            button.tap()
            NSLog("[SystemPermissionHelper] [Fallback] Successfully tapped '\(buttonLabel)' on '\(dialog.key)'")
        } else {
            NSLog("[SystemPermissionHelper] [Fallback] Button at index \(buttonIndex) not found for '\(dialog.key)'")
        }
    }
}
