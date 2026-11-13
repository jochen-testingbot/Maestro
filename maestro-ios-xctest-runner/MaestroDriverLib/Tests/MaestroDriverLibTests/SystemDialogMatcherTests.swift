import XCTest
@testable import MaestroDriverLib

final class SystemDialogMatcherTests: XCTestCase {

    // MARK: - Fixtures

    private func frame(_ y: Double) -> AXFrame {
        ["X": 20, "Y": y, "Width": 300, "Height": 44]
    }

    private func button(_ label: String, y: Double, children: [AXElement]? = nil) -> AXElement {
        AXElement(frame: frame(y), label: label, elementType: ElementType.button, children: children)
    }

    private func text(_ label: String) -> AXElement {
        AXElement(label: label, elementType: ElementType.staticText)
    }

    /// The map / "Precise: On" part of the iOS location prompt. It is a remote view hosted by
    /// CoreLocationVanillaWhenInUseAuthPromptPlugin and shows up twice in SpringBoard's snapshot.
    private func mapSubtree(y: Double) -> [AXElement] {
        [
            AXElement(label: "Map", elementType: 43),
            button("Precise: On", y: y, children: [text("Precise: On")]),
        ]
    }

    /// SpringBoard tree rebuilt from the TestingBot session f3ffd402 hierarchy JSON.
    private func locationPrompt(title: String = "Allow “FR8BOT” to use your location?") -> AXElement {
        let body = AXElement(elementType: 46, children: [
            AXElement(label: "Location@3x.png", elementType: 43),
            text(title),
            text("We use your location to track deliveries and Bluetooth beacons."),
        ] + mapSubtree(y: 300) + mapSubtree(y: 300))
        let actions = AXElement(elementType: 46, children: [
            button("Allow Once", y: 500),
            button("Allow While Using App", y: 550),
            button("Don’t Allow", y: 600),
        ])
        let alert = AXElement(label: title, elementType: ElementType.alert, children: [body, actions])
        let window = AXElement(elementType: 4, children: [alert])
        return AXElement(label: " ", elementType: 2, children: [window])
    }

    private func twoButtonAlert(title: String, buttons labels: [String]) -> AXElement {
        let alert = AXElement(label: title, elementType: ElementType.alert, children: [
            text(title),
        ] + labels.enumerated().map { button($0.element, y: 500 + Double($0.offset) * 50) })
        return AXElement(elementType: 2, children: [AXElement(elementType: 4, children: [alert])])
    }

    // MARK: - Location prompt with map

    func testLocationPromptAllowTapsAllowWhileUsingApp() {
        let tap = SystemDialogMatcher.permissionTap(in: locationPrompt(), permissions: ["location": .allow])
        XCTAssertEqual(tap?.buttonLabel, "Allow While Using App")
        XCTAssertEqual(tap?.frame, frame(550))
        XCTAssertEqual(tap?.reason, .permission(key: "location", value: .allow))
    }

    func testLocationPromptDenyTapsDontAllowDespiteMapButtons() {
        let tap = SystemDialogMatcher.permissionTap(in: locationPrompt(), permissions: ["location": .deny])
        XCTAssertEqual(tap?.buttonLabel, "Don’t Allow")
    }

    func testLocationPromptIndexFallbackSkipsDuplicatedPreciseButtons() {
        // No preferred label for .inuse would still land on index 1 of the last 3 buttons.
        let definition = SystemPermissionDialog(key: "location", labelPattern: "to use your location",
                                                actionButtonCount: 3, buttonMap: [.inuse: 1])
        let alert = SystemDialogMatcher.dialogs(in: locationPrompt()).first!
        XCTAssertEqual(SystemDialogMatcher.buttons(of: alert).count, 5)
        XCTAssertEqual(SystemDialogMatcher.button(for: .inuse, definition: definition, in: alert)?.label,
                       "Allow While Using App")
    }

    func testLocationPromptUnsetIsLeftAlone() {
        XCTAssertNil(SystemDialogMatcher.permissionTap(in: locationPrompt(), permissions: ["location": .unset]))
        XCTAssertNil(SystemDialogMatcher.permissionTap(in: locationPrompt(), permissions: [:]))
    }

    func testPurposeStringMentioningBluetoothDoesNotSteerLocationPrompt() {
        // Location unset but bluetooth auto-allowed: the purpose string says "Bluetooth", but the
        // title is a location prompt, so nothing must be tapped.
        let tap = SystemDialogMatcher.permissionTap(in: locationPrompt(), permissions: ["bluetooth": .allow])
        XCTAssertNil(tap)
    }

    func testLocationPromptIsNotAnAccountAlert() {
        XCTAssertNil(SystemDialogMatcher.accountAlertDismissal(in: locationPrompt()))
    }

    // MARK: - Other dialogs

    func testNotificationsAllowAndDeny() {
        let tree = twoButtonAlert(title: "“App” Would Like to Send You Notifications",
                                  buttons: ["Don’t Allow", "Allow"])
        XCTAssertEqual(SystemDialogMatcher.permissionTap(in: tree, permissions: ["notifications": .allow])?.buttonLabel, "Allow")
        XCTAssertEqual(SystemDialogMatcher.permissionTap(in: tree, permissions: ["notifications": .deny])?.buttonLabel, "Don’t Allow")
    }

    func testPhotosLimited() {
        let tree = twoButtonAlert(title: "“App” Would Like to Access Your Photos",
                                  buttons: ["Don’t Allow", "Allow Limited Access", "Allow Full Access"])
        XCTAssertEqual(SystemDialogMatcher.permissionTap(in: tree, permissions: ["photos": .limited])?.buttonLabel, "Allow Limited Access")
        XCTAssertEqual(SystemDialogMatcher.permissionTap(in: tree, permissions: ["photos": .allow])?.buttonLabel, "Allow Full Access")
    }

    func testCustomLocalNetworkDialogWithoutAlert() {
        let tree = AXElement(elementType: 2, children: [
            text("“App” would like to find and connect to devices on your local network."),
            button("Don’t Allow", y: 500),
            button("Allow", y: 550),
        ])
        let tap = SystemDialogMatcher.permissionTap(in: tree, permissions: ["localnetwork": .allow])
        XCTAssertEqual(tap?.buttonLabel, "Allow")
        XCTAssertEqual(tap?.reason, .customPermission(key: "localnetwork", value: .allow))
    }

    func testNoDialogNoTap() {
        let tree = AXElement(elementType: 2, children: [text("Home"), button("Settings", y: 10)])
        XCTAssertNil(SystemDialogMatcher.permissionTap(in: tree, permissions: ["location": .allow, "bluetooth": .allow]))
        XCTAssertNil(SystemDialogMatcher.accountAlertDismissal(in: tree))
    }

    // MARK: - Apple Account alert

    func testAccountAlertDismissedWithNotNow() {
        let tree = twoButtonAlert(title: "Apple Account Verification", buttons: ["Settings", "Not Now"])
        let tap = SystemDialogMatcher.accountAlertDismissal(in: tree)
        XCTAssertEqual(tap?.buttonLabel, "Not Now")
        XCTAssertEqual(tap?.frame, frame(550))
        XCTAssertEqual(tap?.reason, .accountAlert)
    }

    func testAccountAlertWithoutDismissButtonIsLeftAlone() {
        let tree = twoButtonAlert(title: "Apple Account Verification", buttons: ["Settings"])
        XCTAssertNil(SystemDialogMatcher.accountAlertDismissal(in: tree))
    }
}
