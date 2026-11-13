import Foundation

/// Represents the desired permission state
public enum PermissionValue: String, Codable, Equatable {
    case allow
    case deny
    case unset
    case unknown
    // Location-specific
    case always
    case inuse
    case never
    // Photos-specific
    case limited

    public init(from decoder: Decoder) throws {
        self = try PermissionValue(rawValue: decoder.singleValueContainer().decode(RawValue.self)) ?? .unknown
    }
}