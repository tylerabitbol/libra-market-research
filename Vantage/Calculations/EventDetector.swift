import Foundation

/// Deterministic detection of changes worth investigating — Section 4.
///
/// Everything here is pure Swift over bars and filings the app already holds.
/// No AI, no model, no prediction: a detector states that something is unusual
/// *relative to this security's own recent history* and shows the arithmetic.
/// It never says why, and never says what to do about it.
///
/// Three statistical decisions run through all of it, and each exists because
/// the obvious alternative produces confident nonsense:
///
/// - **Rank, not probability.** A 3σ day in a normal distribution is a 1-in-370
///   event. Daily equity returns are not normal — 3σ days arrive several times
///   a year — so converting a z-score to a probability would put a wildly
///   overstated rarity on screen. Unusualness is reported as position within
///   the observed sample: "larger than 248 of the past 250 sessions" is both
///   true and checkable.
/// - **Median and MAD, not mean and standard deviation.** The spike being
///   measured also inflates a standard deviation computed over the window
///   containing it, which shrinks its own z-score and hides the *next* spike
///   behind a permanently widened band.
/// - **An observation is excluded from its own reference sample**, for the same
///   reason valuation percentiles are: a value cannot be its own yardstick.
enum EventDetector {

    /// Sessions of history a detector requires before it will say anything.
    /// Below this, "unusual" is an opinion about a small sample.
    static let minimumSample = 40

    /// Window for measuring *dispersion*. Long enough to describe a regime,
    /// short enough that a change of regime shows up rather than being
    /// averaged away — a stock that got twice as volatile last month should be
    /// judged against last month, not against a calm year.
    static let scaleWindow = 60

    /// Window for measuring *rank*. Deliberately longer than the scale window:
    /// rank costs nothing extra to compute over more data, and a year of
    /// context supports a far more useful statement. "The largest move in 60
    /// sessions" understates what the app knows when it holds five years.
    static let rankWindow = 250

    /// Retained name for the dispersion window, which is what callers outside
    /// the anomaly machinery mean by "the reference window".
    static var referenceWindow: Int { scaleWindow }

    // MARK: - Entry point

    /// Runs every detector over one security.
    ///
    /// Bars must be daily and are sorted defensively — a provider returning
    /// newest-first would otherwise make "the latest session" the oldest one.
    ///
    /// The quote is not optional decoration. Daily bars end at the *previous*
    /// close, so a detector reading bars alone is blind to today — which is
    /// precisely the day the user is asking about. Passing the quote lets the
    /// current session be measured against the closed ones behind it.
    static func detect(
        bars: [PriceBar],
        quote: QuoteDTO? = nil,
        sourceDetail: String = "Daily bars"
    ) -> [DetectedEventDTO] {
        let sorted = bars.sorted { $0.date < $1.date }
        return [
            priceMove(bars: sorted, quote: quote, sourceDetail: sourceDetail),
            volumeAnomaly(bars: sorted, quote: quote, sourceDetail: sourceDetail),
            volatilityShift(bars: sorted, sourceDetail: sourceDetail)
        ].compactMap { $0 }
    }

    /// The most recent reading, from the live quote when it is ahead of the
    /// bars and from the last closed session otherwise.
    ///
    /// `isIntraday` travels with it because the distinction is material: a
    /// session still open can reverse before it closes, and reporting an
    /// unfinished move in the same words as a settled one would overstate it.
    struct LatestReading: Sendable, Hashable {
        let date: Date
        let percent: Double
        let close: Double
        let volume: Double?
        let isIntraday: Bool

        var sessionLabel: String {
            isIntraday ? "so far today" : "on \(Format.dayAndMonth(date))"
        }
    }

    /// Splits the series into "the reading being judged" and "what it is judged
    /// against", with the current session included when only the quote has it.
    ///
    /// Same-day equality matters more than the raw timestamp comparison: some
    /// providers do publish a partial bar for the open session, and counting
    /// that bar *and* the quote would enter one day's move twice.
    static func latestReading(
        bars: [PriceBar],
        quote: QuoteDTO?,
        calendar: Calendar = .current
    ) -> (reading: LatestReading, priors: [(date: Date, percent: Double, close: Double)])? {
        let returns = dailyReturns(bars: bars)
        guard let lastBar = bars.last, let lastReturn = returns.last else { return nil }

        if let quote, let percent = quote.changePercent {
            let quoteDate = quote.quoteTime ?? .now
            let isNewSession = quoteDate > lastBar.date
                && !calendar.isDate(quoteDate, inSameDayAs: lastBar.date)
            if isNewSession {
                // Every closed session is a prior; none of them is this one.
                return (LatestReading(date: quoteDate, percent: percent, close: quote.last,
                                      volume: quote.volume, isIntraday: true),
                        returns)
            }
        }

        return (LatestReading(date: lastReturn.date, percent: lastReturn.percent,
                              close: lastReturn.close, volume: lastBar.volume,
                              isIntraday: false),
                Array(returns.dropLast()))
    }

