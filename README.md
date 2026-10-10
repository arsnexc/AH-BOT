# AhProfit

AhProfit is a **client-only Fabric mod** for DonutSMP Auction House research. It supports **key-free local market snapshots**, authenticated polling of documented auction-listing and recent-transaction endpoints, and — only when you explicitly switch it on — auction-house automation with hard spending caps. It keeps a local SQLite history, estimates conservative resale values from completed sales, and ranks flips against a manually entered bankroll.

**Auction automation is off by default.** Out of the box AhProfit only reads and advises: it never buys, lists, bids, transfers, clicks auction menus, or sends trade commands. Automation becomes possible only after `/ahprofit ah on`, and even then it obeys per-action, hourly, and action-count ceilings.

## Features

- Optional key-free local data mode with bounded JSON imports, separate local/API histories, and explicit local-data labeling. No API requests are made in local mode.
- Background, single-worker polling; no network or database work runs on Minecraft's render/client command thread.
- Serialized HTTPS requests with a one-second minimum gap, bounded JSON parsing, retry handling, exponential jittered backoff, server `Retry-After` handling, and an explicit manual-refresh cooldown.
- Local SQLite persistence for active listings, listing observations, completed sales, price estimates, and per-endpoint/page successful-fetch timestamps.
- Variant-aware item fingerprints. Incomplete/unknown equipment attributes and incomplete container contents are not compared as if they were identical.
- Resale estimates are based on exact-variant completed sales. Recent weighted medians, median absolute deviation, sample thresholds, freshness, active competition, volatility, confidence, and liquidity proxies are surfaced rather than hidden.
- Active asks can only lower/cap a completed-sales estimate; asking prices never establish or increase the estimated resale value.
- **Flips are ranked by capital efficiency and risk-adjusted return, not by raw profit.** A fast, cheap, high-turnover flip now outranks an expensive one with a similar profit but slower turnover. The full score breakdown, sell-through probability, expected days to clear, and profit per capital per day are shown for every recommendation.
- **Quality gates are enforced rather than only flagged.** Listings below the configured confidence floor or needing longer than the configured days-to-clear are no longer recommended.
- Recommendations are filtered using net profit after explicit fee/risk haircuts, ROI and discount floors, current data freshness, and bankroll constraints.
- A hypothetical capital-allocation plan applies total, per-item, per-category, and per-listing caps. It does not reserve or spend money.
- A small HUD, deduplicated material-change alerts, and detailed local status/recommendation output.
- API credentials are isolated from ordinary settings, excluded from Git, written atomically, and restricted to owner read/write permissions where the filesystem supports POSIX permissions.

## Installation and first run

