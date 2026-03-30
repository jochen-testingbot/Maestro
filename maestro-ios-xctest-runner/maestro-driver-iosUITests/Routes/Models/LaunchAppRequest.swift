import Foundation

struct LaunchAppRequest : Codable {
    let bundleId: String
    let launchArguments: [String: LaunchArgValue]?

    /// Flattens all argument values to a string array: ["-key", "value", ...]
    var flattenedLaunchArguments: [String] {
        guard let args = launchArguments, !args.isEmpty else { return [] }
        var result: [String] = []
        for (key, value) in args {
            result.append(key)
            result.append(value.stringValue)
        }
        return result
    }
}

/// Handles JSON values that can be strings, arrays, numbers, or booleans
enum LaunchArgValue: Codable {
    case string(String)
    case array([String])
    case number(Double)
    case bool(Bool)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let str = try? container.decode(String.self) {
            self = .string(str)
        } else if let arr = try? container.decode([String].self) {
            self = .array(arr)
        } else if let num = try? container.decode(Double.self) {
            self = .number(num)
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else {
            self = .string("")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let s): try container.encode(s)
        case .array(let a): try container.encode(a)
        case .number(let n): try container.encode(n)
        case .bool(let b): try container.encode(b)
        }
    }

    var stringValue: String {
        switch self {
        case .string(let s): return s
        case .array(let a): return a.joined(separator: " ")
        case .number(let n): return String(n)
        case .bool(let b): return String(b)
        }
    }
}
