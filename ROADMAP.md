# Vantage — Roadmap and Working Context

Durable record of the plan, current state, and the decisions behind it.
Written so work can resume without the conversation that produced it.

## Product goal

A personal investment **research** tool that answers "what changed, why did it
change, how unusual is it, and what should I investigate?" — not "the stock is
up 3%". It never makes buy/sell recommendations or gives personalised financial
advice. Calculations are deterministic Swift; AI is confined to summarising and
organising data already fetched.

## Development phases

From the original specification. Status as of the latest commit.

| # | Phase | Status |
|---|-------|--------|
| 1 | **Foundation** — project architecture, SwiftData models, API abstraction, networking, secrets, navigation, basic dashboard | ✅ Complete |
| 2 | **Market data** — quotes, historical prices, volume, watchlist, charts | ✅ Complete — providers, watchlist with search/add/persistence, and range charts |
| 3 | **Fundamentals** — financial statements, valuation, profitability, balance sheet, historical snapshots | ✅ Complete — XBRL extraction, valuation percentiles, Security Detail UI, and append-only persistence |
| 4 | **SEC** — filings, Form 4, filing history, meaningful filing detection | 🟡 Provider + filings done; Form 4 XML parsing outstanding |
| 5 | **Analyst / news** — revisions, news, event detection | 🟡 Ratings + earnings surprises available; estimate revisions blocked by tier |
| 6 | **Intelligence** — What Changed?, Why?, relative analysis, Research Profile, contradictory evidence | 🟡 Price/volume/volatility and fundamental detectors, read-through store, backfill, Research feed and market attribution (beta-adjusted) built; sector leg, Research Profile and contradictory evidence outstanding |
| 7 | **Personal research** — journal, thesis tracking, saved screens, historical comparisons | ⬜ |
| 8 | **Polish** — performance, caching, error handling, accessibility, UI, testing | ⬜ |

Five product areas: Dashboard, Watchlist, Security Detail, Research/Investigation,
Settings. Dashboard, Watchlist, Security Detail and Research are built; Screener
is still a placeholder.

**286 tests**, all passing. Every wrong number caught in the last four commits
was found by looking at rendered output on a real symbol — the suite stayed
green throughout. Treat a screen check as part of "done", not optional polish;
see *Build and verify* below.

**Next, in priority order**: sector-relative attribution (wire the declared
sector ETFs into `RelativeAnalysis` alongside the market leg, and the built but
uncalled `ReturnCalculator.relativePerformance` into Security Detail), then
watchlist intelligence, then contradictory evidence and the Research Profile,
then Form 4 parsing. Working plan in
`~/.claude/plans/this-is-the-current-pure-storm.md`.

## Provider capabilities (measured against live keys, not assumed)

