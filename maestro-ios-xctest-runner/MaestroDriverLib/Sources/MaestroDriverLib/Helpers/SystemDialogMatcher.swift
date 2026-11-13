import Foundation

/// How to recognise one iOS system permission dialog and which button answers it.
public struct SystemPermissionDialog: Equatable {
    /// Key in the permissions map sent by the CLI (e.g. "location").
    public let key: String
    /// Case-insensitive substring of the dialog title (or, failing that, its static texts).
    public let labelPattern: String
    /// Number of action buttons at the bottom of the dialog. Buttons in the dialog body
    /// (e.g. the location prompt's "Precise: On" map toggle, which can appear more than once)
    /// come before them, so action buttons are the last `actionButtonCount` buttons.
    public let actionButtonCount: Int
    /// PermissionValue → index among the action buttons. No entry means "leave it alone".
    public let buttonMap: [PermissionValue: Int]
    /// Optional exact (case-insensitive) labels tried before the index, per value.
    public let preferredLabels: [PermissionValue: [String]]

    public init(
        key: String,
        labelPattern: String,
        actionButtonCount: Int = 2,
        buttonMap: [PermissionValue: Int],
        preferredLabels: [PermissionValue: [String]] = [:]
    ) {
        self.key = key
        self.labelPattern = labelPattern
        self.actionButtonCount = actionButtonCount
        self.buttonMap = buttonMap
        self.preferredLabels = preferredLabels
    }
}

/// What the runner should tap, decided purely from an `AXElement` snapshot.
public struct SystemDialogTap: Equatable {
    public enum Reason: Equatable {
        case accountAlert
        case permission(key: String, value: PermissionValue)
        case customPermission(key: String, value: PermissionValue)
    }

    public let reason: Reason
    public let buttonLabel: String
    public let frame: AXFrame
}

/// Pure logic for finding and answering SpringBoard system dialogs (permission prompts and
/// Apple Account password alerts) in an `AXElement` tree taken with one throwing `snapshot()`.
///
/// This replaces per-element XCUIElement queries (`.exists`, `.label`,
/// `allElementsBoundByIndex`, `element(boundBy:)`). Those resolve every element again,
/// and when one cannot be resolved — e.g. the remote map view in the location prompt — they
/// record an XCTest failure instead of throwing, which ends `testHttpServer` and the runner.
public enum SystemDialogMatcher {

    /// All known iOS system permission dialogs.
    public static let permissionDialogs: [SystemPermissionDialog] = [
        // [Don't Allow (0), Allow (1)]
        SystemPermissionDialog(key: "notifications", labelPattern: "Would Like to Send You Notifications",
                               buttonMap: [.allow: 1, .deny: 0]),
        // [Allow Once (0), Allow While Using App (1), Don't Allow (2)], preceded by the
        // "Precise: On" map button(s) in the body.
        SystemPermissionDialog(key: "location", labelPattern: "to use your location",
                               actionButtonCount: 3,
                               buttonMap: [.allow: 1, .always: 1, .inuse: 1, .deny: 2, .never: 2],
                               preferredLabels: [
                                   .allow: ["allow while using app"],
                                   .always: ["allow while using app"],
                                   .inuse: ["allow while using app"],
                               ]),
        // "Would Like to Use Bluetooth" or "to find Bluetooth devices"
        SystemPermissionDialog(key: "bluetooth", labelPattern: "Bluetooth", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "camera", labelPattern: "Would Like to Access the Camera", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "microphone", labelPattern: "Would Like to Access the Microphone", buttonMap: [.allow: 1, .deny: 0]),
        // [Don't Allow (0), Allow Limited Access (1), Allow Full Access (2)]
        SystemPermissionDialog(key: "photos", labelPattern: "Would Like to Access Your Photos",
                               actionButtonCount: 3,
                               buttonMap: [.allow: 2, .limited: 1, .deny: 0]),
        SystemPermissionDialog(key: "contacts", labelPattern: "Would Like to Access Your Contacts", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "calendar", labelPattern: "Would Like to Access Your Calendar", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "reminders", labelPattern: "Would Like to Access Your Reminders", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "speech", labelPattern: "Would Like to Access Speech Recognition", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "motion", labelPattern: "Would Like to Access Your Motion", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "homekit", labelPattern: "Would Like to Access Your Home Data", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "medialibrary", labelPattern: "Would Like to Access Apple Music", buttonMap: [.allow: 1, .deny: 0]),
        SystemPermissionDialog(key: "siri", labelPattern: "Would Like to Access Siri", buttonMap: [.allow: 1, .deny: 0]),
        // [Ask App Not to Track (0), Allow (1)]
        SystemPermissionDialog(key: "usertracking", labelPattern: "Would Like Permission to Track", buttonMap: [.allow: 1, .deny: 0]),
        // "Allow X to find devices on local networks?"
        SystemPermissionDialog(key: "localnetwork", labelPattern: "local network", buttonMap: [.allow: 1, .deny: 0]),
    ]

    /// Permissions answered with "Allow" unless configured otherwise: these dialogs block the
    /// whole UI and are rarely denied on purpose.
    public static let autoAllowPermissions = ["bluetooth", "localnetwork"]

    // MARK: - Tree helpers

