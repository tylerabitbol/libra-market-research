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
| 6 | **Intelligence** — What Changed?, Why?, relative analysis, Research Signal, contradictory evidence | 🟡 Detectors, event storage, and the Research feed built; thresholds need calibration against live data |
| 7 | **Personal research** — journal, thesis tracking, saved screens, historical comparisons | ⬜ |
| 8 | **Polish** — performance, caching, error handling, accessibility, UI, testing | ⬜ |

Five product areas: Dashboard, Watchlist, Security Detail, Research/Investigation,
Settings. Dashboard, Watchlist, Security Detail and Research are built; Screener
is still a placeholder.

## Provider capabilities (measured against live keys, not assumed)

| Source | Works | Does not work |
|---|---|---|
| **Finnhub** (free) | `quote`, `profile2`, `metric` (133 metrics + 39 annual / 41 quarterly historical ratio series), `recommendation`, `stock/earnings`, `insider-transactions`, `company-news` | `candle`, `eps-estimate`, `revenue-estimate`, `price-target`, `split`, index symbols — all 403 |
| **Tiingo** (free) | Adjusted daily OHLCV back to 1980, ETFs included. 50 req/**hour**, 1000/day, 500 symbols/month | Intraday |
| **FRED** | `SP500`, `DJIA`, `NASDAQCOM`, `VIXCLS`, macro series. Free, generous limits | Sector indexes, intraday |
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
- **Detector thresholds are uncalibrated.** Observed live: NVDA at **+8.74%**
  on 2026-08-27 produced no event, while the unit tests fire correctly on
  synthetic data. Cause not yet isolated. Candidates, in order of suspicion:
  (a) the rank threshold of 0.975 needs 59 of 60 prior sessions to be smaller,
  which a volatile name can fail; (b) MAD over 60 sessions of a high-beta stock
  gives a scale large enough that 8.74% falls under 3 deviations; (c) the quote
  is not being read as a new session because Tiingo publishes a partial bar
  dated today, so the same-day guard treats it as already counted. A
  DEBUG-only `DETECT …` log line in `SecurityDetailViewModel.detectChanges`
  prints the reading, prior count, scale, deviations and rank — read it with
  `log show --predicate 'subsystem == "com.tylerabitbol.vantage"'` against a
  live symbol to settle which. **Thresholds must be set from real distributions
  before this feature is trustworthy**; right now a silent detector is
  indistinguishable from a calm market, which is the failure mode that matters.
- **Detection only ever examines the latest session.** A large move on a day
  the user did not open the app is never recorded, even though the bars for it
  are stored. A backfill pass over stored bars would fix this.
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
