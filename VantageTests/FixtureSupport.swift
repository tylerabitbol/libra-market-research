import Foundation
import Testing
@testable import Vantage

/// Anchors `Bundle(for:)` to the test bundle so fixtures can be located.
/// Swift Testing suites are structs, which `Bundle(for:)` cannot take.
private final class FixtureAnchor {}

/// Loads the JSON captured from real API responses during Phase 2 planning.
///
/// Every provider test decodes a real payload rather than a hand-written one.
/// Hand-authored fixtures drift from what a vendor actually sends and hide
/// exactly the surprises worth catching — Finnhub's zero-filled quotes for
/// unknown symbols, FRED's "." for market holidays.
enum Fixture {
    static func data(_ name: String) throws -> Data {
        let bundle = Bundle(for: FixtureAnchor.self)
        guard let url = bundle.url(forResource: name, withExtension: "json") else {
            throw FixtureError.missing(name)
        }
        return try Data(contentsOf: url)
    }

    static func decode<T: Decodable>(_ type: T.Type, from name: String) throws -> T {
        try JSONDecoder.vantage.decode(T.self, from: data(name))
    }

    enum FixtureError: Error, CustomStringConvertible {
        case missing(String)
        var description: String {
            switch self {
            case .missing(let name):
                "Fixture \(name).json not found in the test bundle. "
                + "Check it is included as a resource in project.yml."
            }
        }
    }
}