1. Build the mod (see [Build and verification](#build-and-verification)) and put `build/libs/ahprofit-1.0.0.jar` in the `mods` directory of a Minecraft **1.21.11 client** instance that has Fabric Loader and Fabric API installed.
2. Start the client once. AhProfit creates `config/ahprofit/settings.json` and `config/ahprofit/credentials.json` inside that game instance's config directory. The selected source's market database is created on the first scan/refresh.
3. **Choose a data source. No API key is required for local mode.** The default `marketDataMode: "auto"` uses local data when no key is configured; see [Key-free local mode](#key-free-local-mode). For live API polling instead, in DonutSMP create an API key using the server's `/api` command. Add it to `config/ahprofit/credentials.json`:

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

**The key authenticates DonutSMP's remote API, not AhProfit's local commands.** Deleting the key check would not unlock the remote API. Bankroll controls, the HUD, cached analysis, and recommendations can run without a key using actual local market records. Live API polling still requires a valid server-provided key. Local/imported/cached estimates are not live quotes.

## Key-free local mode

1. Run `/ahprofit source local` to explicitly disable API requests, even if a key is saved. Alternatively, leave the default `auto` mode with no key configured.
2. Enter a fresh manual balance with `/ahprofit budget <amount>`.
3. Supply a UTF-8 JSON file at `config/ahprofit/market-input.json` in the game instance. Start from `config/market-input.example.json` in this repository, **replace the illustrative records with actual observations**, and set real epoch-millisecond timestamps. The example's zero observation timestamp is intentionally invalid; it is not seeded into the running mod.
4. **Collect listings in game instead of typing them**: run `/ahprofit ah record on`, then open the auction house and browse. AhProfit reads the open menu and writes what you looked at to `config/ahprofit/ah-observed.json`, which is merged with your `market-input.json` on the next refresh. It never clicks anything to do this, and it only holds the last ten minutes of listings, because a listing seen ten minutes ago is no longer a current asking price.
5. Run `/ahprofit refresh` for one background import, or `/ahprofit` to periodically re-read the file. AhProfit does not obtain or generate this file for you, click menus, or invent completed transactions.
6. Use `/ahprofit status` and the HUD as usual. They explicitly label the data as `LOCAL`, `LOCAL_WAITING` (input file absent), or `LOCAL_ERROR` (invalid/unreadable/older input). An absent file leaves the local bankroll tools and any existing local cache available, not a stream of live listings.

With **zero completed sales**, `/ahprofit askonly on` ranks flips by asking-price spread instead: the resale target is the cheapest rival ask for the same item undercut by `askUndercutPercent`, never the market median. Without it, zero sales means zero opportunities, so nothing is ranked and automation has nothing to buy.

Source selection is persisted in `settings.json` as `marketDataMode`:

| Mode | Behavior |
| --- | --- |
| `auto` (default) | Authenticated API when a key is configured; local input otherwise. `/ahprofit refresh` reloads credentials in auto/API mode. A rejected configured key is still reported, not silently treated as an authenticated connection. |
| `local` | Only local input/cache; **zero API requests**, with or without a saved key. |
| `api` | Original live API polling; missing/rejected keys pause requests. |

Use `/ahprofit source` to inspect the choice, or `/ahprofit source auto`, `/ahprofit source local`, or `/ahprofit source api` to change it. Switching clears the displayed analysis and cancels old scanner work before selecting the other store. It does **not** clear upstream API authentication or rate-limit pauses. If scanning was running, a background refresh starts for the selected source; otherwise use `/ahprofit refresh` to load new input.

### Local snapshot format

The root has four required fields:

- `schemaVersion`: integer `1`.
- `observedAtMillis`: **actual observation time**, a positive epoch-millisecond integer no later than the current clock. Do not set this to the file-read time just to keep old quotes fresh.
- `listings`: array of current observed listings. Each uses the same item/seller/price fields as the API: `item`, `seller` (at least `name` or `uuid`), numeric total `price`, and integer `time_left` in milliseconds **at the observation time**. Expiry is calculated from that time, not from import time.
- `sales`: array of **actual completed sales**, using `item`, numeric total `price`, integer `unixMillisDateSold`, and optional `seller`. Sale timestamps must be positive and no later than `observedAtMillis`. Asking prices, disappeared listings, and hypothetical trades are not completed-sale evidence.

The file is a complete active-listing snapshot, not a delta; a newer empty `listings` array clears local active listings. Completed sales accumulate and deduplicate by stable identity across imports. A record's `price` is the **whole-stack total**, not its unit price. Item variant matching and all existing risk, fee, freshness, sales-count, and bankroll filters remain unchanged; normally at least five exact-variant completed sales are needed. With asks alone, no evidence-backed flip is recommended.

Imports are limited to 4 MiB, 10,000 combined records, and 32 JSON nesting levels. Symbolic-link/non-regular input files, missing fields, invalid records, and unsupported schemas are rejected before writing. Listings, completed sales, and observation timestamps are saved atomically. Use an atomic file replacement when an external tool updates the input to avoid a partially written JSON file.

An input older than the last successful local observation is rejected. Re-reading a snapshot with the same observation timestamp is idempotent: it does not add duplicate sales, apply edited records, extend expiries, or refresh freshness. To apply **genuinely newly observed** data, provide a newer `observedAtMillis`. The usual 300-second default listing-age limit still applies, even if a stale file is imported just now.

Local history lives in `config/ahprofit/local-market.sqlite`; API history remains in `config/ahprofit/market.sqlite`. They are never combined for resale estimates, so user-supplied records cannot silently contaminate the API cache. No demo prices or transactions are automatically loaded, and local records are not verified against DonutSMP.

## Local commands

These use Fabric's **client command API**, are registered locally, and execute on the client when entered by the player. AhProfit contains no code that sends these command strings as server trade commands.

| Command | Effect |
| --- | --- |
| `/ahprofit` or `/ahprofit toggle` | Toggle recurring background scanning and show the current balance estimate, data-source state, data freshness, analyzed-listing count, and qualified-opportunity count. |
| `/ahprofit source [auto\|api\|local]` | Inspect/change the data source. Local mode needs no key and never contacts the API. |
| `/ahprofit status` | Show detailed source/cache status, freshness, manual bankroll and caps, hypothetical exposure, top recommendations, evidence, and warnings. |
| `/ahprofit budget <amount>` | Set the manual balance estimate. Accepts values such as `2000000`, `2,000,000`, or `$2,000,000`. |
| `/ahprofit reserve <amount>` | Set an additional minimum protected reserve. The percentage reserve still applies; the effective reserve is the greater of the two. |
| `/ahprofit minprofit <amount>` | Set the absolute net-profit floor. The active floor is the greater of this value and the configured percentage-of-balance floor. |
| `/ahprofit minroi <percent>` | Set the minimum net ROI percentage, e.g. `25` or `25%`. |
| `/ahprofit refresh` | Queue a one-shot background refresh/import, subject to a 15-second local cooldown and any applicable upstream API backoff. Does not turn on recurring scans. |
| `/ahprofit ah` | Show auction-house automation state, its last decision, and the rolling hourly counters. |
| `/ahprofit ah on` / `ah off` | Enable or disable auction automation. Off is the default and never clicks anything. |
| `/ahprofit ah dryrun` / `ah live` | Watch what would be bought without clicking, or allow real clicks. |
| `/ahprofit ah stop` | Halt automation instantly. It stays halted until `ah on`. |
| `/ahprofit ah record [on\|off]` | Read the open auction-house menu into `ah-observed.json`. Read-only; never clicks. |
| `/ahprofit ah holdings` | List what automation bought this session, with the unit cost paid. |
| `/ahprofit ah sellplan` | Break-even and recommended list price for each holding. |
| `/ahprofit ah sell` | List the first holding. Refuses to click unless `automationSellSlot` and `automationConfirmSlot` are set. |
| `/ahprofit askonly on` / `askonly off` | Rank flips by asking-price spread, which works with no completed sales. |
| `/ahprofit ah limits <per-action> <per-hour>` | Set the spending caps, e.g. `/ahprofit ah limits 500000 5000000`. |
| `/ahprofit help` | Show quick setup and safety notes. |

A successful balance change re-scores cached records in the background without making an extra API request. A `/ahprofit refresh` is required to refresh market data on demand.

## Flip ranking

Ranking uses capital efficiency and risk-adjusted return rather than raw profit, so a fast cheap flip beats an expensive slow one with a similar profit. Each recommendation reports:

- **sell-through probability** — derived from liquidity, competing supply, and how long current supply takes to clear.
- **expected days to clear** — how long visible supply takes to absorb at the observed transaction rate.
- **profit per capital per day** — net profit divided by the capital locked up for that long; the dominant ranking term.
- **weakest point** — the largest scoring penalty, so you can see why a flip ranks where it does.

Two quality gates are enforced rather than merely flagged: a listing must reach the configured **confidence floor** (`minimumConfidence`, default `0.50`) and must clear within **`maximumDaysToClear`** days (default 14). Otherwise it is counted as uncertain and is not recommended.

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

All calculations use the supplied listing/transaction total price and item quantity to derive a unit price; a stack is not compared to a single unit at its total stack price.

- The normal analysis window is the last seven days of cached completed sales, matched by an exact, quantity-independent item fingerprint. At least **5** comparable sales are required by default. A lower-volume estimate is marked low-confidence and cannot qualify.
- A recency-weighted median is clipped around the seven-day median using the median absolute deviation. The conservative unit value is no higher than the weighted median, seven-day median, or (when at least three sales exist) 24-hour median. The lowest fresh exact-variant active ask may lower that value further.
- Confidence and liquidity are heuristics based on sample size, sale recency, price dispersion, competing listings, recent sale counts, and visible supply. The `days to clear`/supply figure is only a transaction-volume proxy; it does not guarantee an item will sell within that time.
- Estimated net profit subtracts a default **5% assumed selling-fee haircut** and a risk buffer (default 5%, increased by observed volatility). DonutSMP's fee/tax is not confirmed by these read endpoints; both values are configurable assumptions, not market facts.
- Defaults require minimum **20% net ROI**, **30% discount** to the conservative resale estimate, and a minimum net profit of the greater of the configured absolute floor and **2% of the current manual balance**.
- A balance is considered stale if it is unset, older than 24 hours, or has a future timestamp. A stale balance is clearly warned about and disables capital recommendations until refreshed with `/ahprofit budget`.
- Let `B` be the manual balance and `D` be deployable capital after reserve. Defaults reserve the greater of **20% of B** and the configured minimum reserve. The max spend for one listing is `min(15% of B, 25% of D)`. The hypothetical portfolio is capped at **50% of D** total, **20% of D** per exact item variant, and **35% of D** per broad category. These independent limits mean the remaining deployable balance is not necessarily fully allocated.
- The plan ranks up to five candidates. It is a suggestion only: AhProfit does not track inventory/other holdings, reserve capital in-game, or know whether the player has already placed a trade.

## Auction-house automation (off by default)

AhProfit can act on the auction-house menu you have open. **It is disabled until you enable it**, and the default `automationDryRun` is `true`, so the first enable only reports what it would do.

What it does:

- Reads the open container menu (slots, item id, stack size, hover name, and lore) — nothing is sent while reading.
- Parses a price from each listing's lore, preferring keyword lines such as `Price: $1,234` and ignoring quantity lines such as `64 x Diamond`.
- Matches live listings against ranked opportunities by item id and stack size, choosing the cheapest match.
- **Re-derives the flip at the live price.** A cached recommendation never buys a listing that no longer clears the profit, ROI, and discount filters at its current price.
- Clicks the listing to buy it, then moves the carried stack into your inventory.

Safety controls, all enforced server-side of the decision:

| Control | Setting | Default |
| --- | --- | --- |
| Master switch | `automationEnabled` | `false` |
| Dry run (report only) | `automationDryRun` | `true` |
| Per-action spend cap | `automationMaxSpendPerAction` | `500000` |
| Hourly spend cap | `automationMaxSpendPerHour` | `5000000` |
| Actions per hour | `automationMaxActionsPerHour` | `12` |
| Cooldown between actions | `automationCooldownSeconds` | `10` |
| Minimum flip score to act | `automationMinimumScore` | `55` |

Spend is counted even if a click fails, so the hourly ceiling stays conservative. `/ahprofit ah stop` halts automation immediately and it stays halted until you re-enable it.

Suggested first use:

```text
/ahprofit budget 2000000
/ahprofit askonly on
/ahprofit ah record on
/ahprofit ah dryrun
/ahprofit ah
```

Browse the auction house while recording is on, then `/ahprofit refresh` and `/ahprofit status` to see the ranked flips. `/ahprofit ah` shows the last decision and why, so you can confirm the price parsing matches the real menu before switching to `/ahprofit ah live`.

### When something does not work

Run `/ahprofit ah probe` with the auction house open. It prints the open screen's class, the container menu's id and title, whether AhProfit recognises it as a readable menu, how many slots hold items, how many of those have a price the lore parser can read, and the raw lore lines of the first few items. It then prints the market-data mode, whether a key is present, the scanner status, how many opportunities the last analysis produced, and how many of those are ask-based.

That output is the fastest way to find out which of the four things actually broke: the menu is not being read, the prices are not being parsed, the data never reached the database, or the analysis produced nothing. While the harvester is on, the HUD shows a live `AH recorder ON: N listings` line so recording is never a silent black box.

### Selling what you bought

Automation remembers what it bought this session, with the unit price actually paid. `/ahprofit ah holdings` lists it and `/ahprofit ah sellplan` shows the break-even and the recommended list price for each holding. The recommended price undercuts the cheapest visible rival ask but never drops below your own cost, so the plan cannot advise you into a guaranteed loss.

`/ahprofit ah sell` performs the listing. Because AhProfit does not know DonutSMP's sell-menu layout, it refuses to click unless you map `automationSellSlot` (the button that opens the sell view) and `automationConfirmSlot` (the button that confirms) in `config/ahprofit/settings.json`. Both default to `-1`, so no unverified slot is ever clicked. Selling obeys the same dry-run setting as buying, and `/ahprofit ah stop` halts it.

**This has not been tested against the live DonutSMP auction menu.** The lore price patterns are generic heuristics; if the real menu formats prices differently, dry run will show "no readable price" rather than buying. Verify the parsing in dry run before going live.

**Automated trading may violate the server's rules.** Many Minecraft servers prohibit automation or macroing, and doing so can get an account restricted or banned. That risk is yours; check DonutSMP's rules first. AhProfit's caps limit spend, not rule compliance.

Settings and defaults live in `config/ahprofit/settings.json`; `config/ahprofit.example.json` is a reference copy. The API key is separate in `config/ahprofit/credentials.json` (see `config/credentials.example.json`). Price history is in `config/ahprofit/market.sqlite` for the API and `config/ahprofit/local-market.sqlite` for local input. SQLite retains observations and transactions for up to 90 days, prunes old active listings after one day, and analyses only the recent seven-day sales window. The entire config directory is local to the Minecraft game instance; back it up securely if you want to preserve history.

## Build and verification

### Target toolchain

| Component | Project target |
| --- | --- |
| Minecraft | `1.21.11` |
| Java bytecode / game runtime | Java `21` |
| Java build toolchain | Java `25` (used by Fabric's 1.21.11 template) |
| Fabric Loader | `0.19.5` or newer |
| Fabric API | `0.141.6+1.21.11` |
| Fabric Loom | `1.18-SNAPSHOT` |
| Gradle wrapper | `9.7.1` |
| SQLite JDBC | `3.53.4.0` (nested in the mod JAR) |
| JUnit Jupiter | `6.1.3` |

Run Gradle with Java 25 (as in Fabric's 1.21.11 template); the compiled mod targets Java 21. Gradle also needs normal access to the Fabric, Mojang, and Maven artifact repositories:

```sh
./gradlew test
./gradlew build
```

The expected remapped runtime artifact is:

```text
build/libs/ahprofit-1.0.0.jar
```

The GitHub Actions workflow at `.github/workflows/build.yml` runs tests and builds this JAR on pushes, pull requests, and manual dispatches. It uploads the runtime mod JAR (not the sources JAR) as an Actions artifact named `ahprofit-mod-<commit SHA>`; download it from the corresponding workflow run's **Artifacts** section. If the build fails, it also publishes Gradle error details and a short-lived diagnostic-log artifact.

### Verification

GitHub Actions runs `./gradlew --no-daemon clean test build` with Java 25 (Loom's build runtime); the resulting mod targets Java 21. This runs on pushes, pull requests, and manual dispatches. A successful run uploads the remapped runtime JAR; the workflow does not launch a live Minecraft client. Local builds require the Gradle, Fabric, Mojang, and Maven artifact repositories described above.

Before release, install the resulting JAR in a Fabric 1.21.11 client, verify `/ahprofit` command behavior and HUD rendering in-game, and confirm the packaged JAR contains the nested SQLite JDBC library but no `credentials.json` or API key.

## Known limitations

- Key-free mode is local/offline input, not a remote authentication bypass and not automatic in-game auction scraping. Fresh recommendations require genuinely fresh supplied listings and sufficient actual completed-sale records.
- DonutSMP's API availability, exact market fields, history depth, server-side fees, taxes, sales cancellation/refund semantics, concurrent listings, and actual buyer liquidity cannot be established from a client-side snapshot.
- Pagination is bounded/discovered rather than a guaranteed snapshot: listings can move while pages are being fetched; the transaction API exposes only pages 1–10; different scan cycles may observe different moments.
- Item matching is deliberately strict. Unknown custom attributes, missing equipment durability/enchantment/trim fields, or incomplete container contents may suppress otherwise real opportunities rather than risk mixing different items.
- Listings may expire or sell between polling and the player's inspection. Prices may move, and no recommendation is a promise of profit.
- No automatic balance endpoint is used. Manually entered balances can be wrong; updating the balance after every in-game change is the player's responsibility.
- Auction automation reads whatever container menu is open using generic container and lore heuristics. It has **not** been verified against the real DonutSMP auction menu, and it cannot know a server's custom confirmation dialogs, chat-driven flows, or anti-automation protections. Use dry run first, and expect to adjust the price patterns or abandon automation if the menu differs.
- Automation only covers buying a listed item. It does not create listings, set prices on the server, or manage an existing inventory; sell-side advice is limited to the recommended list price shown in `/ahprofit status`.
- GitHub Actions compiles/tests and packages the 1.21.11 target, but the project still needs an in-game smoke test with a live Fabric client and DonutSMP session.

## License

MIT; see [LICENSE](LICENSE).