    /// Alerts and sheets in the tree, outermost first, in depth-first pre-order.
    public static func dialogs(in root: AXElement) -> [AXElement] {
        var result = [AXElement]()
        func visit(_ element: AXElement) {
            if element.elementType == ElementType.alert || element.elementType == ElementType.sheet {
                result.append(element)
                return
            }
            element.children?.forEach(visit)
        }
        visit(root)
        return result
    }

    /// The dialog's own label followed by its static text labels (non-empty, in order).
    public static func texts(of dialog: AXElement) -> [String] {
        ([dialog.label] + dialog.descendants(ofType: ElementType.staticText).map(\.label))
            .filter { !$0.isEmpty }
    }

    /// Buttons in the dialog, in depth-first pre-order (the order XCUIElement queries use).
    public static func buttons(of dialog: AXElement) -> [AXElement] {
        dialog.descendants(ofType: ElementType.button)
    }

    static func normalize(_ string: String) -> String {
        string
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    // MARK: - Apple Account alerts

    /// The "Not Now"/"Cancel" button of an Apple Account password alert anywhere in `root`,
    /// or nil when there is no such alert or it has no safe dismiss button.
    public static func accountAlertDismissal(in root: AXElement) -> SystemDialogTap? {
        for dialog in dialogs(in: root) where dialog.elementType == ElementType.alert {
            guard AccountAlertMatcher.isAccountAlert(texts: texts(of: dialog)) else { continue }
            let buttons = buttons(of: dialog)
            guard let index = AccountAlertMatcher.dismissButtonIndex(buttonLabels: buttons.map(\.label)) else {
                return nil
            }
            return SystemDialogTap(reason: .accountAlert, buttonLabel: buttons[index].label, frame: buttons[index].frame)
        }
        return nil
    }

    // MARK: - Permission dialogs

    /// The permission dialog that matches `dialog`, by title first and static texts second.
    /// Titles are tried first so a purpose string that mentions e.g. "Bluetooth" cannot
    /// steer a location prompt to the bluetooth answer.
    public static func matchingPermissionDialog(for dialog: AXElement) -> SystemPermissionDialog? {
        let title = dialog.label.lowercased()
        if let match = permissionDialogs.first(where: { title.contains($0.labelPattern.lowercased()) }) {
            return match
        }
        let allText = texts(of: dialog).joined(separator: " ").lowercased()
        return permissionDialogs.first { allText.contains($0.labelPattern.lowercased()) }
    }

    /// The button to tap for `value` in a dialog already known to be `definition`.
    public static func button(for value: PermissionValue, definition: SystemPermissionDialog, in dialog: AXElement) -> AXElement? {
        let buttons = buttons(of: dialog)
        guard !buttons.isEmpty else { return nil }

        if let wanted = definition.preferredLabels[value]?.map(normalize),
           let byLabel = buttons.first(where: { wanted.contains(normalize($0.label)) }) {
            return byLabel
        }

        guard let index = definition.buttonMap[value] else { return nil }
        let actionButtons = buttons.count >= definition.actionButtonCount
            ? Array(buttons.suffix(definition.actionButtonCount))
            : buttons
        return actionButtons.indices.contains(index) ? actionButtons[index] : nil
    }

    /// The button to tap to answer a permission dialog in `root` according to `permissions`,
    /// or nil when nothing should be tapped.
    public static func permissionTap(in root: AXElement, permissions: [String: PermissionValue]) -> SystemDialogTap? {
        let dialogs = dialogs(in: root)
        for dialog in dialogs {
            guard let definition = matchingPermissionDialog(for: dialog),
                  let value = permissions[definition.key],
                  value != .unset, value != .unknown,
                  let button = button(for: value, definition: definition, in: dialog) else {
                continue
            }
            return SystemDialogTap(reason: .permission(key: definition.key, value: value),
                                   buttonLabel: button.label, frame: button.frame)
        }
        // An alert/sheet we chose not to answer (e.g. location unset) must not be answered
        // through the custom path because its purpose string mentions "Bluetooth".
        guard dialogs.isEmpty else { return nil }
        return customPermissionTap(in: root, permissions: permissions)
    }

    /// Bluetooth / Local Network prompts that are neither alerts nor sheets: a static text
    /// mentioning the pattern plus an "Allow"/"Don't Allow" button somewhere in the tree.
    /// Only the auto-allow permissions are handled this way, and only when the tree has no
    /// alert or sheet.
    static func customPermissionTap(in root: AXElement, permissions: [String: PermissionValue]) -> SystemDialogTap? {
        let staticTexts = root.descendants(ofType: ElementType.staticText).map { $0.label.lowercased() }
        guard !staticTexts.isEmpty else { return nil }
        let buttons = root.descendants(ofType: ElementType.button)

        for key in autoAllowPermissions {
            guard let definition = permissionDialogs.first(where: { $0.key == key }),
                  let value = permissions[key],
                  value != .unset, value != .unknown else {
                continue
            }
            let pattern = definition.labelPattern.lowercased()
            guard staticTexts.contains(where: { $0.contains(pattern) }) else { continue }

            let target: String
            switch value {
            case .deny, .never: target = "don't allow"
            default: target = "allow"
            }
            guard let button = buttons.first(where: { normalize($0.label) == target }) else { continue }
            return SystemDialogTap(reason: .customPermission(key: key, value: value),
                                   buttonLabel: button.label, frame: button.frame)
        }
        return nil
    }
}
