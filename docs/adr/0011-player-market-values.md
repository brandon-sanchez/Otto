# ADR-0011: Player market values come from FantasyCalc, once a day, with DynastyProcess behind it

Status: accepted (2026-11-16, issue #218)

Otto's own trade math (ADR-0008, trade evaluation) says how much a player helps a roster over the rest of the season. It can't say what another manager would pay for him. Trade ideas, team direction and buy-low reads all need that second number: the price managers pay for a player in real trades. This ADR records where Otto gets that price, how often it asks, and what it keeps.

## Sources

**FantasyCalc is the source.** Its free JSON endpoint, `https://api.fantasycalc.com/values/current`, prices players from real trades and keys every row by Sleeper id, so no name matching is needed. The request is set by the league's own settings:

- `isDynasty` comes from `LeagueRules.format()`. A keeper league reads both the redraft and the dynasty lists.
- `numQbs` is 2 when the league has a `SUPER_FLEX` slot or two `QB` slots, otherwise 1.
- `numTeams` comes from the league's `num_teams` setting.
- `ppr` is the league's points per reception, rounded to 0, 0.5 or 1, the steps FantasyCalc prices.

Otto keeps every field FantasyCalc publishes for a player: value, redraft value, overall rank, position rank, tier, 30-day trend, ADP, roster share, trade frequency, age and years of experience. The dynasty list also prices future rookie picks.

**DynastyProcess is the fallback for dynasty players and picks.** Its `values.csv` is GPL-3 open data with a one-quarterback and a Superflex value for each player and pick. Its `values-picks.csv` ranks picks but carries no values, so Otto reads `values.csv`, which holds both players and picks. Rows join to Sleeper through the FantasyPros id in the Player-ID Map that Otto already downloads. DynastyProcess has no redraft values, so a redraft league that FantasyCalc doesn't price has no market value, and Otto says so.

Every answer names the source of its value.

**KeepTradeCut and FantasyPros are not used.** KeepTradeCut's terms ban automated collection of any kind. FantasyPros sells its data through a paid API that costs more than Otto's free-data budget allows. DynastyProcess's values derive from FantasyPros' expert rankings, and that's the only way FantasyPros reaches Otto.

## Once a day

FantasyCalc publishes no terms of use and no rate limit. Out of courtesy, Otto asks it at most once a UTC day for each format, and asks DynastyProcess at most once a day too. The limit counts failed asks: a day whose ask fails isn't retried. That day's answers use the last good list and say how old it is.

An hourly scheduled job (`market-values`) asks for the formats the user's league needs. Only the first run each day reaches the sources. Chat answers read through the same limit, so a question asked before the job has run triggers the day's single ask, and later questions that day reuse it.

## History

Each day's FantasyCalc list is kept in a ring of 31 daily slots for each format. A value change over any window up to 30 days can then be measured from Otto's own record, beside FantasyCalc's `trend30Day`. Because the ring overwrites the oldest day, storage stays the same size all season. The documents are small DynamoDB records under the `market-values-` prefix.

## Valuation rules

- The redraft list already prices injuries, and the dynasty list already prices age. Otto applies neither again.
- A keeper league values a likely keeper at his redraft value plus a keeper bonus. Everyone else gets his redraft value alone.
  - A likely keeper is one of his team's most valuable players by dynasty value, inside the league's keeper limit, and worth more than the keeper line.
  - Sleeper doesn't publish a keeper's cost, so the cost is what a keeper spot gives up: the keeper line, the dynasty value of the best rostered player who falls outside every team's likely keepers.
  - The bonus is the player's dynasty value minus the keeper line.
- TE premium isn't in either source. Otto's scoring-based rest-of-season math (ADR-0008) covers it.

## Checked answers

A chat question about a player's worth goes to the `get_market_value` tool. Code computes the quote. The model explains it in a few sentences from an evidence bundle, and `AdviceCheck` holds every name, number and rank to that bundle, with one correction allowed. If the answer fails twice, it's withheld. Each answer writes a Decision Record of kind `MARKET_VALUE`.

## Consequences

- If FantasyCalc disappears or blocks Otto, dynasty values still come from DynastyProcess, but redraft leagues lose market values.
- Keeper bonuses depend on the keeper line as defined above. A league whose keepers cost draft rounds may want the real cost. Sleeper doesn't expose it, so that waits for a source that does.