    // MARK: - Price

    /// A daily move large relative to this security's own recent daily moves.
    ///
    /// Two conditions must both hold. The rank condition asks whether the move
    /// is unusual *for this security*; the deviation condition keeps a quiet
    /// name's ordinary wobble from being flagged merely because the sample is
    /// tight. A minimum absolute move sits on top of both as noise suppression
    /// — that one is a judgement about what is worth a reader's attention, not
    /// a statistical claim, and is labelled as such.
    /// - Parameters:
    ///   - rankThreshold: the primary criterion — how far into the tail of the
    ///     security's own recent sessions the move must sit. This is what the
    ///     app actually states on screen, so it is what the gate is built on.
    ///   - deviationThreshold: a secondary guard, not a second opinion. It
    ///     exists only to reject a move that ranks highly because the sample
    ///     happens to be unusually tight. Set low deliberately: an earlier
    ///     value of 3.0 rejected an 8.7% day that was the *largest in the whole
    ///     reference window*, because a high-volatility name has a large MAD —
    ///     which made the detector least sensitive exactly where large moves
    ///     matter most.
    ///   - minimumAbsoluteMove: noise suppression. A judgement about what is
    ///     worth attention, not a statistical claim.
    static func priceMove(
        bars: [PriceBar],
        quote: QuoteDTO? = nil,
        rankThreshold: Double = 0.975,
        deviationThreshold: Double = 2,
        minimumAbsoluteMove: Double = 1.0,
        sourceDetail: String = "Daily bars"
    ) -> DetectedEventDTO? {
        guard let (latest, priorReturns) = latestReading(bars: bars, quote: quote),
              priorReturns.count >= minimumSample
        else { return nil }

        return priceMoveEvent(
            latest: latest,
            priors: priorReturns.map(\.percent),
            rankThreshold: rankThreshold,
            deviationThreshold: deviationThreshold,
            minimumAbsoluteMove: minimumAbsoluteMove,
            sourceDetail: sourceDetail
        )
    }

    /// Unusual moves on sessions that closed after a given date.
    ///
    /// `priceMove` only ever judges the newest session, which means a large
    /// move on a day the user did not open the app was never recorded even
    /// though its bar was stored. This walks the gap between visits so the
    /// record is complete regardless of when the app happened to be open.
    ///
    /// Each session is judged only against the sessions *before* it, never
    /// against the ones that followed — anything else is hindsight dressed up
    /// as detection.
    static func priceMoves(
        bars: [PriceBar],
        after date: Date?,
        limit: Int = 10,
        rankThreshold: Double = 0.975,
        deviationThreshold: Double = 2,
        minimumAbsoluteMove: Double = 1.0,
        sourceDetail: String = "Daily bars"
    ) -> [DetectedEventDTO] {
        guard let date else { return [] }
        let sorted = bars.sorted { $0.date < $1.date }
        let returns = dailyReturns(bars: sorted)
        guard returns.count > minimumSample else { return [] }

        var events: [DetectedEventDTO] = []
        for index in minimumSample..<returns.count where returns[index].date > date {
            let session = returns[index]
            let reading = LatestReading(date: session.date, percent: session.percent,
                                        close: session.close, volume: nil, isIntraday: false)
            let priors = returns[..<index].map(\.percent)
            if let event = priceMoveEvent(
                latest: reading, priors: priors,
                rankThreshold: rankThreshold,
                deviationThreshold: deviationThreshold,
                minimumAbsoluteMove: minimumAbsoluteMove,
                sourceDetail: sourceDetail
            ) {
                events.append(event)
            }
        }
        return Array(events.suffix(limit))
    }

