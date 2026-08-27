import Foundation
import SwiftData

/// One observation of a FRED economic series.
@Model
final class MacroObservation {
    /// FRED series ID, e.g. "CPIAUCSL", "UNRATE", "DFF".
    var seriesID: String
    var date: Date
    var value: Double
    var observedAt: Date

    init(seriesID: String, date: Date, value: Double, observedAt: Date = .now) {
        self.seriesID = seriesID
        self.date = date
        self.value = value
        self.observedAt = observedAt
    }
}

/// The macro indicators from Section 11, with the FRED series that back them.
struct MacroIndicator: Sendable, Hashable, Identifiable {
    let id: String
    let seriesID: String
    let displayName: String
    let unit: MacroUnit
    /// Plain-language note on what the series measures. Shown in the UI so the
    /// number is interpretable without leaving the app.
    let note: String

    static let defaults: [MacroIndicator] = [
        .init(id: "cpi", seriesID: "CPIAUCSL", displayName: "CPI",
              unit: .index,
              note: "Consumer Price Index, all urban consumers, seasonally adjusted."),
        .init(id: "coreCPI", seriesID: "CPILFESL", displayName: "Core CPI",
              unit: .index,
              note: "CPI excluding food and energy."),
        .init(id: "unemployment", seriesID: "UNRATE", displayName: "Unemployment",
              unit: .percent,
              note: "Civilian unemployment rate."),
        .init(id: "fedFunds", seriesID: "DFF", displayName: "Fed funds rate",
              unit: .percent,
              note: "Effective federal funds rate, daily."),
        .init(id: "twoYear", seriesID: "DGS2", displayName: "2Y Treasury",
              unit: .percent,
              note: "2-year Treasury constant maturity yield."),
        .init(id: "tenYear", seriesID: "DGS10", displayName: "10Y Treasury",
              unit: .percent,
              note: "10-year Treasury constant maturity yield."),
        .init(id: "yieldCurve", seriesID: "T10Y2Y", displayName: "10Y–2Y spread",
              unit: .percent,
              note: "10-year minus 2-year Treasury yield."),
        .init(id: "gdp", seriesID: "GDPC1", displayName: "Real GDP",
              unit: .currency,
              note: "Real gross domestic product, chained 2017 dollars."),
        .init(id: "inflationExpectations", seriesID: "T5YIE",
              displayName: "5Y inflation breakeven",
              unit: .percent,
              note: "5-year breakeven inflation rate implied by TIPS."),
        .init(id: "sentiment", seriesID: "UMCSENT", displayName: "Consumer sentiment",
              unit: .index,
              note: "University of Michigan consumer sentiment index.")
    ]
}

enum MacroUnit: String, Sendable, Hashable {
    case percent, index, currency
}
