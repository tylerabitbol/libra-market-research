import Testing
import Foundation
@testable import Vantage

@Suite("FRED decoding")
struct FREDDecodingTests {
    @Test("A real observations payload decodes")
    func decodesRealPayload() throws {
        let response = try Fixture.decode(
            FREDObservationsResponse.self, from: "fred_observations_SP500"
        )
        #expect(response.observations.count == 12)
    }

    @Test("The '.' missing marker is dropped, never coerced to zero")
    func missingMarkerIsDropped() throws {
        let response = try Fixture.decode(
            FREDObservationsResponse.self, from: "fred_observations_SP500"
        )
        // This window spans Christmas and New Year, both market holidays.
        let markers = response.observations.filter { $0.value == "." }
        #expect(markers.count == 2, "Fixture should contain the holiday gaps")

        let parsed = response.observations.compactMap { FREDProvider.parseValue($0.value) }
        #expect(parsed.count == response.observations.count - 2)
        #expect(!parsed.contains(0), "A holiday must not become a crash to zero on the chart")
    }

    @Test("parseValue rejects the marker and blanks, accepts real numbers")
    func parseValueBehaviour() {
        #expect(FREDProvider.parseValue(".") == nil)
        #expect(FREDProvider.parseValue("") == nil)
        #expect(FREDProvider.parseValue("   ") == nil)
        #expect(FREDProvider.parseValue("6932.05") == 6932.05)
        #expect(FREDProvider.parseValue(" 15.21 ") == 15.21)
    }

    @Test("The VIX series decodes as real index levels")
    func vixDecodes() throws {
        let response = try Fixture.decode(
            FREDObservationsResponse.self, from: "fred_observations_VIXCLS"
        )
        let values = response.observations.compactMap { FREDProvider.parseValue($0.value) }
        #expect(!values.isEmpty)
        // The VIX has never closed outside this band; a value beyond it means
        // we decoded the wrong field.
        #expect(values.allSatisfy { $0 > 0 && $0 < 200 })
    }
}
