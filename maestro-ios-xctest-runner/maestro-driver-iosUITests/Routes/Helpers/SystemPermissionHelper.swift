import XCTest
import MaestroDriverLib

/// Answers iOS system permission dialogs and dismisses Apple Account password alerts.
///
/// Everything is read from one throwing `snapshot()` (an `AXElement` tree) and matched by
/// `SystemDialogMatcher` in MaestroDriverLib. Do not go back to non-throwing XCUIElement
/// APIs (`.exists`, `.label`, `allElementsBoundByIndex`, `element(boundBy:)`) here: they
/// resolve each element again, and when one cannot be resolved they record an XCTest failure
/// instead of throwing. That fails `testHttpServer` and the runner exits. The iOS 26
/// location prompt reliably triggers this: its map / "Precise: On" part is a remote view
/// hosted by CoreLocationVanillaWhenInUseAuthPromptPlugin, and per-element queries into it
/// killed the runner on a physical iPhone.
@MainActor
final class SystemPermissionHelper {

    /// AXElement-based notifications button finder from upstream MaestroDriverLib, kept as a
    /// last resort for a notifications prompt that is not exposed as an alert or sheet.
    private static let buttonFinder = PermissionButtonFinder()

    /// Returns the stored permissions with auto-allow defaults applied.
    private static func effectivePermissions() -> [String: PermissionValue]? {
        guard let data = UserDefaults.standard.object(forKey: "permissions") as? Data,
              var permissions = try? JSONDecoder().decode([String: PermissionValue].self, from: data) else {
            // Even with no permissions configured, still handle auto-allow permissions
            var defaults: [String: PermissionValue] = [:]
            for key in SystemDialogMatcher.autoAllowPermissions { defaults[key] = .allow }
            return defaults
        }
        for key in SystemDialogMatcher.autoAllowPermissions where permissions[key] == nil {
            permissions[key] = .allow
        }
        return permissions.isEmpty ? nil : permissions
    }

    private static func describe(_ tap: SystemDialogTap) -> String {
        switch tap.reason {
        case .accountAlert:
            return "Apple Account alert"
        case .permission(let key, let value):
            return "'\(key)' permission dialog (\(value.rawValue))"
        case .customPermission(let key, let value):
            return "'\(key)' custom permission dialog (\(value.rawValue))"
        @unknown default:
            return "system dialog"
        }
    }

    /// Last dialog summary logged by the view-hierarchy hook, so a dialog that stays on screen
    /// is logged once rather than on every request.
    private static var lastLoggedDialogs = ""

    private static func logDialogs(in root: AXElement, source: String, onlyOnChange: Bool) {
        let summary = SystemDialogMatcher.dialogs(in: root).map { dialog -> String in
            let kind = dialog.elementType == ElementType.alert ? "alert" : "sheet"
            let title = SystemDialogMatcher.texts(of: dialog).first ?? ""
            let buttons = SystemDialogMatcher.buttons(of: dialog).map(\.label)
            return "\(kind) '\(title)' buttons: \(buttons)"
        }.joined(separator: "; ")
        if onlyOnChange {
            guard summary != lastLoggedDialogs else { return }
            lastLoggedDialogs = summary
        }
        if !summary.isEmpty {
            NSLog("[SystemPermissionHelper] [\(source)] Found \(summary)")
        }
    }

    /// The tap to perform for `root`, if any: an Apple Account alert first (regardless of
    /// permissions), then a permission dialog when `permissionsApply` is true.
    private static func decide(in root: AXElement, permissionsApply: Bool) -> SystemDialogTap? {
        if let tap = SystemDialogMatcher.accountAlertDismissal(in: root) {
            return tap
        }
        guard permissionsApply, let permissions = effectivePermissions() else {
            return nil
        }
        return SystemDialogMatcher.permissionTap(in: root, permissions: permissions)
    }

    // MARK: - Interruption monitor handler

