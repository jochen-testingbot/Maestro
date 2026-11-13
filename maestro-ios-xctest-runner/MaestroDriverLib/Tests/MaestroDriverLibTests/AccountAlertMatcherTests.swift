import XCTest
@testable import MaestroDriverLib

final class AccountAlertMatcherTests: XCTestCase {

    func testMatchesAppleAccountVerification() {
        let texts = [
            "Apple Account Verification",
            "Enter the password for “someone@example.com” in Settings.",
        ]
        XCTAssertTrue(AccountAlertMatcher.isAccountAlert(texts: texts))
    }

    // iOS 26/27 SpringBoard label, as logged on iphone17-02 (bytes 41 70 70 6c 65 c2 a0 41 ...).
    func testMatchesAppleAccountWithNoBreakSpace() {
        XCTAssertTrue(AccountAlertMatcher.isAccountAlert(texts: ["Apple\u{00A0}Account Verification"]))
        XCTAssertTrue(AccountAlertMatcher.isAccountAlert(texts: ["Apple\u{202F}ID\u{00A0}Verification"]))
    }

    func testPicksNotNowWithNoBreakSpace() {
        XCTAssertEqual(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Not\u{00A0}Now", "Settings"]), 0)
    }

    func testMatchesLegacyAppleIdAndItunesPrompts() {
        XCTAssertTrue(AccountAlertMatcher.isAccountAlert(texts: ["Apple ID Verification"]))
        XCTAssertTrue(AccountAlertMatcher.isAccountAlert(texts: ["Sign In to iTunes Store"]))
    }

    func testDoesNotMatchPermissionPrompts() {
        XCTAssertFalse(AccountAlertMatcher.isAccountAlert(texts: ["“App” Would Like to Send You Notifications"]))
        XCTAssertFalse(AccountAlertMatcher.isAccountAlert(texts: ["Allow “App” to use your location?"]))
        XCTAssertFalse(AccountAlertMatcher.isAccountAlert(texts: []))
    }

    func testPicksNotNowAndNeverSettings() {
        XCTAssertEqual(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Not Now", "Settings"]), 0)
        XCTAssertEqual(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Settings", "Not Now"]), 1)
    }

    func testFallsBackToCancel() {
        XCTAssertEqual(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Sign In", " Cancel "]), 1)
    }

    func testReturnsNilWithoutADismissButton() {
        XCTAssertNil(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Settings"]))
        XCTAssertNil(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Sign In", "Continue"]))
        // Substrings must not count: "Cancel Subscription" is not a dismiss button.
        XCTAssertNil(AccountAlertMatcher.dismissButtonIndex(buttonLabels: ["Cancel Subscription", "Settings"]))
    }
}
