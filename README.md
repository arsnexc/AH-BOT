# AhProfit

AhProfit is a **client-only Fabric mod** for read-only DonutSMP Auction House research. It polls documented public auction-listing and recent-transaction endpoints, keeps a local SQLite history, estimates conservative resale values from completed sales, and ranks hypothetical flips against a manually entered bankroll.

**It never buys, lists, bids, transfers, clicks auction menus, or sends trade commands.** Recommendations are informational only; every trade is manual and all prices, fees, liquidity estimates, and market conditions must be checked in-game.

## Features

- Background, single-worker polling; no network or database work runs on Minecraft's render/client command thread.
- Serialized HTTPS requests with a one-second minimum gap, bounded JSON parsing, retry handling, exponential jittered backoff, server `Retry-After` handling, and an explicit manual-refresh cooldown.
- Local SQLite persistence for active listings, listing observations, completed sales, price estimates, and per-endpoint/page successful-fetch timestamps.
- Variant-aware item fingerprints. Incomplete/unknown equipment attributes and incomplete container contents are not compared as if they were identical.
- Resale estimates are based on exact-variant completed sales. Recent weighted medians, median absolute deviation, sample thresholds, freshness, active competition, volatility, confidence, and liquidity proxies are surfaced rather than hidden.
- Active asks can only lower/cap a completed-sales estimate; asking prices never establish or increase the estimated resale value.
- Recommendations are filtered using net profit after explicit fee/risk haircuts, ROI and discount floors, current data freshness, and bankroll constraints.
- A hypothetical capital-allocation plan applies total, per-item, per-category, and per-listing caps. It does not reserve or spend money.
- A small HUD, deduplicated material-change alerts, and detailed local status/recommendation output.
- API credentials are isolated from ordinary settings, excluded from Git, written atomically, and restricted to owner read/write permissions where the filesystem supports POSIX permissions.

## Installation and first run

