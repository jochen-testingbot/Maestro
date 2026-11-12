import Foundation

/// Recognises iOS system alerts that ask for an Apple Account / Apple ID password
/// ("Apple Account Verification", "Sign In to iTunes Store", …) and picks the button
/// that dismisses them without signing in.
///
/// These alerts are not permission prompts. They appear when an Apple Account is signed in
/// on the device and iOS wants its password re-entered, and they block every interaction
/// with the app under test. The only safe action is to dismiss them: the matcher never
/// returns "Settings", "Sign In", "Continue" or any button that leads to entering a password.
public enum AccountAlertMatcher {

    /// Case-insensitive substrings of the alert title or body that identify an
    /// Apple Account password prompt.
    public static let titlePatterns: [String] = [
        "apple account verification",          // iOS 18+
        "apple id verification",               // iOS ≤ 17
        "sign in to itunes store",
        "sign in to the itunes store",
        "sign in to apple account",
        "sign in with your apple account",
        "sign in with your apple id",
        "apple account password",
        "apple id password",
    ]

    /// Buttons that dismiss the alert, in order of preference. Matched on the whole
    /// (trimmed, case-insensitive) label, never as a substring.
    public static let dismissLabels: [String] = ["not now", "cancel", "later", "close"]

    /// True when the given alert texts (title, message, static texts) identify an
    /// Apple Account password prompt.
    public static func isAccountAlert(texts: [String]) -> Bool {
        let haystack = normalize(texts.joined(separator: " "))
        return titlePatterns.contains { haystack.contains($0) }
    }

    /// Index of the button to tap to dismiss the alert, or nil when none of the
    /// buttons is a known dismiss button (in which case nothing must be tapped).
    public static func dismissButtonIndex(buttonLabels: [String]) -> Int? {
        let normalized = buttonLabels.map(normalize)
        for wanted in dismissLabels {
            if let index = normalized.firstIndex(of: wanted) {
                return index
            }
        }
        return nil
    }

    /// Lowercases, folds every Unicode whitespace run into one plain space and trims.
    /// iOS 26+ writes the brand name with a no-break space ("Apple\u{00A0}Account
    /// Verification"), which a plain-space pattern never matches.
    static func normalize(_ text: String) -> String {
        text.lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }
}
