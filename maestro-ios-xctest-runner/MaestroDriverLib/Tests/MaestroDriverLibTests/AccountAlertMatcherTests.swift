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
