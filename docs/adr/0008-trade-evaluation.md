# ADR-0008: Trade evaluation

Status: accepted (2026-08-09, issue #18)

The spec pins the valuation formula, the verdict bands and the leverage
note. It leaves the shape of each open: what "rest of season" reads
from, which roster a fit factor is measured against, what a percentage
is a percentage of, and what happens to the parts of a trade that are
not players. This ADR records what implementation decided.

## Rest of season is a sum of weeks, because that is all Sleeper sells

Sleeper publishes projections one week at a time and a schedule one week
at a time. There is no season endpoint, so a season total is the sum of
the weeks from the current one to the last week of the regular season -
the week before `playoff_week_start`.

That shape is not a workaround; it is the only shape that can count a
bye as zero. A bye is a week the player's team has no game in, and only
that week's own schedule says so. A season-level number would have to
assume every player plays every week, which prices a player with two
byes left the same as a player with none.

The cost is one conditional GET per remaining week per feed, through the
cadence-gated adapter ADR-0003 introduced. A second trade question
inside the cadence interval costs nothing at all, and the reads are
shared with every other Ask.

A week whose projections or schedule never arrived is left out of the
total and named in the notes. Counting it as zero would be
indistinguishable from a bye, and a broken feed must not read as a
player who does not play.

## The value formula, and why each factor is what it is

A player is worth his rest-of-season points in league scoring, times a
positional scarcity multiplier, times a roster-fit factor:

- Scarcity: QB 1.20, TE 1.10, RB 1.05, WR 1.00. The receiver is the
  yardstick because the receiver is the position the waiver wire answers
  best - a league of this size always has a startable one free. The
  quarterback multiplier is the largest because this league is
  Superflex: two of nine starting slots take a quarterback, so a
  quarterback lost is a starting slot that cannot be refilled. Tight end
  sits next because the position runs dry below its first dozen, which
  is the same fact the replacement-level ranks record (TE 12 where the
  other three sit at 24).
- Roster fit: 1.10 when he walks into the optimal lineup of the roster
  judging him, 0.90 when somebody better at his position is already on
  that bench ahead of him, and 1.00 otherwise, which is the first man
  off the bench and real cover.

Fit is answered by the optimal lineup rather than by a count of players
at the position, because the flex slots decide it: a fourth receiver
starts in a league with two flex slots and does not in a league without
them. It runs the same `LineupOptimizer` the lineup tools already run,
so a trade and a start-sit call cannot disagree about who starts.

## The fit factor always asks the same question of the same roster

The roster a player is measured against is the roster that is judging
him:

- A player arriving is measured against the receiving roster **after**
  the trade, because that is the team he would start or sit for.
- A player leaving is measured against the roster he is on **now**,
  because what that team gives up is what he is worth there today.

Four fit computations follow, and the trade is priced twice. This is
what makes "the same math from both teams' perspectives" mean anything:
a trade can be good for both teams, and the only way to show that is to
let each team price its own gain and its own loss.

ADR-0004 fixed that a team is judged the same way whichever team it is.
The leverage note therefore reads both rosters through the same
replacement-level depth `get_team_roster` gives, rather than growing a
second yardstick for the team on the other side of the deal.

## The verdict is a share of the larger side

The gap is the difference between the two sides divided by the larger of
them. Dividing by the user's own side would make the same trade read
differently depending on which way round he typed it, and dividing by
the sum would halve every band.

- Inside 5%: even. The lean is stated, because the user asked, but the
  answer says in as many words that a coin flip is not a reason to act.
- 5% to 15%: a slight edge.
- Over 15%: a clear edge, at High confidence.

ADR-0003 fixed that a comparison of two estimates rides at Medium.
A trade verdict at High is a deliberate exception to that, and the
reason is the summing: `compare_players` weighs one week against one
week, where a projection's own error is the size of the answer. A trade
is weighed over every week left in the season, where the same per-week
error averages out, and a gap of more than 15% of the larger side is
larger than the error that is left. The two smaller bands stay at
Medium, which is where a one-week comparison sits.

## Draft picks and FAAB count zero, and the answer says so

Otto prices no rookie draft pick. It holds no dynasty rankings, no
pick-value chart and no view of next year's class, and a number invented
for one would be the model's guess dressed as arithmetic.

The alternative to a zero is not a better number, it is silence - and
silence is worse. A pick-for-player trade with the pick quietly dropped
reads as a robbery in whichever direction the players fall. So the pick
is carried through the answer as an asset priced at zero, with a note
saying the verdict cannot see it, and the narration says the same.

FAAB is treated the same way and for the same reason. The spec does not
mention it, but Sleeper's own trade row carries a `waiver_budget` array
beside `draft_picks`, so a trade that moves bidding money is a trade
this system will be shown. Dropping it silently would be the same
failure as dropping a pick. What FAAB is actually worth against a player
is an open question, deliberately left open.

## One shape for what a trade moves

A trade moves three kinds of thing, and Sleeper's transaction row says
so: players in `adds` and `drops`, picks in `draft_picks`, money in
`waiver_budget`. `TradeAsset` is the one type that holds all three.

The Ask lane parses what the user typed into it. The Alert lane for a
trade the league has already made (issue #31) maps a transaction row
into the same three cases rather than growing a second vocabulary for
the same three things. A system that can describe a trade one way in
chat and another way in a notification will eventually describe the same
trade two different ways to the same reader.

## The tool takes names, not a transaction id

`evaluate_trade` is asked about a trade that has not happened, so there
is no transaction to read. It takes the partner's name and the two
sides as the user typed them, resolves each player reference through the
one `NameMatch` rule ADR-0003 fixed, and refuses rather than guesses
when a reference is ambiguous.

A player named on the wrong side - one the partner does not roster, or
one the user does not - is still priced, with a note saying whose roster
he is actually on. The user asking about a player he has misremembered
wants to be told which player he means, not handed an error.