    /// Unusual volume on sessions that closed after a given date.
    ///
    /// Same reason as `priceMoves`: judging only the newest session loses a
    /// spike on any day the app was not opened.
    static func volumeAnomalies(
        bars: [PriceBar],
        after date: Date?,
        limit: Int = 10,
        multipleThreshold: Double = 2,
        rankThreshold: Double = 0.95,
        sourceDetail: String = "Daily bars"
    ) -> [DetectedEventDTO] {
        guard let date else { return [] }
        let sorted = bars.sorted { $0.date < $1.date }
        guard sorted.count > minimumSample else { return [] }

        var events: [DetectedEventDTO] = []
        for index in minimumSample..<sorted.count where sorted[index].date > date {
            // Judged against the sessions before it only — the window ends at
            // this bar, so nothing that followed can influence the verdict.
            let window = Array(sorted[...index])
            if let event = volumeAnomaly(
                bars: window, quote: nil,
                multipleThreshold: multipleThreshold,
                rankThreshold: rankThreshold,
                sourceDetail: sourceDetail
            ) {
                events.append(event)
            }
        }
        return Array(events.suffix(limit))
    }

    /// Builds the event for one reading judged against one set of priors.
    /// Shared so the live session and a backfilled one are described in
    /// identical terms and cannot drift apart.
    private static func priceMoveEvent(
        latest: LatestReading,
        priors: [Double],
        rankThreshold: Double,
        deviationThreshold: Double,
        minimumAbsoluteMove: Double,
        sourceDetail: String
    ) -> DetectedEventDTO? {
        guard let measure = AnomalyMeasure.measure(latest.percent, against: priors)
        else { return nil }

        guard abs(latest.percent) >= minimumAbsoluteMove,
              measure.unusualness >= rankThreshold,
              abs(measure.deviations) >= deviationThreshold
        else { return nil }

        let direction = latest.percent >= 0 ? "rose" : "fell"
        var details = [
            "Move: \(Format.signedPercent(latest.percent, precision: 2))",
            measure.comparisonLine,
            "\(Format.multiple(abs(measure.deviations), precision: 1)) the typical "
                + "daily move (median absolute deviation)",
            "\(latest.isIntraday ? "Last" : "Close"): \(Format.currency(latest.close))"
        ]
        if latest.isIntraday {
            details.append("Session still open — this can change before the close")
        }

        return DetectedEventDTO(
            kind: .unusualPriceMove,
            occurredAt: latest.date,
            headline: "Price \(direction) \(Format.percent(abs(latest.percent), precision: 1)) "
                + latest.sessionLabel,
            detailLines: details,
            context: "Size is measured against this security's own prior "
                + "\(measure.sampleSize) closed sessions, not against the market. A move "
                + "this large usually has a specific cause — a filing, an earnings report, "
                + "a sector move, or market-wide news. This detector does not know which.",
            unusualness: measure.unusualness,
            sourceDetails: [sourceDetail],
            derivation: measure.derivation(
                label: latest.isIntraday ? "Change so far today" : "Daily return",
                formatted: Format.signedPercent(latest.percent, precision: 2)
            ),
            isProvisional: latest.isIntraday
        )
    }

    // MARK: - Volume

