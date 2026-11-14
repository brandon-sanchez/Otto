# Paid sources for in-season coverage, route and alignment data

_Researched November 13, 2026 for #145 (parent map #134). Prices and terms come from public pages on that date. They are not vendor quotes. No purchases were made and no credentials were used._

## Recommendation

Keep paid providers out of the build, as PRD #108 says, until one provider gives written permission for the way Otto works. Otto pulls data on a schedule from a cloud account and sends facts to an OpenAI model. Every consumer licence checked here limits use to personal use, and the strongest data source, PFF, explicitly forbids sending its data to a generative AI tool.

1. **First choice: PFF Pro ($199.99 a year), but only with a written exception from PFF.** PFF Pro is the only consumer product with a documented API. Its public OpenAPI contract has per-week filters, CSV export and API keys for scripts. It covers six of the seven stats in this ticket, two of them only by computing from documented fields. It does not document coverage shells. The PFF terms forbid three things Otto needs: passing PFF data to generative AI, keeping a database of API data, and access from any machine the subscriber does not alone control. Section 14.3 says only "a written agreement signed by an authorized officer of PFF" can grant more. The owner should email PFF with Otto's exact use: one user, a private AWS account, and values passed to OpenAI. Buy PFF Pro only if PFF grants that use in writing.
2. **Fallback: Fantasy Points Data Suite ($50 a month or $200 a year), loaded by hand.** Its terms let a subscriber "manually download, export, and compile" Data Suite data "for the subscriber's own private and personal use, including maintaining a personal database". They forbid automated scraping. Otto could read a CSV that the owner exports each week, but it cannot fetch the data itself. The terms do not mention AI. Before relying on this route, ask Fantasy Points to confirm in writing that sending the data to an LLM for the subscriber's own use is allowed. In-season monthly billing for the rest of 2026 costs about $100.
3. **Not recommended: Sports Info Solutions, FTN Data and NFL Pro.** SIS costs nearly four times as much ($749.99 a year), has no API for individuals, and does not publish its field list. FTN's weekly charting is already free through nflverse `ftn_charting`, which has no man or zone coverage. Its API costs about $5,000. NFL Pro has no documented API or export.

Whatever is chosen, PRD #108 still holds. Code computes each fact from a named source, and the model only explains it.

## Coverage of the requested stats

Legend: **Yes** means a primary source documents it. **Claim** means the vendor's own articles or marketing show it, but no data dictionary confirms the field. **Derivable** means Otto would have to compute it from documented fields. **No evidence** means nothing public was found.

| Stat | PFF Pro (API) | Fantasy Points Data Suite | SIS DataHub Pro | FTN Data | NFL Pro |
| --- | --- | --- | --- | --- | --- |
| Man and zone rate per defense and week | **Derivable**: defenders' `man_snap_counts_coverage` and `zone_snap_counts_coverage` with `week` and `franchise_id` filters [1]. PFF articles print team man and zone rates [6] | **Claim**: Coverage Matrix gives league-wide coverage rates [9] | **Claim**: third-party article says leaderboards filter by coverage scheme [12] | **No evidence**. The free `ftn_charting` file has blitzers and pass rushers but no coverage type | **Claim**: Next Gen Stats has classified man and zone coverages since 2022 [15] |
| Coverage-shell rates (1-high, 2-high) | **No evidence** in the API contract | **Claim**: single-high and two-high usage and fantasy points per dropback against each [9] | **No evidence** | **No evidence** | **Claim**: Cover 0 to Cover 6 classes [15] |
| Player production split by coverage | **Yes**: `facet/receiving/scheme` has `man_routes`, `man_yprr`, `zone_routes`, `zone_yprr` and more [1] | **Claim**: YPRR against man and fantasy points per dropback against man or two-high [8][9] | **No evidence** | **No evidence** | **Claim**: NFL.com prints man and zone passing splits [15] |
| Routes run and route share | **Yes**: `routes`, `route_rate` in `facet/receiving/summary` [1] | **Claim**: weekly Route Share report, routes divided by team dropbacks [8] | **No evidence** | **No evidence** for routes (participation only) | **No evidence** |
| Slot and outside alignment, points allowed to each | **Yes** for player alignment: `slot_snaps`, `wide_snaps`, `inline_snaps`. **Yes** for slot coverage allowed: `signature/defense/slot_coverage`. Points allowed by alignment is **Derivable** [1] | **Claim**: "slot share" on the WR/CB matchups page [10] | **No evidence** | **No evidence** | **No evidence** |
| Cornerback shadow coverage | **Derivable**: `facet/defense/coverage_matchup` pairs each defender with the receivers he covered [1] | **Claim**: WR/CB matchups page marks projected shadow situations and was updated November 13, 2026 [10]. A 2021 article credits PFF for the shadow data [10] | **No evidence** | **No evidence** | **No evidence** |
| QB clean pocket versus pressure | **Yes**: `facet/passing/pressure` has `pressure_*`, `no_pressure_*`, `blitz_*` and `no_blitz_*` columns [1] | **Claim**: "pressure" is a filter in Data Suite 2.0 [7] | **Claim**: pressure filter [12] | **Partial**: free blitzer counts only | **No evidence** |
| RB pass-block snaps, such as staying in on a blitz | **Yes** for pass-block snaps and pressures allowed per player (`snap_counts_pass_block`, `pressures_allowed`). A split by blitz is **Derivable** at best [1] | **No evidence** | **No evidence** | **No evidence** | **No evidence** |