1. Build the mod (see [Build and verification](#build-and-verification)) and put `build/libs/ahprofit-1.0.0.jar` in the `mods` directory of a Minecraft **client** instance that has Fabric Loader and Fabric API installed.
2. Start the client once. AhProfit creates `config/ahprofit/settings.json`, `config/ahprofit/credentials.json`, and `config/ahprofit/market.sqlite` inside that game instance's config directory.
3. In DonutSMP, create an API key using the server's `/api` command. Add it to `config/ahprofit/credentials.json`:

   ```json
   {
     "apiKey": "YOUR_PRIVATE_DONUTSMP_API_KEY"
   }
   ```

   Keep the key private. Do not put it in `settings.json`, a screenshot, a public log, or a bug report. The credentials file is ignored by Git. If the key is rejected, generate a replacement with `/api`, update the file, and run `/ahprofit refresh` so AhProfit reloads it.
4. Enter your current in-game balance estimate, for example:

   ```text
   /ahprofit budget 2000000
   ```

   The balance is stored locally and **is not fetched or verified** by AhProfit. Re-enter it after spending/earning or at least once per day. Unset or stale (over 24 hours old) balances do not produce capital recommendations.
5. Run `/ahprofit` to start the scanner. It is initially off. Use `/ahprofit status` for freshness, bankroll limits, estimated opportunities, rationale, and risk/data flags. `/ahprofit` or `/ahprofit toggle` stops recurring polling; `/ahprofit refresh` queues one throttled manual refresh without enabling recurring polling.

AhProfit needs a working server-provided API key and live API access to build current market data. Cached records are retained locally between sessions; no cached estimate should be treated as a live quote.

## Local commands

These use Fabric's **client command API**, are registered on the client, and require an attended/user-entered command. AhProfit contains no code that sends these command strings as server trade commands.

| Command | Effect |
| --- | --- |
| `/ahprofit` or `/ahprofit toggle` | Toggle recurring background scanning and show the current balance estimate, API state, data freshness, analyzed-listing count, and qualified-opportunity count. |
| `/ahprofit status` | Show detailed API/cache status, freshness, manual bankroll and caps, hypothetical exposure, top recommendations, evidence, and warnings. |
| `/ahprofit budget <amount>` | Set the manual balance estimate. Accepts values such as `2000000`, `2,000,000`, or `$2,000,000`. |
| `/ahprofit reserve <amount>` | Set an additional minimum protected reserve. The percentage reserve still applies; the effective reserve is the greater of the two. |
| `/ahprofit minprofit <amount>` | Set the absolute net-profit floor. The active floor is the greater of this value and the configured percentage-of-balance floor. |
| `/ahprofit minroi <percent>` | Set the minimum net ROI percentage, e.g. `25` or `25%`. |
| `/ahprofit refresh` | Queue a one-shot background refresh, subject to a 15-second local cooldown and any upstream backoff. Does not turn on recurring scans. |
| `/ahprofit help` | Show quick setup and safety notes. |

A successful balance change re-scores cached records in the background without making an extra API request. A `/ahprofit refresh` is required to refresh market data on demand.

## API use and rate controls

The production transport is pinned to HTTPS host `api.donutsmp.net`, disables redirects, and sends the API key only as an `Authorization: Bearer …` header. AhProfit calls only:

| Endpoint | Usage |
| --- | --- |
| `GET /v1/auction/list/{page}` | Sends the documented `{"sort":"lowest_price"}` request body. Page 1 is refreshed each cycle; subsequent pages are discovered/rotated. Page discovery stops at a documented empty-page response or the specific invalid-page HTTP 500 message, then probes the known boundary periodically for growth. |
| `GET /v1/auction/transactions/{page}` | Page 1 (recent sales) is refreshed each cycle; pages 2–10 rotate over subsequent cycles. The client rejects transaction page values outside 1–10. |

The client does **not** call a balance/statistics, account, purchase, bid, listing, or other mutation endpoint. The displayed balance is manual, not API-verified. The transaction history available to the mod is limited to the documented ten pages; it is not a complete archive of all market sales.

Request and failure controls:

- Requests are serialized with at least **1 second** between starts (at most 60 starts/minute from this client). The default scan interval is 20 seconds; the configuration bounds it to 10–3,600 seconds.
- Each cycle refreshes listing page 1, at most one discovery/rotation page, transaction page 1, and one rotating historical transaction page. This normally means 3–4 spaced requests per cycle, not a burst of every page.
- HTTP 401/403 pauses automatic retries until credentials are reloaded by `/ahprofit refresh`. HTTP 429 honors `Retry-After` (bounded to one hour); transient network/server/schema failures use exponential jittered backoff up to five minutes.
- A manual refresh is limited to one request sequence per 15 seconds and does not bypass an active server backoff.
- Malformed JSON, missing required fields, oversize responses, null/malformed-only empty pages, timeouts, and upstream outages do not replace the last valid cached page. Malformed individual entries are skipped and counted; duplicate entries are deduplicated.
- A previous valid snapshot is kept after request/database failures. It is explicitly labeled by API state and marked stale after the configured data-age limit (default 300 seconds). The analysis excludes expired or over-age active listings; historical-only data is not a live quote.

The remote API controls its own quotas and availability. AhProfit's one-second client throttle is a conservative local cap, **not a claim about or guarantee of DonutSMP's server-side rate limit**.

## Pricing, confidence, and bankroll policy

All calculations use the API's total price and item quantity to derive a unit price; a stack is not compared to a single unit at its total stack price.

- The normal analysis window is the last seven days of cached completed sales, matched by an exact, quantity-independent item fingerprint. At least **5** comparable sales are required by default. A lower-volume estimate is marked low-confidence and cannot qualify.
- A recency-weighted median is clipped around the seven-day median using the median absolute deviation. The conservative unit value is no higher than the weighted median, seven-day median, or (when at least three sales exist) 24-hour median. The lowest fresh exact-variant active ask may lower that value further.
- Confidence and liquidity are heuristics based on sample size, sale recency, price dispersion, competing listings, recent sale counts, and visible supply. The `days to clear`/supply figure is only a transaction-volume proxy; it does not guarantee an item will sell within that time.
- Estimated net profit subtracts a default **5% assumed selling-fee haircut** and a risk buffer (default 5%, increased by observed volatility). DonutSMP's fee/tax is not confirmed by these read endpoints; both values are configurable assumptions, not market facts.
- Defaults require minimum **20% net ROI**, **30% discount** to the conservative resale estimate, and a minimum net profit of the greater of the configured absolute floor and **2% of the current manual balance**.
- A balance is considered stale if it is unset, older than 24 hours, or has a future timestamp. A stale balance is clearly warned about and disables capital recommendations until refreshed with `/ahprofit budget`.
- Let `B` be the manual balance and `D` be deployable capital after reserve. Defaults reserve the greater of **20% of B** and the configured minimum reserve. The max spend for one listing is `min(15% of B, 25% of D)`. The hypothetical portfolio is capped at **50% of D** total, **20% of D** per exact item variant, and **35% of D** per broad category. These independent limits mean the remaining deployable balance is not necessarily fully allocated.
- The plan ranks up to five candidates. It is a suggestion only: AhProfit does not track inventory/other holdings, reserve capital in-game, or know whether the player has already placed a trade.

Settings and defaults live in `config/ahprofit/settings.json`; `config/ahprofit.example.json` is a reference copy. The API key is separate in `config/ahprofit/credentials.json` (see `config/credentials.example.json`). Local price history is in `config/ahprofit/market.sqlite`. SQLite retains observations and transactions for up to 90 days, prunes old active listings after one day, and analyses only the recent seven-day sales window. The entire config directory is local to the Minecraft game instance; back it up securely if you want to preserve history.

## Build and verification

### Target toolchain

| Component | Project target |
| --- | --- |
| Minecraft | `26.2` |
| Java bytecode / runtime | Java `25` |
| Fabric Loader | `0.19.5` or newer |
| Fabric API | `0.161.0+26.2` |
| Fabric Loom | `1.18-SNAPSHOT` |
| Gradle wrapper | `9.7.1` |
| SQLite JDBC | `3.53.4.0` (nested in the mod JAR) |
| JUnit Jupiter | `6.1.3` |

Build on a machine with the matching Java 25 toolchain and normal access to the Gradle, Fabric, Mojang, and Maven artifact repositories:

```sh
./gradlew test
./gradlew build
```

The expected remapped runtime artifact is:

```text
build/libs/ahprofit-1.0.0.jar
```

The GitHub Actions workflow at `.github/workflows/build.yml` runs tests and builds this JAR on pushes, pull requests, and manual dispatches. It uploads the runtime mod JAR (not the sources JAR) as an Actions artifact named `ahprofit-mod-<commit SHA>`; download it from the corresponding workflow run's **Artifacts** section. If the build fails, it also publishes Gradle error details and a short-lived diagnostic-log artifact.

### Results in this workspace

**Not verified here. No compiled JAR has been produced.** The available sandbox has no `java` or `javac` executable (`java -version` and `javac -version` both report “command not found”), and its permitted outbound hosts do not include `services.gradle.org` or the Fabric/Mojang/Maven dependency repositories needed by the wrapper/build. To honor that network restriction, the Gradle wrapper/build was not run and no build or JUnit tests are claimed as passing. Java source syntax was checked with a tree-sitter Java parser; this is not a compiler or a substitute for `./gradlew test`/`build`.

Before release, run both Gradle commands above on the target toolchain, launch a Fabric 26.2 client, verify `/ahprofit` command behavior and HUD rendering in-game, and confirm the packaged JAR contains the nested SQLite JDBC library but no `credentials.json` or API key.

## Known limitations

- DonutSMP's API availability, exact market fields, history depth, server-side fees, taxes, sales cancellation/refund semantics, concurrent listings, and actual buyer liquidity cannot be established from a client-side snapshot.
- Pagination is bounded/discovered rather than a guaranteed snapshot: listings can move while pages are being fetched; the transaction API exposes only pages 1–10; different scan cycles may observe different moments.
- Item matching is deliberately strict. Unknown custom attributes, missing equipment durability/enchantment/trim fields, or incomplete container contents may suppress otherwise real opportunities rather than risk mixing different items.
- Listings may expire or sell between polling and the player's inspection. Prices may move, and no recommendation is a promise of profit.
- No automatic balance endpoint is used. Manually entered balances can be wrong; updating the balance after every in-game change is the player's responsibility.
- This project has not yet been compiled/tested against an installed Minecraft client in this restricted workspace; see [Results in this workspace](#results-in-this-workspace).

## License

MIT; see [LICENSE](LICENSE).