    /// Volume well above the recent median.
    ///
    /// Measured as a ratio rather than in deviations: volume is strictly
    /// positive and heavily right-skewed, so a symmetric deviation band around
    /// it is the wrong shape and would flag quiet days as anomalies too.
    static func volumeAnomaly(
        bars: [PriceBar],
        quote: QuoteDTO? = nil,
        multipleThreshold: Double = 2,
        rankThreshold: Double = 0.95,
        sourceDetail: String = "Daily bars"
    ) -> DetectedEventDTO? {
        let withVolume = bars.compactMap { bar -> (date: Date, volume: Double)? in
            guard let volume = bar.volume, volume > 0, volume.isFinite else { return nil }
            return (bar.date, volume)
        }
        guard withVolume.count >= minimumSample, let lastClosed = withVolume.last
        else { return nil }

        // Same treatment as price: without the quote, today is invisible. An
        // open session's volume is partial by definition, so the ratio built
        // from it understates the day — said plainly rather than left implied.
        let session = latestReading(bars: bars, quote: quote)?.reading
        let liveVolume: Double? = session.flatMap { reading in
            guard reading.isIntraday, let volume = reading.volume, volume > 0 else { return nil }
            return volume
        }
        let latest: (date: Date, volume: Double)
        let closedPriors: [(date: Date, volume: Double)]
        if let liveVolume, let session {
            latest = (session.date, liveVolume)
            closedPriors = withVolume
        } else {
            latest = lastClosed
            closedPriors = Array(withVolume.dropLast())
        }

        let priors = Array(closedPriors.suffix(referenceWindow).map(\.volume))
        guard priors.count >= minimumSample - 1 else { return nil }
        let median = Statistics.median(priors)
        guard median > 0 else { return nil }

        let multiple = latest.volume / median
        let exceeded = priors.filter { $0 < latest.volume }.count
        let unusualness = Double(exceeded) / Double(priors.count)

        guard multiple >= multipleThreshold, unusualness >= rankThreshold else { return nil }

        return DetectedEventDTO(
            kind: .unusualVolume,
            occurredAt: latest.date,
            headline: "Volume \(Format.multiple(multiple)) the \(priors.count)-session median "
                + (liveVolume != nil ? "so far today" : "on \(Format.dayAndMonth(latest.date))"),
            detailLines: [
                "Volume: \(Format.compact(latest.volume)) shares",
                "Median of prior \(priors.count) sessions: \(Format.compact(median)) shares",
                "Higher than \(exceeded) of those \(priors.count) sessions"
            ] + (liveVolume != nil
                 ? ["Session still open — the day's total will be higher"] : []),
            context: "Elevated volume means more shares changed hands than usual, which "
                + "indicates unusual attention. It carries no direction on its own: heavy "
                + "buying and heavy selling look identical in a volume figure.",
            unusualness: unusualness,
            sourceDetails: [sourceDetail],
            derivation: Derivation(
                formula: "volume ÷ median(prior \(priors.count) sessions)",
                inputs: [
                    .init(name: "volume", value: Format.compact(latest.volume), source: nil),
                    .init(name: "median volume", value: Format.compact(median), source: nil)
                ],
                result: Format.multiple(multiple)
            ),
            isProvisional: liveVolume != nil
        )
    }

    // MARK: - Volatility

    /// A change in how much the price is moving, recent window against prior.
    ///
    /// Reported as annualised realised volatility so the figure is comparable
    /// to how volatility is normally quoted, with both windows named — the
    /// ratio alone would hide that "doubled" can mean 8% to 16% or 60% to 120%.
    static func volatilityShift(
        bars: [PriceBar],
        recentWindow: Int = 20,
        priorWindow: Int = 60,
        riseThreshold: Double = 1.6,
        fallThreshold: Double = 0.625,
        sourceDetail: String = "Daily bars"
    ) -> DetectedEventDTO? {
        let returns = dailyReturns(bars: bars).map(\.percent)
        guard returns.count >= recentWindow + priorWindow else { return nil }

        let recent = Array(returns.suffix(recentWindow))
        let prior = Array(returns.dropLast(recentWindow).suffix(priorWindow))
        guard let recentVol = Statistics.annualisedVolatility(percentReturns: recent),
              let priorVol = Statistics.annualisedVolatility(percentReturns: prior),
              priorVol > 0
        else { return nil }

        let ratio = recentVol / priorVol
        guard ratio >= riseThreshold || ratio <= fallThreshold else { return nil }
        guard let occurredAt = bars.last?.date else { return nil }

        let rising = ratio > 1
        // Mapped onto 0–1 by how far the ratio sits beyond its threshold, and
        // capped: unlike the rank-based scores this is not a sample position,
        // so it must not be presented as one.
        let unusualness = min(1, abs(log(ratio)) / log(3))

        return DetectedEventDTO(
            kind: .volatilityShift,
            occurredAt: occurredAt,
            headline: "Volatility has \(rising ? "risen" : "fallen") — "
                + "\(Format.percent(recentVol, precision: 1)) over \(recentWindow) sessions "
                + "against \(Format.percent(priorVol, precision: 1)) before that",
            detailLines: [
                "Recent \(recentWindow) sessions: \(Format.percent(recentVol, precision: 1)) annualised",
                "Prior \(prior.count) sessions: \(Format.percent(priorVol, precision: 1)) annualised",
                "Ratio: \(Format.multiple(ratio, precision: 2))"
            ],
            context: "Realised volatility is the spread of recent daily returns, annualised. "
                + "A rise means the price has been moving more than it was, which often "
                + "accompanies a period of unresolved news. It says nothing about direction.",
            unusualness: unusualness,
            sourceDetails: [sourceDetail],
            derivation: Derivation(
                formula: "stdev(daily returns) × √252, recent ÷ prior",
                inputs: [
                    .init(name: "recent \(recentWindow)-session",
                          value: Format.percent(recentVol, precision: 1), source: nil),
                    .init(name: "prior \(prior.count)-session",
                          value: Format.percent(priorVol, precision: 1), source: nil)
                ],
                result: Format.multiple(ratio, precision: 2)
            )
        )
    }

