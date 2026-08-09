# ADR-0006: Waivers and FAAB

Status: accepted (2026-08-08, issue #17)

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

## A cutoff the table cannot reach abstains from a usage breakout

The other cutoff reads the other way. The usage breakout rests on "two
consecutive weeks of top-24 positional usage", and a top-24 claim over
a week with eight running backs on record is not a top-24 claim - it
would tag every candidate a breakout. So when the week's stat lines at
a position number fewer than 24, the usage breakout does not fire.

Top-24 here is the spec's own number and is deliberately not the
replacement cutoff, which is 12 at tight end. The two ranks answer
different questions: replacement level asks how many starters a league
of this shape needs at the position, and a usage breakout asks whether
the offence is treating him as one of the two dozen busiest players at
his position in the sport.

The thin-table rules differ for the same reason. A replacement level
must produce a number or nothing works, so the conservative reading is
the worst projected player. A breakout is a claim about one player, so
the conservative reading is silence.

## Snap share is not in the feed, so touches and targets carry usage

The spec's usage breakout reads "snap share plus touches or targets".
Snap share lives in a separate nflverse release the system does not
download. `stats_player_week` carries carries and targets, so those
two - added together, and translated into Sleeper's own `rush_att` and
`rec_tgt` keys on the way in like every other column - are the usage
signal. This league scores neither, so no point total changes.

Adding a snap-count feed would be a second hourly download for a
signal that moves with touches anyway. If the tag proves noisy in use,
that download is the fix.

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
candidate whose man ahead is Out or worse. The breakout tag needs a
multi-week designation - IR or PUP - because Out is a Sunday, not a
season, and an aggressive bid on a one-week absence is how a budget
disappears.

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
