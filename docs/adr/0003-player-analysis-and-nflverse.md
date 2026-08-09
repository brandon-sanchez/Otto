# ADR-0003: Player analysis and the nflverse pipeline

Status: accepted (2026-08-08, issue #14)

The spec pins the nflverse sources, their refresh cadence, the
defense-versus-position table and the two player tools. It leaves the
semantics of each open. This ADR records what implementation decided.

## Rank 1 is the toughest defense, and the words say so

A points-allowed rank has no natural direction: half the fantasy world
ranks the softest defense first and the other half the toughest. The
table fixes it at rank 1 allows the fewest points - the toughest
matchup - so a high rank reads as a soft one.

A bare rank still carries no direction to a reader, so the reason
string spells it out: "DET allow 32.5 points per game to RB - 4th
toughest of 4 (2026 season to date through week 1)". The count of
defenses rides in the sentence because a rank means nothing without
the size of the field, and early in a season the table can hold fewer
than 32.

## The table ranks QB as well as RB, WR and TE

The spec names RB, WR and TE plus a run-defense rank. This league is
Superflex, so two of nine starting slots take a quarterback and "who
has the better matchup" is a quarterback question as often as a running
back one. Ranking all four positions the Player Directory keeps costs
nothing extra and closes a gap the spec's ESPN-style shorthand left.

Run defense stays as the spec has it - rushing yards allowed per game,
ranked the same way round.

## The weekly stats feed tracks one season at a time

Week 1 has no played week of its own, so the spec says it uses last
season's final ranks. Rather than hold two seasons and choose at build
time, the hourly update resolves the season it needs from the NFL week
and downloads that one file: the prior season while the week is 1, the
current season from week 2 on. The stored document records which season
it holds and whether it is a prior-season final, so the nightly build
labels the table without asking Sleeper anything.

The changeover is self-correcting: when the week rolls to 2 the asset
name changes, so its stored timestamp no longer matches and the current
season downloads on the next hourly check.

The rollover the other way needs help, because it changes nothing on
the wire. In week 1 of the next season the same finished file answers
again, at the same publish timestamp it has carried since the season
ended - so the timestamp check says "unchanged" and would leave the
stored copy labelled as a season to date. The unchanged branch
therefore rewrites the prior-season flag from the current week rather
than only moving the checked-at time. Rows follow the file; the label
follows the calendar.

## Timestamp checks for the releases, an ETag for the mapping

nflverse publishes as GitHub release assets, and the release index
carries an updated-at timestamp per asset. That timestamp is the whole
hourly check: the season files run to tens of megabytes and are
republished at most once a day, so an unchanged timestamp costs one
small JSON read instead of a download.

The DynastyProcess mapping has no release index - it is a plain file in
a repository - so "download only on change" is a conditional GET on its
ETag there. Both express the same rule with the mechanism each source
actually offers.

## nflverse rows arrive in Sleeper's stat vocabulary

Stat columns are translated to Sleeper's stat keys (`rushing_yards` to
`rush_yd` and so on) as the download streams past. Everything
downstream then prices through one `LeagueScoring`, so a projected week
and a played week are counted the same way, and nflverse's own
`fantasy_points_ppr` column is as unusable as Sleeper's `pts_ppr` -
no scoring setting names either.

Team codes are normalized the same way and for the same reason:
nflverse writes the Rams "LA" where Sleeper writes "LAR", and a join on
team that missed a franchise would fail silently.

## A comparison is two estimates, so it rides at Medium

ADR-0001 fixed that a point edge is always Medium confidence: a
comparison of two estimates is doubt by nature, whatever its size.
`compare_players` is exactly that comparison, so it inherits the rule,
and a gap inside the edge threshold is voiced as a coin flip with a
lean.

A player who cannot play - ruled out, or on bye - is not an estimate.
When one of the two cannot play and the other can, the call is a
certainty and rides at High. This is the same line ADR-0001 drew
between a ruled-out starter and a projection difference.

## A comparison reaches the whole Player Directory

The lineup tools resolve a named player against the user's own roster,
because they can only ever move a player he has. A comparison and a
news question reach anyone - the waiver target, the trade candidate,
the player another manager just dropped - so they resolve against the
Player Directory instead. One matching rule serves both (`NameMatch`);
only the candidate set differs.

Ambiguity is never resolved by guessing. The published feeds carry two
players called Justin Jefferson, which is also why every join to
nflverse goes through the id mapping and never through a name.

## A Mute never blinds an Ask

A Mute silences one player's news as a notification class. It does not
touch `get_player_news`: the user asking a direct question is the
opposite of an unwanted interruption, and answering "you muted him"
would be the assistant refusing to answer its own user. This follows
the spec's own line - Mutes silence notifications, they never cancel a
Recommendation.

## The cadence rule ADR-0002 deferred is now implemented

ADR-0002 recorded that "a tool fetches live only when its data is older
than its cadence interval" was not implemented, and that it needed a
per-source fetch clock the Check must not share. That gap is closed
here.

The ETag cache now remembers when each body arrived, and
`SleeperAdapter.cachedWithin(maxAge)` hands back the same wire with a
gate in front: a stored copy younger than the cadence interval answers
without a request. The Check keeps its unguarded reader, because
polling at the Freshness Ceiling is what a Check is. The Ask lane uses
the gated one, so an ordinary question now costs no Sleeper requests at
all rather than a handful of 304s. A 304 refreshes the stored copy's
arrival time, since the answer confirms it is current now.

`get_player_news` is the deliberate exception and is never cached: the
user asks for news precisely when something has just happened, and a
minute-old answer would be the wrong one.

## The nightly build writes nothing rather than an empty table

When the weekly stats have never landed, the build self-reports and
leaves the stored table alone. An empty table would read as "every
defense is equal", which is a claim the data does not support; a
missing one lets `compare_players` say plainly that it cannot see
matchup context, which is the honest answer.