    // MARK: - Filings

    /// Filings that appeared since a given moment.
    ///
    /// `since` is when the user last looked, not when the app last ran: the
    /// question being answered is "what is new *to me*". With no prior visit
    /// recorded, nothing is reported — showing a company's entire filing
    /// history as "new" on first open would be false.
    static func newFilings(
        filings: [FilingDTO],
        since: Date?,
        limit: Int = 10
    ) -> [DetectedEventDTO] {
        guard let since else { return [] }
        return filings
            .filter { $0.filedAt > since }
            .sorted { $0.filedAt > $1.filedAt }
            .prefix(limit)
            .map { filing in
                DetectedEventDTO(
                    kind: .newFiling,
                    occurredAt: filing.filedAt,
                    headline: "Form \(filing.formType) filed "
                        + "\(Format.shortDate(filing.filedAt))",
                    detailLines: [
                        "Form: \(filing.formType)",
                        filing.periodOfReport.map {
                            "Period: \(Format.shortDate($0))"
                        } ?? "Period: \(Format.notAvailable)",
                        "Accession: \(filing.accessionNumber)"
                    ],
                    context: FilingSignificance.explanation(for: filing.formType),
                    // A filing either exists or does not; there is no sample to
                    // rank it against, so it carries no unusualness score.
                    unusualness: 0,
                    sourceDetails: ["\(filing.formType) — \(filing.accessionNumber)"],
                    // One link, not two. The document and its index point at
                    // the same filing, and offering both as identically
                    // labelled links is noise rather than a second source.
                    sourceURLs: [filing.primaryDocumentURL ?? filing.filingIndexURL]
                        .compactMap { $0 }
                )
            }
    }

    // MARK: - Helpers

    private static func dailyReturns(bars: [PriceBar]) -> [(date: Date, percent: Double, close: Double)] {
        guard bars.count > 1 else { return [] }
        var result: [(date: Date, percent: Double, close: Double)] = []
        result.reserveCapacity(bars.count - 1)
        for index in 1..<bars.count {
            let previous = bars[index - 1].analysisClose
            let current = bars[index].analysisClose
            guard let percent = ReturnCalculator.simpleReturn(from: previous, to: current)
            else { continue }
            result.append((bars[index].date, percent, current))
        }
        return result
    }
}

/// How far an observation sits from the middle of its own reference sample,
/// expressed two ways: robustly scaled deviations, and position within the
/// sample. Both are reported because neither alone is enough — deviations
/// without rank overstate rarity in a fat-tailed series, rank without
/// deviations cannot distinguish "slightly the largest" from "enormous".
struct AnomalyMeasure: Sendable, Hashable {
    let observation: Double
    let median: Double
    /// Median absolute deviation, rescaled so it is comparable to a standard
    /// deviation for normally distributed data.
    let scale: Double
    /// Size of the window `exceededCount` is drawn from.
    let sampleSize: Int
    /// Prior observations whose absolute deviation was smaller than this one's.
    let exceededCount: Int
    /// Size of the shorter window the dispersion was measured over.
    let scaleSampleSize: Int

    var deviations: Double { (observation - median) / scale }
    /// 0–1. Position within the sample, not a probability.
    var unusualness: Double { Double(exceededCount) / Double(sampleSize) }

    /// What the observation was compared against, in words.
    /// "Larger than 250 of the prior 250" is technically right and reads badly;
    /// the whole point of that reading is that nothing in the window beat it.
    var comparisonLine: String {
        exceededCount == sampleSize
            ? "Larger than every one of the prior \(sampleSize) closed sessions"
            : "Larger than \(exceededCount) of the prior \(sampleSize) closed sessions"
    }