## Timing relative to the Tuesday waiver deadline

- **Fantasy Points.** Its archived October 2025 content schedule lists Sunday games' charting as "fully available" on Monday. Monday-night charting and "Advanced Coverage and Separation Data" are listed for Tuesday [11]. No hour is given, so the coverage data may arrive after a Tuesday deadline. The 2026 schedule page returns 404, so this pattern is unconfirmed for 2026.
- **PFF.** No release time is documented. PFF posted "PFF grades are live" articles on Mondays during 2024 [13]. An AP report says PFF's live grades lock until a final pass the next day [13]. The API's `/v2` team-stats table returns an `updatedAt` time [1], so a trial could measure when each week lands.
- **SIS, FTN, NFL Pro.** Not documented. FTN says only that it delivers data with "short turnaround times upon game completion" [14].

## Access and licence terms

**PFF** (terms updated October 12, 2026) [3]
- The API needs a PFF Pro subscription [2]. Scripts use an API key from the pff.com account page. Every account has per-minute read and export limits, and every response is `cache-control: no-store` [1].
- §14.2(5) defines Personal Use as use "by you, as a single natural person, solely for your own private, non-commercial, non-public purposes" in environments that you alone control.
- §14.4(5) bars caching or storing API data in bulk, or maintaining "any database". Local storage is allowed only while the subscription is active, and the data must be deleted when it ends.
- §1.6 says a subscriber may not provide PFF data "as inputs or prompts to any generative artificial intelligence tools". The only exception is derived data that "does not contain, reproduce, or allow the recovery or reconstruction of any underlying API Data". A fact such as "2.1 yards per route run against man" is API data itself.
- §14.4 bars hosting an application on a cloud platform "where any person other than you can access it". A private AWS account may fit, but the terms do not say so.

**Fantasy Points** (terms updated August 2, 2026) [5]
- §7.4 allows manual download, export and a personal database. It forbids "automated scraping or bulk extraction", redistribution, and sharing with non-subscribers.
- §5.2 says using the service "to inform your own personal fantasy football or wagering decisions is a permitted personal use".
- The terms say nothing about AI. Whether sending data to OpenAI counts as sharing with a non-subscriber is unclear.
- No API is documented.

**SIS DataHub Pro** (subscriber terms on the registration page) [12]
- Data may be used "for private purposes" and in articles, with at most five data points per published piece.
- The subscriber may not "distribute, sell, transmit … or transfer" SIS content without written permission. The limit is 1,000 queries a week.
- The SIS website terms forbid automated access by bots. The DataHub Pro offers CSV export but no API for individuals. Raw feeds and an API are sold to teams and businesses on request.

**FTN Data**: sells to individuals and businesses. API terms were not public [14].

**NFL Pro**: consumer web product inside NFL+ Premium. No API or data-use terms are published [15].

## Price for one user

| Provider | Product | Price | Status |
| --- | --- | --- | --- |
| PFF | PFF Pro (Premium Stats Pro and API) | $199.99 a year; no monthly Pro plan listed | Verified [2] |
| PFF | PFF+ Annual (Premium Stats, no API) | $99.99 a year | Verified [2] |
| Fantasy Points | NFL Data Suite | $200 a year or $50 a month | Verified from plan data on the plans page [4] |
| SIS | DataHub Pro, NFL | $99.99 a month or $749.99 a year; 7-day free trial | Verified [12] |
| FTN Data | CSV access / API with charting | $599 / $5,000 | From an April 2025 archive of FTN's FAQ; the live page blocks automated reads [14] |
| NFL | NFL+ Premium (includes NFL Pro) | $14.99 a month or $99.99 a season | Reported by third parties; nfl.com shows no price [15] |
| Teamworks (PFF B2B), SIS feeds, Genius Sports | Enterprise feeds | Quote only | PFF's B2B site redirects to Teamworks [3] |

