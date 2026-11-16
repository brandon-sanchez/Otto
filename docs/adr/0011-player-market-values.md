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
- A keeper league values a likely keeper at his redraft value plus a keeper bonus: his dynasty value minus what keeping him costs next season. Everyone else gets his redraft value alone.
  - A team's likely keepers are its players worth most above what keeping them costs, those worth more than the cost, up to the league's keeper limit.
  - Sleeper records each draft's picks, with the round, the auction price and whether the pick was a keeper, but never the league's keeper rule, which commissioners enforce by hand. Otto follows `previous_league_id` back through the league's finished drafts. A player costs what the rule makes of his round or price in the latest finished draft: this season's in season, last season's before the draft. A player nobody drafted costs the last round, or $1. Years kept count the drafts in a row that kept him.
  - The owner sets the rule in chat (`manage_keeper_rule`): the same round or price, some rounds earlier per year kept, one fixed round, free, plus some dollars, or plus a percent, with an optional minimum cost, undrafted cost and year limit. It's stored per league. Until he does, Otto proposes the rule two seasons of drafts follow, or else assumes the round drafted, says so in its answers, and asks once.
  - A pick costs the dynasty value of the player the market ranks at the middle of that round. Auction dollars cost the drafted players' combined dynasty value over every team's budget, per dollar. A free keeper, or a league with no finished draft, costs the keeper line: the dynasty value of the best rostered player who falls outside every team's likely keepers, what a keeper spot gives up.
- TE premium isn't in either source. Otto's scoring-based rest-of-season math (ADR-0008) covers it.

## Checked answers

A chat question about a player's worth goes to the `get_market_value` tool. Code computes the quote. The model explains it in a few sentences from an evidence bundle, and `AdviceCheck` holds every name, number and rank to that bundle, with one correction allowed. If the answer fails twice, it's withheld. Each answer writes a Decision Record of kind `MARKET_VALUE`.

## Consequences

- If FantasyCalc disappears or blocks Otto, dynasty values still come from DynastyProcess, but redraft leagues lose market values.
- Keeper bonuses depend on the rule in force. An unconfirmed rule is a guess, so answers say where the rule came from. `KeeperCosts` is the one place keeper pricing plugs in.