    /// Nil when the sample is too small or has no dispersion to measure
    /// against — a flat series makes every deviation infinite, which would
    /// render as an extraordinary event rather than as an absence of movement.
    ///
    /// Dispersion and rank are drawn from different windows on purpose. Scale
    /// must reflect the *current* regime, so it uses the shorter window. Rank
    /// is a statement about how rare something is, and is better the more
    /// history it sees, so it uses the longer one.
    static func measure(_ observation: Double, against sample: [Double]) -> AnomalyMeasure? {
        let usable = sample.filter(\.isFinite)
        guard observation.isFinite, usable.count >= EventDetector.minimumSample - 1
        else { return nil }

        let scaleSample = Array(usable.suffix(EventDetector.scaleWindow))
        let median = Statistics.median(scaleSample)
        guard let scale = Statistics.robustScale(scaleSample, median: median), scale > 0
        else { return nil }

        let rankSample = Array(usable.suffix(EventDetector.rankWindow))
        let magnitude = abs(observation - median)
        return AnomalyMeasure(
            observation: observation,
            median: median,
            scale: scale,
            sampleSize: rankSample.count,
            exceededCount: rankSample.filter { abs($0 - median) < magnitude }.count,
            scaleSampleSize: scaleSample.count
        )
    }

    func derivation(label: String, formatted: String) -> Derivation {
        Derivation(
            formula: "(observation - median) ÷ (1.4826 × median absolute deviation)",
            inputs: [
                .init(name: label, value: formatted, source: nil),
                .init(name: "sample median",
                      value: Format.signedPercent(median, precision: 2), source: nil),
                .init(name: "robust scale",
                      value: Format.percent(scale, precision: 2), source: nil),
                .init(name: "rank sample", value: "\(sampleSize) sessions", source: nil),
                .init(name: "scale sample", value: "\(scaleSampleSize) sessions", source: nil)
            ],
            result: "\(Format.multiple(abs(deviations), precision: 1)) typical"
        )
    }
}

/// Small statistical primitives, kept separate so they can be tested against
/// known values rather than only through the detectors that use them.
enum Statistics {
    static func median(_ values: [Double]) -> Double {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        return sorted.count.isMultiple(of: 2)
            ? (sorted[middle - 1] + sorted[middle]) / 2
            : sorted[middle]
    }

    /// Median absolute deviation scaled to be comparable with a standard
    /// deviation under normality. Chosen over standard deviation because the
    /// outlier being measured would otherwise inflate the very yardstick used
    /// to judge it.
    static func robustScale(_ values: [Double], median centre: Double? = nil) -> Double? {
        guard !values.isEmpty else { return nil }
        let centre = centre ?? median(values)
        let mad = median(values.map { abs($0 - centre) })
        guard mad > 0, mad.isFinite else { return nil }
        return 1.4826 * mad
    }

    static func standardDeviation(_ values: [Double]) -> Double? {
        guard values.count > 1 else { return nil }
        let mean = values.reduce(0, +) / Double(values.count)
        let variance = values.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(values.count - 1)
        guard variance.isFinite, variance >= 0 else { return nil }
        return variance.squareRoot()
    }

    /// Annualised realised volatility from returns already expressed in percent.
    /// 252 is the conventional count of US trading sessions in a year.
    static func annualisedVolatility(percentReturns: [Double]) -> Double? {
        guard let daily = standardDeviation(percentReturns.filter(\.isFinite)) else { return nil }
        return daily * (252.0).squareRoot()
    }
}

/// Why a given form type is worth reading, from Section 9's "meaningful filing"
/// list. Plain description of what the document contains — never a judgement
/// about what it implies for the security.
enum FilingSignificance {
    static func explanation(for formType: String) -> String {
        switch formType.uppercased() {
        case let type where type.hasPrefix("10-K"):
            "The annual report: audited financial statements, risk factors, and "
                + "management's discussion of the year."
        case let type where type.hasPrefix("10-Q"):
            "The quarterly report: unaudited statements and any material change "
                + "since the last annual report."
        case let type where type.hasPrefix("8-K"):
            "A current report, filed when something material happens between "
                + "scheduled reports. The item numbers say what."
        case "4", "4/A":
            "An insider's transaction in the company's own shares, reportable "
                + "within two business days."
        case let type where type.hasPrefix("S-"):
            "A registration statement — the company proposing to sell securities."
        case let type where type.hasPrefix("DEF 14A"), let type where type.hasPrefix("DEFA"):
            "The proxy statement: executive compensation, board nominees, and "
                + "matters put to a shareholder vote."
        case let type where type.hasPrefix("SC 13"), let type where type.hasPrefix("SC 14"):
            "A beneficial-ownership filing — someone crossing a reporting "
                + "threshold in the company's shares."
        default:
            "Filed with the SEC. Open the document to see what it contains."
        }
    }
}
