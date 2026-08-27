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
| 2 | **Market data** — quotes, historical prices, volume, watchlist, charts | 🟡 Providers done; watchlist and charts outstanding |
| 3 | **Fundamentals** — financial statements, valuation, profitability, balance sheet, historical snapshots | 🟡 XBRL extraction + historical valuation percentiles done; persistence and UI outstanding |
| 4 | **SEC** — filings, Form 4, filing history, meaningful filing detection | 🟡 Provider + filings done; Form 4 XML parsing outstanding |
| 5 | **Analyst / news** — revisions, news, event detection | 🟡 Ratings + earnings surprises available; estimate revisions blocked by tier |
| 6 | **Intelligence** — What Changed?, Why?, relative analysis, Research Signal, contradictory evidence | ⬜ |
| 7 | **Personal research** — journal, thesis tracking, saved screens, historical comparisons | ⬜ |
| 8 | **Polish** — performance, caching, error handling, accessibility, UI, testing | ⬜ |

Five product areas: Dashboard, Watchlist, Security Detail, Research/Investigation,
Settings. Navigation shell exists for all five; Dashboard and Settings are built.

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
- **Percentage points ≠ percent.** Relative performance reports `pp` throughout.
- **No secrets in source.** Keys live in the Keychain, entered by the user.
  `DeveloperOptions` can seed from `VANTAGE_*` environment variables, gated on a
  DEBUG build plus an explicit `-VantageSeedKeys` argument.

## Known gaps

- **Estimate revisions** (spec §8) need a paid Finnhub tier. Rating changes and
  earnings surprises work. The gap surfaces as "not in your plan", never an
  empty chart.
- **Form 4 parsing** — XML, fetched per-document. Currently refuses rather than
  returning an empty array, which would read as "no insider trades".
- **Intraday index levels** — FRED is end-of-day. An intraday ETF overlay is
  deliberately deferred.
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