## Verified facts and open questions

Verified from primary sources: PFF's API contract and field names, PFF's terms, PFF and Fantasy Points prices, the Fantasy Points data-use clause, SIS prices and subscriber terms, and the archived Fantasy Points weekly schedule.

Still claims or guesses:
- Fantasy Points' exact field definitions. Its tools need a login, so this note relies on its articles and report glossaries.
- Whether any PFF field gives team man or zone rate or coverage shell directly.
- SIS field coverage.
- Every provider's 2026 publication time.
- Whether PFF or Fantasy Points would allow LLM input in writing.

A seven-day SIS trial, and one month of the Fantasy Points Data Suite at $50, would settle the field questions cheaply. That is the owner's call.

## Sources

1. PFF, [Premium Stats Pro CLI API OpenAPI contract v2.1.0](https://developer.pff.com/openapi.json) and [API reference](https://developer.pff.com/reference/)
2. PFF, [plans and pricing](https://www.pff.com/premium) and [developer guide](https://developer.pff.com/)
3. PFF, [Terms of Use](https://pff.com/terms), §§1.2, 1.5, 1.6, 14; [b2b.pff.com/data](https://b2b.pff.com/data) redirects to teamworks.com
4. Fantasy Points, [Plans](https://www.fantasypoints.com/plans): plan data lists "NFL Data Suite (Annually) - $200" and "(Monthly) - $50"
5. Fantasy Points, [Terms & Conditions](https://www.fantasypoints.com/terms), §§5.2, 7.4
6. PFF, [WR report: man and zone performance, week 9 2025](https://www.pff.com/news/fantasy-football-wide-receiver-report-man-zone-coverage-performance-nfl-week-9-2025)
7. Fantasy Points, [Data Suite 2.0](https://newsletter.fantasypoints.com/p/fantasy-points-data-suite-2), September 8, 2026
8. Fantasy Points, [2025 Data Suite tools](https://www.fantasypoints.com/nfl/articles/2025/fantasy-points-data-new-tools-breakdown) and [2026 early-bird plans](https://newsletter.fantasypoints.com/p/early-bird-discount-2026)
9. Fantasy Points, [Week 10 DFS coverage shells](https://fantasypoints.com/nfl/articles/2024/week-10-dfs-coverage-shells) and [NFL Coverage Bible](https://fantasypoints.com/nfl/articles/2024/nfl-coverage-bible-broad-coverages)
10. Fantasy Points, [WR/CB Matchups](https://fantasypoints.com/nfl/reports/wr-cb-matchups) and [shadow matchups article](https://fantasypts.com/nfl/articles/2021/which-cbwr-shadow-matchups-actually-matter)
11. Fantasy Points, [content schedule, archived October 11, 2025](https://web.archive.org/web/20251011185336/https://www.fantasypoints.com/content-schedule)
12. SIS, [DataHub pricing](https://store.sportsinfosolutions.com), [registration and subscriber terms](https://auth.sportsinfosolutions.com/Account/Register), [football products](https://www.sportsinfosolutions.com/solution/football), [website terms](https://sportsinfosolutions.com/terms-of-service/); RotoGrinders, [SIS DataHub research tool](https://rotogrinders.com/articles/sis-datahub-new-marketplace-dfs-research-tool-2644741)
13. PFF, [week 12 2024 "grades are live" listing](https://pff.com/nfl?offset=150); AP, [PFF adds in-game grading](https://abcnews.go.com/Sports/wireStory/pff-adds-game-grading-feature-nfl-analysis-114463373)
14. FTN, [Data FAQ, archived April 26, 2025](https://web.archive.org/web/20250426062553/https://ftnfantasy.com/data)
15. NFL, [NFL+](https://www.nfl.com/plus/); AWS, [NGS coverage classification](https://aws.amazon.com/blogs/machine-learning/identifying-defense-coverage-schemes-in-nfls-next-gen-stats/); NFL.com, [Next Gen Stats 2025 metrics](https://www.nfl.com/_amp/next-gen-stats-new-advanced-metrics-you-need-to-know-for-the-2025-nfl-season); NFL+ price as reported by [agoodmovietowatch](https://agoodmovietowatch.com/platforms/nfl-premium-nfl-redzone-bundle/)
