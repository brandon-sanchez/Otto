# ADR-0008: Trade evaluation

Status: accepted (2026-08-09, issue #18)

The spec pins the valuation formula, the verdict bands and the leverage
note. It leaves the shape of each open: what "rest of season" reads
from, which roster a fit factor is measured against, what a percentage
is a percentage of, and what happens to the parts of a trade that are
not players. This ADR records what implementation decided.

The first acceptance criterion was reopened during implementation. It
priced each player once, against whoever received him, which forced the
two teams' nets to mirror each other and made a trade that is good for
both impossible to describe. The user replaced it with the four-way
model recorded below.

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

A player is worth his rest-of-season points above Replacement Level,
times a positional scarcity multiplier, times a roster-fit factor:

- Above Replacement Level. A player is priced against what the manager
  could have for nothing, not against zero, and it is the same
  Replacement Level the waiver board already uses: the projection at the
  position's cutoff rank. That rank is read from the current week's
  table and multiplied by the weeks that are left, rather than summed
  week by week. Replacement Level is a line, not a person - the free
  agent who fills it is a different player every week, and none of them
  has a bye that matters, because the manager claims whoever plays. A
  manager who loses a
  player does not field nobody; he claims the best free agent there is.
  This is also what makes a fourth running back correctly cheap to a
  manager who already holds three: the points that put him above a free
  agent are the only points he can sell. A player under the line prices
  at zero and never below it, because the buyer can have that player for
  a waiver claim.
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
  reading him, 0.90 when somebody better at his position is already on
  that bench ahead of him, and 1.00 otherwise, which is the first man
  off the bench and real cover.

Fit is answered by the optimal lineup rather than by a count of players
at the position, because the flex slots decide it: a fourth receiver
starts in a league with two flex slots and does not in a league without
them. It runs the same `LineupOptimizer` the lineup tools already run,
so a trade and a start-sit call cannot disagree about who starts.

Every lineup in that arithmetic is filled out with replacement-level
players, one per position per starting slot. Leaving a slot empty would
price a lost starter as a total loss and make every trade away look
ruinous, when in fact the manager claims a free agent on the Tuesday.

## Four numbers, not two: each team is priced against its own roster

The trade is priced four times:

- the user's gain: what the arriving players are worth against the
  roster he would then hold;
- the user's cost: what the leaving players were worth against the
  roster he holds now;
- the partner's gain and the partner's cost, the same two questions
  asked of the partner's own roster.

Each side's net is its gain less its cost. The two nets are not mirrors
of each other and are not meant to be. A manager gives from a surplus
and receives at a hole, so the same two players can be worth more to
both teams than what each gives up - and both nets come out positive.
That is not a flaw in the arithmetic; it is the only reason anybody
ever agrees to a trade. A model that made one team's gain the other's
loss by construction could never explain a trade happening at all.

An earlier reading priced each player once, against whoever received
him, which forced the two nets to mirror. It was replaced on the user's
instruction, and the acceptance criterion was reopened to allow it.

ADR-0004 fixed that a team is judged the same way whichever team it is.
That still holds: the same formula, the same optimizer and the same
replacement ranks are used for both teams. What differs between them is
only the roster each is measured against, which is the whole point.

The leverage note reads both rosters through the same replacement-level
depth `get_team_roster` gives, rather than growing a second yardstick
for the team on the other side of the deal.

## The verdict is the user's, and only the user's

The Verdict Band is computed from the user's net alone. The partner's
net is reported beside it and feeds the leverage note, because it says
whether he would accept; it is never a reason to advise against a trade.

Otto must be able to say all four things plainly: good for you and good
for him, good for you and bad for him, even, and bad for you. The
second of those is the one worth writing down. A trade that is good for
the user and bad for the partner is still a trade the user should take.
The other manager is a grown adult who can read his own roster, and it
is not this assistant's business to protect him from a deal he offered.

The trade is the user's decision, so the verdict answers his question
and no one else's.

## Otto prices no draft pick, and the answer says so

Otto holds no dynasty rankings, no pick-value chart and no view of next
year's class, and a number invented for one would be the model's guess
dressed as arithmetic.

The alternative to a zero is not a better number, it is silence - and
silence is worse. A pick-for-player trade with the pick quietly dropped
reads as a robbery in whichever direction the players fall. So the pick
is carried through the answer as an asset priced at zero, with a note
saying the verdict cannot see it, and the narration says the same.

## FAAB is not part of a trade in this league

The user's league does not allow bidding money to move in a trade, and
his reason is a good one: whoever gives up more players replaces them
off the waiver wire for about a dollar, so FAAB in a trade is noise.

`TradeAsset.Faab` stays, because a user may still type "and $20 FAAB"
and the parser must not read that as a player it cannot find. It is
recognized and dropped. It is priced at nothing, carries no note, and
appears nowhere in the answer - unlike a draft pick, which is a real
thing this league trades and Otto simply cannot price.

## The band is a share of the user's larger side

The gap is the user's net divided by the larger of his own gain and his
own cost. Dividing by his gain alone would make the same trade read
differently depending on which way round he typed it, and dividing by
the sum of the two would halve every band. The partner's two numbers
never enter it.

- Inside 5%: even. The lean is stated, because the user asked, but the
  answer says in as many words that a coin flip is not a reason to act.
- 5% to 15%: a slight edge. Both boundaries fall here.
- Over 15%: a clear edge, at High confidence.

The band is decided from the gap rounded to the one decimal place the
answer prints, not from the raw quotient. A gap shown as 5.0% must not
be called even in one trade and a slight edge in the next; the reader
sees one number, so one number has to decide it.

ADR-0003 fixed that a comparison of two estimates rides at Medium.
A trade verdict at High is a deliberate exception to that, approved by
the user explicitly when the question was put to him. The reason is the
summing: `compare_players` weighs one week against one
week, where a projection's own error is the size of the answer. A trade
is weighed over every week left in the season, where the same per-week
error averages out, and a gap of more than 15% of the larger side is
larger than the error that is left. The two smaller bands stay at
Medium, which is where a one-week comparison sits.

## The net and the starting lineup answer two different questions

The answer also carries what each team's optimal starting lineup
projects over the rest of the season, before and after the trade. That
is the concrete consequence: what the team would actually score.

It counts no scarcity multiplier, so it can point the other way from
the priced net - a manager can improve his Sunday total and still have
sold the scarcer position too cheaply. The two are not in conflict and
the answer says so in as many words, because a reader who sees them
disagree without an explanation will read one of them as a bug.

## One shape for what a trade moves

A trade moves three kinds of thing, and Sleeper's transaction row says
so: players in `adds` and `drops`, picks in `draft_picks`, money in
`waiver_budget`. `TradeAsset` is the one type that holds all three,
whether or not this league trades all three.

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

Each player on one side is priced as the one player moving: the rest of
his own side comes off the roster he is read against. Two backs sent
together must not each be called buried behind the other when they both
leave.

A trade that names a player the team giving him up does not hold is not
a trade either manager could agree to. It is still priced, because the
user usually wants to be shown which player he means, but the verdict
drops to Medium and says so. A player carries his full price wherever
he is named, so leaving this alone would let a mistyped side buy a
High-confidence verdict for a deal that cannot happen.

A player named on the wrong side - one the partner does not roster, or
one the user does not - is still priced, with a note saying whose roster
he is actually on. The user asking about a player he has misremembered
wants to be told which player he means, not handed an error.