    /// Handles a system interruption element (alert or sheet) delivered by addUIInterruptionMonitor.
    /// Returns true if the dialog was handled, false otherwise.
    static func handleInterruption(_ element: XCUIElement) -> Bool {
        let root: AXElement
        do {
            root = AXElement(try element.snapshot().dictionaryRepresentation)
        } catch {
            NSLog("[SystemPermissionHelper] [Interruption] snapshot failed: \(error.localizedDescription)")
            return false
        }
        logDialogs(in: root, source: "Interruption", onlyOnChange: false)

        guard let tap = decide(in: root, permissionsApply: true) else {
            NSLog("[SystemPermissionHelper] [Interruption] No matching dialog, leaving it")
            return false
        }

        // The interruption handler is synchronous, so tap through XCUIElement. Resolve the
        // button by its (unique) label rather than by index, and only after the throwing
        // snapshot above has confirmed it is there.
        NSLog("[SystemPermissionHelper] [Interruption] Tapping '\(tap.buttonLabel)' on \(describe(tap))")
        element.buttons[tap.buttonLabel].firstMatch.tap()
        return true
    }

    // MARK: - ViewHierarchy hook

    /// Called from ViewHierarchyHandler on every view-hierarchy request, as a safety net in case
    /// the interruption monitor did not fire.
    ///
    /// - Parameters:
    ///   - springboardHierarchy: one throwing snapshot of SpringBoard taken by the caller, or nil
    ///     when that snapshot failed (then nothing is done).
    ///   - appHierarchy: the foreground app's hierarchy, used by the notifications last resort.
    ///   - foregroundApp: permission dialogs are only answered while SpringBoard is in front.
    ///     Apple Account alerts are dismissed whatever app is in front.
    static func handleSystemPermissionAlertIfNeeded(
        springboardHierarchy: AXElement?,
        appHierarchy: AXElement,
        foregroundApp: XCUIApplication
    ) async {
        guard let springboardHierarchy = springboardHierarchy else {
            NSLog("[SystemPermissionHelper] [Fallback] No SpringBoard snapshot, skipping system dialog check")
            return
        }

        let springboardInFront = foregroundApp.bundleID == "com.apple.springboard"
        if springboardInFront {
            logDialogs(in: springboardHierarchy, source: "Fallback", onlyOnChange: true)
        }

        if let tap = decide(in: springboardHierarchy, permissionsApply: springboardInFront) {
            NSLog("[SystemPermissionHelper] [Fallback] Tapping '\(tap.buttonLabel)' on \(describe(tap)) at \(tap.frame)")
            await tapAtCenter(of: tap.frame)
            return
        }

        guard springboardInFront, let notifications = effectivePermissions()?["notifications"] else {
            return
        }
        switch buttonFinder.findButtonToTap(for: notifications, in: appHierarchy) {
        case .found(let frame):
            NSLog("[SystemPermissionHelper] [AX-Fallback] Found notifications button at frame: \(frame)")
            await tapAtCenter(of: frame)
        case .noButtonsFound, .noActionRequired:
            break
        @unknown default:
            break
        }
    }

    /// Tap at the center of a frame with RunnerDaemonProxy. Needs no XCUIElement resolution,
    /// so it cannot record an XCTest failure.
    private static func tapAtCenter(of frame: AXFrame) async {
        let (width, height) = ScreenSizeHelper.physicalScreenSize()
        let point = ScreenSizeHelper.orientationAwarePoint(
            width: width,
            height: height,
            point: CGPoint(x: CGFloat(frame.centerX), y: CGFloat(frame.centerY))
        )

        let eventRecord = EventRecord(orientation: .portrait)
        _ = eventRecord.addPointerTouchEvent(at: point, touchUpAfter: nil)

        do {
            try await RunnerDaemonProxy().synthesize(eventRecord: eventRecord)
        } catch {
            NSLog("[SystemPermissionHelper] Error tapping system dialog button: \(error)")
        }
    }
}
