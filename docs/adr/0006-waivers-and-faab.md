# ADR-0006: Waivers and FAAB

Status: accepted (2026-08-08, issue #17)

Amended (2026-08-09, issue #33): the Role Tag rules are rewritten. A
breakout is now read from the player's own share of his offence, and
the designation route is split into an absence that ends the season and
one the player comes back from. The sections below marked as revised
replace what #17 decided; everything else stands.

The spec pins the waiver weights, the three role tags, the FAAB bands
and the Tuesday cadence. It leaves the arithmetic behind several of
them open. This ADR records what implementation decided.

## The 50 projection points scale to one free agent, not to one position

The spec scales the projection component "so the best available free
agent's value earns 50". Points above replacement already answer
"compared to what" inside a position, so the scale that follows is a
single one across all four: the best free agent on the whole board
takes 50 and everybody else takes a share of him.

Scaling per position would say a thin position's best free agent is
worth the same as a strong one's, which is the one thing points above
replacement exist to prevent.

The scale is also computed over every position whatever the question
narrowed to, and so are the replacement levels and the roster-need
read. A player's score must not move because the user asked about his
position alone: one board, whatever asks for it.

## Replacement level has one definition, and this is not it

The ranks that set replacement level - QB 24, RB 24, WR 24, TE 12 -
are `otto.lineup.PositionCutoffs`, configured once as
`otto.replacement-cutoffs` and read by the league-analysis tools as
well as by the board. The waiver score does not restate them. A second
copy would drift from the first the day either moved.

They are deliberately not the Notable Player cutoffs, which the chat
can change and which sit at QB 12. The two lines answer different
questions: a Notable Player is one whose drop is league news, and
replacement level is what a startable player is worth. Superflex
starts two quarterbacks, so about 24 of them are startable even though
only the top 12 are news. Reading the chat-settable line here would
quietly halve the quarterback replacement rank.

That leaves the replacement ranks configurable but not chat-settable,
which is where #15 put them. If the user ever needs to move them by
chat, they follow the Notable cutoffs into the Settings document; the
board reads whatever `PositionCutoffs` it is handed either way.

## A table thinner than the cutoff sets replacement from its lowest rank

Replacement level is the projection of that cutoff rank. Early in a
week, or in any fixture, fewer
players than that carry a projection. Replacement then reads off the
lowest projected player at the position, and the board says so in
words: "only 8 RBs are projected this week, fewer than the RB24
cutoff, so the lowest sets it".

Degrading is right here and abstaining is not, because a replacement
level of zero would price every scrub as a starter and nothing on the
board could be scored at all.

## A breakout is a share of the offence, not a rank (revised, #33)

#17 read the usage breakout as "two consecutive weeks of top-24
positional usage", and had to add a rule that a week with fewer than 24
stat lines at a position abstains, because a top-24 claim over eight
running backs is not a top-24 claim. That workaround is a symptom. A
rank is a comparison against everybody else's week, so it moves when a
blowout or a pass-heavy script reshuffles the busiest two dozen players
without any role changing.

A share does not move that way. It compares a player against his own
team in the same game, so it is self-normalising, it can be read
against a fixed bar, and the thin-table abstention rule is deleted
rather than kept: there is no table to be thin. The board can now read
a breakout off one played week in a fixture of sixteen rows, which is
what week 1 of a real season looks like too.

The rank also read the wrong thing. Two real cases show it. Kyren
Williams began 2023 as the number 2 back with nobody injured anywhere,
took the job on merit in week 1 and is the best waiver pickup in
fantasy history; the injury route cannot see him at all. Puka Nacua
caught 10 of 15 targets in week 1 of the same season, about 39% of his
team's targets, and the two-week route would have missed the only
waiver run that mattered. Both men kept the role. What they have in
common is that they earned it, and what predicts that is their own
share of the offence.

## The two lanes, and the numbers on them (new, #33)

Either lane is enough, and both are named constants in
`otto.waivers.BreakoutLanes`.

The **fast lane** asks one game at the elite bar, because that is where
the league winners are and waiting a second week means bidding against
everybody else who waited.

- `FAST_TARGET_SHARE` = 25% for WR and TE. A season target share above
  25% is the published line for an elite receiver - fewer than eight
  men in the league sustain 30% with the air-yards lead - so one game
  at that rate is the offence treating him as its first read.
- `FAST_OPPORTUNITY_SHARE` = 65% for RB. The published line for the
  lead back of a two-man committee is 65-70% of the carries, so a back
  at 65% of his backfield's work is the lead back and not the better
  half of a committee.

The **slow lane** asks two consecutive games at the bar of a real role,
which is the role that grows rather than arrives.

- `SLOW_TARGET_SHARE` = 18% for WR and TE. A season target share above
  20% is associated with WR1 finishes; 18% held over two straight games
  is the week-to-week reading of the same line.
- `SLOW_OPPORTUNITY_SHARE` = 50% for RB. Half a backfield's work is the
  point at which a committee has a lead back at all.

`TREND_GAMES` = 3. A share that rose every week across three straight
games goes in the reason string whatever the tag, because a role
growing in front of the user is worth showing him before it crosses a
bar.

Four rules keep the lanes honest. A player's newest game has to be the
newest week the feed holds, or the claim is about a role he had a month
ago. The games have to be in straight weeks, so a bye or an absence
does not get read as consecutive. No lane fires for a player whose own
designation rules him out this week: that is the week his role ended,
whatever last week's share was.

The fourth rule is about which season the feed holds. Before any week
of this season is played, the stats feed is deliberately last season's
final record, because a defence table has to say something in week 1
and last season is the honest thing for it to say. A breakout is the
opposite case: "he took 91% of the backfield" is a claim about a role
now, and last December is not now. So the lanes read nothing at all
while the feed is the prior season's, and the board says that no week
of this season has been played - which is a different note from the one
a missing feed earns, because they are different facts. The staleness
rule above cannot cover this on its own -
the newest week of last season's file is week 18 of last season, which
looks current to any rule that only compares weeks.

A quarterback has no lane. Nothing a quarterback does divides into a
share of his own offence, and no waiver tag rests on one.

## Where each share really comes from (new, #33)

The column names were read off the published files before any threshold
was pinned, because a threshold against a column that does not exist is
worse than no threshold at all.

| Input | Release | Asset | Column |
|---|---|---|---|
| Target share | `stats_player` | `stats_player_week_YYYY.csv` | `target_share` |
| Carries | `stats_player` | `stats_player_week_YYYY.csv` | `carries` |
| Targets | `stats_player` | `stats_player_week_YYYY.csv` | `targets` |
| Roster standing | `weekly_rosters` | `roster_weekly_YYYY.csv` | `status_description_abbr` |

Target share is nflverse's own per-game column and needs no arithmetic
here: it is already his targets over his team's targets in that game,
which is the real team denominator the tag asks for. It is no league's
scoring key, so it rides beside the translated stats rather than inside
them, and a blank or an "NA" reads as absent rather than as zero - a
zero would say the offence never looked at him, which is a claim the
file did not make.

Opportunity share has no published column, so it is computed from the
two that are already downloaded: a back's carries plus targets over the
same total for every back his team played that week. The denominator
comes from the same rows, so it is a real team's real game.

Both new reads join the fail-soft list that "Only the projections are
load-bearing" sets out, and each one has its own note rather than a
shared one. No weekly stats feed means no share can be read at all. No
week of this season played means the shares on disk are last season's,
which is a different fact and a different sentence. No roster standings
means nothing is season-ending. None of the three stops a board being
priced.

#17 judged that a snap-count download was not worth a second hourly
fetch. That judgement stands and is now moot: `snap_counts` is not
added, because the share the tag actually wants was in the file the
system already downloads. The only new download is `weekly_rosters`,
and it is there for a different question.

## An absence with a return date is a loan, not a breakout (revised, #33)

#17 made any multi-week designation - IR or PUP - a breakout on its
own. Modern IR carries a four-game return designation, so "on IR" no
longer means "gone", and paying a breakout price for a four-game loan
is how a budget disappears. The question that matters is whether the
man ahead has a date he comes back on.

Sleeper cannot answer it. Its players file publishes a single "Injured
Reserve" status with no return designation, so the split is read from
nflverse `weekly_rosters` instead, whose `status_description_abbr` is
the league's own roster-standing code. Only two codes are named as
season-ending: `R01`, reserve/injured with no return designation, and
`R02`, reserve/retired.

The codes are published without a key, so each one was read off the
feed itself. Over the 2024 season, a player is active again in a later
week:

| Code | Meaning | Active again later |
|---|---|---|
| `R01` | Reserve/injured, no return designation | 18% |
| `R02` | Reserve/retired | 4% |
| `R04` | Reserve/PUP | 59% |
| `R48` | Reserve/injured, designated for return | 75% |

### PUP and the non-football lists are not season-ending here

#33 named PUP and the non-football lists as season-ending. This ADR
does not follow that list, and the deviation was put to the user with
the rates above and approved by him explicitly.

The deviation follows #33's own principle rather than departing from
it. The ticket says the distinction that matters is "does he revert
when the starter comes back". Applied to the feed instead of to a
guess, that principle puts `R04` with `R48` and not with `R01`: a man
on PUP is back inside the season more often than not. Naming PUP
season-ending would keep the ticket's words and break its rule.

What settled it is that the two errors do not cost the same, and the
asymmetry is severe.

- Calling PUP season-ending when it is not tags a breakout on a
  four-week rental. That is a $50 bid on a man who gives the job back,
  and the money is gone.
- Calling PUP returning when it really was season-ending costs a $5 bid
  on a genuine breakout. The fast usage lane then catches him the
  following week, at 25% or 65% of his own offence.

One error has a safety net inside this same feature. The other has
none. The set is built around that.

Rarer codes are deliberately left unnamed. The file carries a long tail
of reserve codes that appear a few dozen times a season, too few to
measure and published with no key to read them by, and naming one on a
guess is how a wrong bid gets made. The error the two directions cost
is not the same. A season-ending code this set does not know costs a
breakout the board should have tagged, which the usage lanes may catch
anyway. A returning code wrongly named season-ending costs real money
on a man who is back in four weeks. The set errs the cheap way, and it
grows only when the feed itself gives a number to grow it on.

The standing therefore has three states, not two, and the board says
which one it is. A named code means the season is over. Any other code
means he comes back. No row at all - a feed that never downloaded, or a
player it has never seen - means the board does not know, and it says
so on the man's own line rather than printing either claim. A read that
collapsed "he comes back" and "I cannot tell" into one answer would put
a fact on the board that no feed supplied, which is the failure this
system treats as worse than silence. Only the first state tags a
breakout, so a board with no standings tags none.

The second state is worded as "not on a list that ends his season"
rather than as "he comes back on a date", because only some of these
codes name a date. A man who is merely Out for Sunday carries the
active code, and promising a reversion date for him would be the same
invented fact in a smaller way.

Two rows can share a player's newest week, because a man claimed or
traded inside a week appears twice. The order the file happens to list
them in is not a fact about him, so a tie is settled by the code and
settled the conservative way: the standing that does not end his season
wins. Only the four positions the Player Directory keeps, and only
regular-season rows, are stored at all - the published file carries the
whole league down to the offensive line, and none of it can be the man
ahead on a chart this system reads.

The 10 usage points are unchanged and still key off the Player
Directory's own health: those points say the man ahead cannot play this
Sunday, which is true either way. Only the tag reads the standing.

## Snap share is still not needed (revised, #33)

The spec's usage breakout reads "snap share plus touches or targets".
Carries and targets are translated into Sleeper's own `rush_att` and
`rec_tgt` keys on the way in like every other column, so one vocabulary
serves a played week and a projected one. This league scores neither,
so no point total changes.

#33 asked for #17's judgement on the `snap_counts` download to be
revisited, because #17 had refused it as a second hourly fetch for a
signal that moves with touches anyway, and because the tag it feeds is
the difference between a $5 bid and a $50 one. It has been revisited.
The answer is unchanged, and the reason for it is new.

`snap_counts` is still not downloaded, and this time not because the
cost outweighs the signal. It is because the signal the tag actually
wants was already in a file the system downloads: `target_share` is a
column of `stats_player_week`, and opportunity share is arithmetic on
two more columns of the same file. The question #33 raised - "the data
already exists, no scraping needed" - is answered in full, and it is
answered without adding the feed the ticket assumed would be needed.
The one new download is `weekly_rosters`, and that is for the
designation split, not for usage.

Snap share would still say a different thing if it were fetched: it
measures how often a player was on the field, where target share and
opportunity share measure what the offence did while he was there,
which is the thing the tag is trying to see. If the lanes prove noisy
in use, that download remains the fix.

## A stream is a matchup that explains more than half the edge

The spec defines a stream as a projection that "comes mostly (over
half) from this week's matchup context, not from the player's role".
The defence-versus-position table makes that arithmetic, and the units
have to line up for it to mean anything.

The table counts what a whole position group scored against a defence,
so the excess a soft defence gives up cannot be handed to one player
as points - a team's third receiver does not inherit the lift its
first one earns. The excess is therefore read as a ratio to the
average and applied to the player's own projection. That leaves the
matchup's share in his units, which is what makes it comparable with
his points above replacement. Over half of that edge explained by the
matchup is a stream.

Without the table nothing can be shown to be a matchup play, so no
candidate is tagged a stream and the board's notes say why. An unknown
matchup is never called a soft one.

## A garbled budget stops the document; it never reads as zero

Sleeper's `waiver_budget` and `waiver_budget_used` are whole numbers
today. A value that arrives as something else - a string, a fraction,
a number too large - now fails the document it came in, the same way a
missing `roster_id` or a drifted `start_time` already does.

Reading it soft was the tempting option and the wrong one. Money is
the one number a silent zero must never stand in for: an unreadable
budget would price every bid at $0 and call it advice, and unreadable
spend would offer the user money he has already spent. Both are
confident wrong answers about his own team.

The cost is real and is accepted here: the budget rides in the league
and rosters documents, so failing on it also costs that Check its
Snapshot. The user hears which feed broke, the previous Snapshot
stands, and the next Check recovers - which is the behaviour the spec
asks for when a source goes bad. The alternative, a per-field result
type for two integers, would be more machinery than the problem
deserves and the only field in the adapter that worked that way.

## Chart dates are parsed, not compared as text

Which depth chart is newest decides which one describes this week, and
the rank a player held on the one before it is what the waiver score
reads as a promotion. Comparing the raw `dt` strings agrees with the
calendar only while the format never moves: a bare date beside a
timestamp, or an offset beside a Z, would reorder the snapshots
silently and hand the promotion to the wrong chart.

So `dt` is parsed to an instant, in any of the three shapes the column
has been seen in - an offset timestamp, a bare local timestamp, a bare
date - and a value none of them read is drift that fails the download.
Sorting it as text instead would keep the feed and lose the meaning.

## Who was ahead on the chart is read from the chart before this one

A depth chart that already shows the promotion has nobody above the
promoted player, so "the player ahead of him went to IR" cannot be
read off the newest chart alone. The stored chart therefore carries
each player's rank on the chart published before it, and "ahead" means
above him now or above him then. Nothing else about the older chart is
kept.

The two rules the spec draws are kept apart. Ten usage points go to a
candidate whose man ahead is Out or worse. The breakout tag asks for
more than that, and what it asks for is above, under "An absence with a
return date is a loan, not a breakout".

## Negative news wins, inside an item and across the feed

The news component has to read words, and the model may not: it owns
language and never a number. So a keyword read of Sleeper's headline,
report and analysis decides it, coarse on purpose and deliberately
pessimistic. A bad word anywhere in an item makes the item negative,
and a negative item anywhere in the window makes the feed negative.

A player who is the new starter and also questionable is not a player
to bid on, and reading it the other way round would pay 15 points for
exactly the pickups that burn a budget.

## News is read for a shortlist that says where it stopped

News is one live request per player and there are hundreds of free
agents, so it is read last and only for the candidates who could still
reach the answer. A candidate whose best possible score - his score
with all 15 news points - sits below the worst possible score of the
last candidate already in the answer cannot enter it however good his
news is, so his feed is never read.

That rule is exact, and where it stops so is the board. It is also
bounded at twenty-five reads, because one question must never storm
the feed. When the bound cuts the set short the board says so in its
notes and each unread candidate carries the reason on his own line, so
a zero the reader would take for a checked feed never appears. Only
the bound is a compromise; the rule above it is not.

Reading the whole field instead would cost hundreds of live requests
per question. If waiver questions ever need more reach than this, the
answer is a cached news pass on the Check loop, not a longer wait on
the chat.

## The stream cap holds, and the slot bump lands on top of it

The FAAB adjustments are ordered, and the order is the argument. A
breakout moves up one band. A stream is then capped at 10% of what is
left. The five-point bump for a pickup that fills a slot the user
cannot legally fill this week is applied last, so it lifts even a
capped stream: the cap says "do not pay up for a matchup", and the
bump says "this one plugs a hole you actually have". A capped stream
that fixes a bye-hit slot bids 10-15%, not 10%.

The spec pins the breakout raise for the top two bands only. The
ladder is applied uniformly - every band moves up one rung, with the
top band's raise pinned at 40-60% as written - so a breakout is priced
the same way whatever it scores.

## The Tuesday evening is a calendar date, not an offset

The Alert instant is 18:00 put on the Tuesday's own date in the
America/Los_Angeles zone. The zone's rules then decide the offset, so
the September board lands at 01:00Z and the November one at 02:00Z
with no offset written down anywhere. The zone is pinned in the code
rather than read from the clock, because the Check runs in UTC.

Every Check asks one question - has the most recent Tuesday 18:00
passed, and does the Event Log already hold that Tuesday's key? - so
the 1-minute loop plus one key is the whole timer, and no scheduler is
added.

## The board answers to the same two switches as every other Alert

`Trigger.WAIVER` is the board's own trigger, so the Settings document
switches it off like any other and a Mute silences `class:waiver`
alone. Without the trigger check the user could turn the board off by
chat and still get it every Tuesday; without a class of its own the
Mute tap would fall through the cascade in `AlertActions` to the
legality class, and a user who wanted one quiet Tuesday would lose
every illegal-lineup warning instead.

The two switches differ in what they cost. A Mute silences the message
and the board is still computed, which is what a Mute means everywhere
else in this system. A trigger switched off stops the board being
built at all - there is no Recommendation to keep, and building one
would spend live news requests on an answer nobody will read.

## A board that missed its evening is never sent late

Claims clear on Wednesday. A board that arrives the morning after is
advice about a deadline that has passed, so the board goes out on the
Tuesday it is due or not at all: the cutoff is midnight at the end of
that Tuesday, local. The Event Log stays empty for that Tuesday, which
is the honest record of a board that never went out.

The cutoff is the calendar day rather than a count of hours, for the
same reason the 18:00 instant is. A duration would have to be picked
against one of the two offsets and would be wrong under the other; the
end of the local Tuesday is the end of the local Tuesday in both.

That leaves the whole evening to retry. A Check that could not reach
the projections at 18:00 keeps trying every minute until midnight,
because a board at 21:00 is still a board the user can plan Wednesday
from. Tightening this to a few minutes of scheduler jitter would trade
a real outage - no board at all that week - against a risk the
midnight cutoff already removes.

A board that could not be computed records nothing either, and neither
does one with nobody on it: "no targets this week" is not worth a
text. Both leave the next Check inside the window free to try again,
and only a board Telegram accepted is written down.

## Two limits the source and the medium impose, both spoken

Trending is a top-N list, not a per-player counter: Sleeper publishes
the most-added players and nothing else. A player outside the list
scores no trending points, because the source has no count for him to
log-scale. That is the source's limit, not a rule of the score.

How deep the list runs is the Watchlist watcher's choice, not the
board's: both read the same window and the same top twenty-five, so
one poll and one cached body serve both. Asking for a different depth
would double the requests to say the same thing.

"Any count" is honoured up to fifty targets. Past that a chat reply
stops being a message and starts being a spreadsheet, and the news
bound above would leave most of the tail unread anyway. A request for
more is answered up to fifty and the board says in its notes that it
was cut, so the user knows he asked for more than he got.

## Only the projections are load-bearing

Every other input fails soft into a note: no depth charts means no
usage points, no defence table means no stream tags, no trending means
no trending points, and each says so on the board. The projections are
half the score, and a board without them would be a ranking of rumour,
so the tool answers that it cannot price one instead.
