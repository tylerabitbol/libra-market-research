import Foundation

/// The arithmetic behind the intraday chart, kept apart from any view model.
///
/// These were computed properties on `SecurityDetailViewModel` until the
/// dashboard's benchmarks needed the same chart. They are pure functions of a
/// bar series and a range — no state, no actor — so putting them here lets a
/// second screen draw an identical chart without inheriting a security's
/// loading machinery, and lets the session and axis rules be tested directly.
///
/// Nothing downstream of this treats a position as a time. The positional
/// layout exists because a wall-clock axis gave five sixths of the width to
/// hours in which nothing traded.
enum ChartSeriesBuilder {
    /// Sessions are bounded by the exchange's day, not the device's. Grouping
    /// on local midnight would split one session in two for anyone east of
    /// New York.
    static let marketCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        return calendar
    }()

    /// The intraday bars a range actually draws: regular hours only, trimmed
    /// to the last N sessions.
    ///
    /// Counted in sessions, not in hours. "1D" as a rolling 24 hours began
    /// mid-afternoon the previous day and opened with a straight line across
    /// the overnight gap; "5D" as five calendar days reached back to Wednesday
    /// and drew three sessions.
    static func regularHoursBars(_ bars: [PriceBar], range: ChartRange) -> [PriceBar] {
        let series = bars.filter(isRegularHours).sorted { $0.date < $1.date }
        guard let wanted = range.intradaySessions else { return series }
        let days = Set(series.map { marketCalendar.startOfDay(for: $0.date) })
        let kept = Set(days.sorted().suffix(wanted))
        return series.filter { kept.contains(marketCalendar.startOfDay(for: $0.date)) }
    }

    /// The series laid out end to end, one position per bar.
    static func points(_ bars: [PriceBar]) -> [ChartPoint] {
        bars.enumerated().map { index, bar in
            ChartPoint(id: index, date: bar.date, close: bar.analysisClose,
                       session: marketCalendar.startOfDay(for: bar.date))
        }
    }

    /// The line broken into the runs the chart strokes differently: one per
    /// session, and one across each break between them.
    ///
    /// The overnight runs hold exactly two points, one position apart, so the
    /// move between a close and the next open is drawn at the width of a
    /// single bar. That is the whole argument for drawing it at all — at that
    /// width it cannot be mistaken for a gradual drift, and leaving it out
    /// turned one instrument into five floating fragments.
    static func segments(_ points: [ChartPoint]) -> [ChartSegment] {
        guard !points.isEmpty else { return [] }

        var traded: [ChartSegment] = []
        var run: [ChartPoint] = []
        for point in points {
            if let first = run.first, first.session != point.session {
                traded.append(ChartSegment(id: "session-\(first.session.timeIntervalSince1970)",
                                           kind: .traded, points: run))
                run = []
            }
            run.append(point)
        }
        if let first = run.first {
            traded.append(ChartSegment(id: "session-\(first.session.timeIntervalSince1970)",
                                       kind: .traded, points: run))
        }

        let overnight = zip(traded, traded.dropFirst()).compactMap { earlier, later
            -> ChartSegment? in
            guard let close = earlier.points.last, let open = later.points.first else {
                return nil
            }
            return ChartSegment(id: "overnight-\(close.id)", kind: .overnight,
                                points: [close, open])
        }
        return traded + overnight
    }

    /// Where to label the axis: each hour on 1D, each session otherwise.
    ///
    /// Positions are not evenly spaced in time — a session with thin trading
    /// holds fewer bars — so the labels are placed on the bars themselves
    /// rather than computed by stride.
    static func axisTicks(_ points: [ChartPoint], range: ChartRange) -> [ChartAxisTick] {
        guard !points.isEmpty else { return [] }

        if range == .oneDay {
            var ticks: [ChartAxisTick] = []
            var seen: Int?
            for point in points {
                let parts = marketCalendar.dateComponents([.hour, .minute], from: point.date)
                guard let hour = parts.hour, hour != seen else { continue }
                seen = hour
                // The session opens at 9:30, and labelling that bar "9 AM"
                // both misstated it by half an hour and crowded the 10 AM
                // label beside it. A tick has to sit near the hour it names.
                guard (parts.minute ?? 0) <= 10 else { continue }
                ticks.append(ChartAxisTick(id: point.id, label: hourLabel(point.date)))
            }
            return ticks
        }

        var ticks: [ChartAxisTick] = []
        var seen: Date?
        for point in points where point.session != seen {
            seen = point.session
            ticks.append(ChartAxisTick(id: point.id, label: sessionLabel(point.date)))
        }
        return ticks
    }

    /// Axis labels are in market time, matching the session the bars belong
    /// to. A reader in another time zone would otherwise see a session that
    /// appears to open at 6:30.
    static func hourLabel(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(timeZone: marketCalendar.timeZone)
            .hour(.defaultDigits(amPM: .abbreviated)))
    }

    static func sessionLabel(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(timeZone: marketCalendar.timeZone)
            .month(.abbreviated).day())
    }

    /// Whether a bar falls inside the regular session, 9:30 to 16:00 New York.
    ///
    /// Alpaca returns extended-hours bars, and pre-market IEX trading is thin
    /// enough that a single 7 a.m. print bridged to the open as one long
    /// diagonal — the widest move on the chart, and an artefact of two sparse
    /// bars rather than a move anyone could have traded.
    static func isRegularHours(_ bar: PriceBar) -> Bool {
        let parts = marketCalendar.dateComponents([.hour, .minute], from: bar.date)
        guard let hour = parts.hour, let minute = parts.minute else { return false }
        let minutes = hour * 60 + minute
        return minutes >= 9 * 60 + 30 && minutes < 16 * 60
    }
}