| Source | Works | Does not work |
|---|---|---|
| **Finnhub** (free) | `quote`, `profile2`, `metric` (133 metrics + 39 annual / 41 quarterly historical ratio series), `recommendation`, `stock/earnings`, `insider-transactions`, `company-news` | `candle`, `eps-estimate`, `revenue-estimate`, `price-target`, `split`, index symbols — all 403 |
| **Tiingo** (free) | Adjusted daily OHLCV back to 1980, ETFs included. 50 req/**hour**, 1000/day, 500 symbols/month | Intraday |
| **FRED** | `SP500`, `DJIA`, `NASDAQCOM`, `VIXCLS`, macro series. Free, generous limits. Also the market leg for beta and attribution | Sector indexes, intraday |
| **SEC EDGAR** | Ticker→CIK map, submissions/filing history. **No API key**; requires identifying User-Agent, ~10 req/sec | Form 4 parsing not yet implemented |

Verify all four at any time: launch with `-VantageSelfTest` and read the log
(`subsystem == "com.tylerabitbol.vantage"`). Credentials come from the Keychain,
so they never enter a command line.

## Decisions worth not re-litigating

- **Append-only persistence.** Every record carries `observedAt` separately from
  the period it describes. Restatements insert new rows. This is what makes
  "what did this look like three months ago" possible and cannot be retrofitted.
- **`Claim` / `ClaimKind` as a type.** FACT / CALCULATION / INTERPRETATION /
  HYPOTHESIS is enforced by the type system, not by UI convention — a statement
  cannot be constructed without declaring its epistemic status.
- **Absent data renders "Not available", never 0.** Fabricating a value is worse
  than showing nothing.
- **Real indexes from FRED, not ETF proxies**, for S&P 500 / Dow / Nasdaq / VIX.
  Sectors remain ETF proxies and are labelled as such. The VIX-futures ETF was
  removed: those futures decay and track the index only loosely.
- **`FinancialFactRecord` is one row per reported figure**, not a wide statement
  object, so a concept an issuer doesn't report stays genuinely absent.
- **Fetch only what is displayed.** Sector tiles show a daily change only, so they
  cost no history request. Ignoring this once cost an 8-minute dashboard stall.
- **XBRL periods are classified by duration, not by the `fp` label.** EDGAR files
  cumulative year-to-date figures under the same tag as discrete quarters; a Q3
  10-Q carries both a three-month and a nine-month revenue. Reading the latter as
  a quarter makes Q3 look ~3x Q2. Cumulative rows are filtered out of extraction.
- **Valuation percentiles compare a company against its own past**, not against
  other companies, and require a minimum sample of 8 observations rather than
  returning a weak percentile dressed as a strong one.
- **Finnhub reports the same concept at two different scales.** `grossMarginTTM`
  is 48.65 in the current metrics block while the `grossMargin` series is 0.4622.
  Ranking one against the other pins every margin at the 100th percentile
  forever — authoritative-looking and meaningless. `ValuationMetric` declares
  both keys and the scaling that reconciles them; never compare the two blocks
  without normalising.
- **XBRL facts are deduplicated by the period they describe, not by `fy`.** That
  field is the *filing's* fiscal context, so two distinct periods can share it
  and one gets silently dropped — which showed up as a 0.0% revenue growth year.
- **The watchlist fetches quotes only, never history.** One bars request per row
  would exhaust Tiingo's hourly quota on a list of any size.
- **A multiple that cannot be ranked is shown and flagged, never silently
  omitted.** A negative P/E ranked naively lands at the 0th percentile and reads
  as "near the low end of its own range" — which scans as cheap when it means
  losses. Omitting it instead would hide that the company is loss-making.
- **A value taken from history is excluded from its own ranking.** Counting an
  observation in the sample it is measured against inflates the percentile.
- **Scale factors are validated against the data, not assumed.** A ratio series
  that is already in percent must not be multiplied again; a silent 100x error
  is the worst outcome available in this app.
- **SEC identifiers are parsed strictly.** `Int(cik) ?? 0` builds a well-formed
  request for CIK 0000000000 — a silent wrong question rather than a failure.
- **The store is read before the network is asked.** `SnapshotStore` was
  write-only for its whole existence: bars, facts and filings were recorded on
  every visit and never read back, so each visit re-fetched five years of
  history it already held, and a security opened a hundred times still showed
  nothing offline. Hydration now fills the page from disk first, and a section
  whose held copy is still fresh costs no request at all.
- **Fundamentals are compared year-over-year, never quarter-over-quarter.**
  Most businesses are seasonal. A retailer's Q4 gross margin is not comparable
  to its Q3, and a detector built on consecutive quarters fires every December
  and reports the calendar as a change.
- **The year-earlier period is matched by date, not by counting back four.** A
  52/53-week fiscal calendar moves the closing date between years, and a
  concept an issuer skipped for one quarter would otherwise pair a period
  against the wrong year with no signal that it had.
- **Cash flow is compared in percentage points, not percent.** Free cash flow
  crosses zero regularly, and a percentage change through zero is either
  infinite or sign-flipped: a company going from -$10M to +$10M has improved,
  and "-200%" describes that improvement as a collapse. Margins in pp do not
  have this failure.
- **A fundamental event is dated to when it was filed, not to the period it
  covers.** A June quarter disclosed in August is news in August; dating it to
  June files it behind price events the user has already seen and defeats "what
  changed since I last looked".
- **`AnomalyMeasure` windows are parameters, not constants.** Price history is
  250 daily sessions; fundamentals are roughly 20 reported quarters. The same
  rank-and-MAD machinery serves both, but a 40-observation minimum applied to
  quarterly filings would mean saying nothing for a decade.
- **Restatement detection merges the freshly fetched facts with the stored
  ones.** Detection runs before persistence, so reading the store alone would
  delay every amendment by one visit — it would land, be stored, and only be
  noticed the next time the page was opened.
- **Never edit navigation to capture a screenshot.** Use `-VantageOpenSymbol`.
  A hand-edit for a screenshot once reached a commit and left the Watchlist tab
  wired to a hardcoded symbol.
- **Unusualness is a rank, never a probability.** A 3σ day is a 1-in-370 event
  only under a normal distribution, and daily equity returns are not normal —
  3σ days arrive several times a year. Detectors report position within the
  observed sample ("larger than 248 of the past 250 sessions"), which is both
  true and checkable. Converting it to a percentage chance would be a
  fabricated statistic wearing a lab coat.
- **Anomalies are measured with median and MAD, not mean and standard
  deviation.** The spike being measured also inflates a standard deviation
  computed over the window containing it, shrinking its own score and hiding
  the *next* spike behind a permanently widened band.
- **Detection reads the quote, not only the bars.** Daily bars end at the
  previous close, so a detector reading bars alone is blind to today — the one
  day the user is actually asking about.
- **An open session is provisional and is never stored.** A +8% reading at
  midday can close at +2%; the permanent record must not keep the midday figure
  as what happened. `SnapshotStore.record(events:)` filters provisional events
  itself so no future caller can store one by forgetting.
- **A first visit reports nothing as new.** With no prior visit there is no
  "since", and presenting a company's whole filing history as new would be false.
- **The visit stamp is written only after a load completes**, and always read
  before it is written. Stamping first would make every visit report "nothing
  changed"; stamping a cancelled load would skip past changes the user never saw.
- **Rank and dispersion come from different windows.** Scale uses 60 sessions
  so it reflects the *current* volatility regime; rank uses 250 because rank
  costs nothing extra over more data and "the largest move in a year" is a far
  more useful statement than "the largest in 60 sessions".
- **Rank is the primary gate; deviations are only a guard.** These were once
  ANDed at equal strength, and an 8.74% NVDA day that was *the largest move in
  the whole reference window* was rejected for missing a 3.0-sigma cut by 0.08.
  A high-volatility name has a large MAD, so a hard sigma gate makes the
  detector least sensitive exactly where large moves matter most.
- **Candidate XBRL tags are merged by period, never first-tag-wins.** Issuers
  change the tag they report a concept under. Taking the first candidate that
  returns anything truncates the history at the changeover — NVIDIA's revenue
  showed FY2021 and FY2022 only, hiding the four years that made the company
  what it is, and the chart just looked like it started late. Merging in
  priority order fills the gap without double-counting a period reported under
  two tags.
- **Derived annual figures key on `periodEnd`, never `fiscalYear`** — the same
  rule as XBRL extraction, which the view model was violating. It paired free
  cash flow with dates two years out and rendered two different years as two
  identical "2022" rows.
- **Attribution is beta-adjusted, and the beta is shown.** A raw
  security-minus-market difference understates the market's contribution for a
  high-beta name — NVIDIA's computed beta is ~2.2, so a +0.3% market day
  accounts for more than twice what the raw difference credits it with. The
  model is one-factor, named as such in the text, and the beta carries its own
  derivation so it can be rejected.
- **Beta is fitted over ~2 years, not all available history.** Beta is not a
  constant. A five-year fit averages a sensitivity the company no longer has
  into the one being applied today, and looks more rigorous while being less
  informative.
- **Aligned returns must span identical sessions.** Matching on the end date
  alone is not enough: after a holiday present in one calendar and not the
  other, one series' "daily" return covers two days and the other covers one,
  and the difference lands in the residual reading as company-specific. Both
  legs must share a previous session too.
- **The market/company split is never rendered as a share of one move.** The
  two parts can point in opposite directions — a stock can rise on a falling
  market — and any percentage-of-the-move framing breaks down entirely there.
- **Percentage points ≠ percent.** Relative performance reports `pp` throughout.
- **No secrets in source.** Keys live in the Keychain, entered by the user.
  `DeveloperOptions` can seed from `VANTAGE_*` environment variables, gated on a
  DEBUG build plus an explicit `-VantageSeedKeys` argument.

## Debug launch arguments

Debug builds only, each gated on an explicit argument so nothing fires by accident.

| Argument | Effect |
|---|---|
| `-VantageSelfTest` | Keychain round-trip, live connection test per provider, and end-to-end valuation/fundamentals checks; results go to the unified log |
| `-VantageSeedKeys` | Seeds the Keychain from `VANTAGE_*` environment variables |
| `-VantageSeedWatchlist` | Adds AAPL, NVDA, COST to the watchlist without touching existing entries |
| `-VantageCaptureFixtures` | Writes a trimmed `companyfacts` payload into the app container for use as a test fixture |
| `-VantageBackdateVisits 45` | Moves every visit stamp back that many days, so "what changed since you last looked" can be exercised without waiting days between runs. Moves the reference point only — no event is fabricated |
| `DETECT` log line | Not an argument: a DEBUG-only `.notice` in `detectChanges` reporting what the detectors saw and why they stayed silent |
| `-VantageOpenSymbol AAPL` | Opens straight to a security's detail page. Added because capturing that screen used to mean hand-editing `RootView`, and one such edit reached a commit |

## Known gaps

- **Estimate revisions** (spec §8) need a paid Finnhub tier. Rating changes and
  earnings surprises work. The gap surfaces as "not in your plan", never an
  empty chart.
- **Form 4 parsing** — XML, fetched per-document. Currently refuses rather than
  returning an empty array, which would read as "no insider trades".
- **Intraday index levels** — FRED is end-of-day. An intraday ETF overlay is
  deliberately deferred.
- **Volatility shifts are not backfilled.** Price moves and volume are; a
  regime change that began on a day the app was not opened is still reported
  only from the current window. `EventDetector.priceMoves(bars:after:)` is the
  pattern to follow.
- **A stored event loses its derivation.** `DetectedEvent.snapshot` rebuilds the
  DTO without one, so an event read back from the store renders through
  `headlineClaim` as a FACT rather than the CALCULATION it was — the app
  mislabelling its own epistemic status, which Section 24 exists to prevent.
  Affects the Research feed, where every card is read from the store.
  Persisting it needs `Derivation` to become `Codable`.
- **Only the most recent reported period is judged.** A second filing arriving
  inside one gap between visits leaves the older of the two unreported. Price
  moves are backfilled across the gap; fundamentals are not.
- **Attribution is market-only.** Sector-relative comparison (spec §7) needs a
  sector benchmark per security; the sector ETFs are declared but not wired to
  the detail page. A move the market does not explain is currently attributed
  to "this company or its industry" without separating the two.
- **Tiingo's 50 req/hour** is the binding constraint on any screen wanting
  history for many symbols. Budget accordingly; the rate limiter now refuses
  past a wait budget rather than blocking silently.

## Providers evaluated and declined

- **sec-api.io** — free tier is 100 calls total, then $49/mo. EDGAR is free and
  primary. Only unique value is 10-K Item 1A risk-factor extraction; revisit if
  risk-factor diffing becomes a priority.
- **Alpaca** — free tier is **IEX feed only, ~2.5% of US volume**. Unsuitable as
  the history source: volume-anomaly detection (spec §4) computed on 2.5% of
  real volume would be meaningless. Attractive rate limit (200/min vs Tiingo's
  50/hour) and it offers intraday, so it is a reasonable *supplement* for
  intraday charts with an explicit caveat. SIP (full volume) is $99/mo. Needs an
  API key ID **and** secret.
- **Stooq** — now behind a JavaScript proof-of-work bot check. Not pursued.

## Build and verify

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodegen generate                      # .xcodeproj is generated, not committed
xcodebuild -project Vantage.xcodeproj -scheme Vantage \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```

Edit `project.yml`, not the Xcode project — regenerating discards UI changes to
build settings. Signing must stay enabled (`DEVELOPMENT_TEAM: 3RSPVBC57V`): an
unsigned build carries no entitlements, and the Keychain then fails with -34018.

Provider tests decode real captured payloads through the full mapping path via a
stubbed `URLSession`; no test touches the network.

EDGAR fixtures cannot be captured with `curl` without putting a contact address
in the User-Agent, so `-VantageCaptureFixtures` has the app fetch and trim them
into its own container instead; lift them out with `simctl get_app_container`.
The `companyfacts` fixture was captured that way. The two `sec_submissions_*`
fixtures remain hand-authored, since their value is exercising ragged and
well-formed parallel arrays rather than reproducing a real response.
