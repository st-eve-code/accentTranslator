import Foundation

public struct AccentOption: Identifiable {
    public let id: String
    public let label: String
}

struct AccentCatalogRoot: Decodable {
    let version: Int?
    let defaultAccent: String?
    let accents: [String: AccentDef]

    enum CodingKeys: String, CodingKey {
        case version, accents
        case defaultAccent = "default_accent"
    }
}

struct AccentDef: Decodable {
    let label: String
    let words: [String: String]
    let regex: [RegexRuleDef]?
}

struct RegexRuleDef: Decodable {
    let pattern: String
    let replace: String
    let caseInsensitive: Bool?

    enum CodingKeys: String, CodingKey {
        case pattern, replace
        case caseInsensitive = "case_insensitive"
    }
}

struct CompiledRegexRule {
    let regex: NSRegularExpression
    let replacement: String
}

public final class AccentEngine {

    public let accentID: String
    public let accentLabel: String

    private let wordRegex: NSRegularExpression
    private let wordMap: [String: String]
    private let regexRules: [CompiledRegexRule]

    public init(catalogJSON jsonString: String, accentID: String? = nil) throws {
        guard let data = jsonString.data(using: .utf8),
              let root = try? JSONDecoder().decode(AccentCatalogRoot.self, from: data),
              !root.accents.isEmpty else {
            throw NSError(
                domain: "AccentEngine",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid or empty accents catalog"]
            )
        }

        let fallbackID = root.defaultAccent.flatMap { root.accents[$0] != nil ? $0 : nil }
            ?? root.accents.keys.sorted().first!
        let wanted = accentID ?? root.defaultAccent
        let resolvedID = wanted.flatMap { root.accents[$0] != nil ? $0 : nil } ?? fallbackID

        guard let def = root.accents[resolvedID] else {
            throw NSError(
                domain: "AccentEngine",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Missing accent definition: \(resolvedID)"]
            )
        }

        self.accentID = resolvedID
        self.accentLabel = def.label

        let entries = def.words
            .map { (source: Self.normalize($0.key), target: $0.value) }
            .sorted { lhs, rhs in
                if lhs.source.count != rhs.source.count { return lhs.source.count > rhs.source.count }
                return lhs.source < rhs.source
            }

        var map: [String: String] = [:]
        var parts: [String] = []
        for entry in entries {
            map[entry.source] = entry.target
            parts.append(NSRegularExpression.escapedPattern(for: entry.source))
        }
        self.wordMap = map

        self.wordRegex = try NSRegularExpression(
            pattern: "\\b(?:" + parts.joined(separator: "|") + ")\\b",
            options: [.caseInsensitive]
        )

        var compiledRules: [CompiledRegexRule] = []
        for rule in def.regex ?? [] {
            let regex = try NSRegularExpression(
                pattern: rule.pattern,
                options: (rule.caseInsensitive ?? false) ? [.caseInsensitive] : []
            )
            compiledRules.append(CompiledRegexRule(regex: regex, replacement: rule.replace))
        }
        self.regexRules = compiledRules
    }

    public convenience init(bundle: Bundle = .main, accentID: String? = nil) throws {
        guard let url = bundle.url(forResource: "accents", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else {
            throw NSError(
                domain: "AccentEngine",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "accents.json not found in bundle"]
            )
        }
        try self.init(catalogJSON: json, accentID: accentID)
    }

    public static func availableAccents(inJSON jsonString: String) -> [AccentOption] {
        guard let data = jsonString.data(using: .utf8),
              let root = try? JSONDecoder().decode(AccentCatalogRoot.self, from: data) else {
            return []
        }
        return root.accents.keys.sorted().map { key in
            AccentOption(id: key, label: root.accents[key]?.label ?? key)
        }
    }

    public func translate(_ text: String) -> String {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return text }
        let normalized = text
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2018}", with: "'")
        return applyRegexRules(swapWords(normalized))
    }

    private func swapWords(_ text: String) -> String {
        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)
        let matches = wordRegex.matches(in: text, range: fullRange)
        guard !matches.isEmpty else { return text }

        var output = ""
        var cursor = 0
        for match in matches {
            let original = nsText.substring(with: match.range)
            output += nsText.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            if let replacement = wordMap[original.lowercased()] {
                output += Self.matchCase(original: original, replacement: replacement)
            } else {
                output += original
            }
            cursor = match.range.location + match.range.length
        }
        output += nsText.substring(from: cursor)
        return output
    }

    private func applyRegexRules(_ text: String) -> String {
        var result = text
        for rule in regexRules {
            let nsResult = result as NSString
            let fullRange = NSRange(location: 0, length: nsResult.length)
            let matches = rule.regex.matches(in: result, range: fullRange)
            guard !matches.isEmpty else { continue }

            var output = ""
            var cursor = 0
            for match in matches {
                let original = nsResult.substring(with: match.range)
                output += nsResult.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
                output += Self.matchCase(original: original, replacement: rule.replacement)
                cursor = match.range.location + match.range.length
            }
            output += nsResult.substring(from: cursor)
            result = output
        }
        return result
    }

    static func matchCase(original: String, replacement: String) -> String {
        guard !replacement.isEmpty,
              let firstLetter = original.first(where: { $0.isLetter }) else { return replacement }
        let letters = original.filter { $0.isLetter }
        if letters.count > 1 && letters.allSatisfy({ $0.isUppercase }) {
            return replacement.uppercased()
        }
        if firstLetter.isUppercase {
            return replacement.prefix(1).uppercased() + replacement.dropFirst()
        }
        return replacement
    }

    private static func normalize(_ key: String) -> String {
        key.lowercased()
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2018}", with: "'")
    }
}
