# ADR-0003: Player analysis and the nflverse pipeline

Status: accepted (2026-08-08, issue #14)

Amended (2026-08-15): the weekly stats feed resolves its season from
the NFL season type as well as the week, so the preseason reads last
season's final file. Everything else stands.

Amended (2026-10-04, issue #112): a release timestamp decides whether
to download, no longer what changed. Each stored feed now records which
games, team-weeks or team charts it holds and when each last changed,
and the schedule is stored as a feed to say what a week should hold.
The schedule also decides when the weekly stats and snap feeds move
from last season's final file to this season's, where the 2026-08-15
amendment read Sleeper's week and season type.

Amended (2026-11-13, issue #120): three feeds join the registry, the
play-by-play, ffopportunity's expected points and the player register,
and the stats, snap and FTN feeds keep more of each row. The section
"Free usage evidence, and what unknown means" records the decisions.

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
time, the hourly update resolves the season it needs and downloads
that one file: the prior season's final file until a game of this
season is final, the current season from then on. The stored document
records which season it holds and whether it is a prior-season final,
so the nightly build labels the table without asking Sleeper anything.

Whether a game is final comes from the stored schedule, which the
hourly run refreshes before the weekly feeds. Sleeper's week counter
cannot say it: it counts preseason weeks from 1, so the middle of
August reads as week 2 or later while no game that counts has been
played, and it took a live preseason to show that. Only when no
schedule has been stored at all does the counter stand in, as the week
and the season type together: the current season from week 2 on, once
Sleeper says the season is underway, and the prior season's final file
at every other time.

The changeover is self-correcting: once the schedule carries the first
result the asset name changes, so the stored timestamp no longer
matches and the current season downloads on that hourly check.

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

The timestamp says only that a file was republished. nflverse
republishes a whole season file at once, often daily, and corrects
stats through Wednesday, so a moved timestamp cannot say whether week
2's numbers changed or week 4 was appended. Each stored feed therefore
carries its coverage in the same document as its rows: one record per
unit (a game, a team's week, or for the undated depth charts a team's
current chart) with a fingerprint of the rows Otto keeps for it. A
download compares fingerprints with the stored copy, and only a unit
whose kept content moved counts as corrected. A reordered file, a
column Otto drops, or FTN re-pulling old games under a new
`date_pulled` does not. A depth chart is a snapshot dated by its own
`dt`: a chart with a newer date is new data and starts its record
afresh, and only the same date republished with other ranks is a
correction. A unit that drops out of a republished file keeps its
record as a tombstone, so one that returns is measured against what
Otto saw before. A copy stored before coverage existed, or under an
older content version, is downloaded once more to gain it.

Whether a week has arrived is measured against the schedule
(`schedules/games.csv`), which is itself a stored feed: a week is
complete in a feed when every game or team the schedule expects is
held, and anything the schedule cannot vouch for reads as unknown
rather than incomplete. That includes a week still being played for a
feed that waits on results, a week that has not yet started for a feed
published before kickoff, and a depth-chart week whose charts have
since been replaced by newer ones.

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

## Free usage evidence, and what unknown means

`PlayerUsage` joins every free usage source into one record per player
per regular-season game, keyed by gsis id:

- **Weekly stats** keep `air_yards_share`, `wopr`, `racr` and
  `receiving_epa`, and each line stores its team's targets and carries
  for that game. Carry share is the player's carries over the team's,
  counted across every position the file lists.
- **Snap counts** keep each player's team and raw offensive snaps beside
  `offense_pct`, so a team's backs can be set side by side.
- **Play-by-play** (`pbp/play_by_play_YYYY.csv`) keeps every target and
  carry. Two-point tries, kneels, spikes and plays a penalty erased are
  not opportunities. A red-zone play starts at or inside the opponent's
  20. An end-zone target is thrown at least as far as the goal line,
  so `air_yards` is at least `yardline_100`.
- **FTN charting** keeps the `read_thrown` code of every play with a
  throw. FTN's data dictionary describes the codes differently from
  the data. The real file puts `1` on the first read, and the code
  follows the real file and a fixture recorded from it. A first-read
  target is a play-by-play target whose game and play id carry code `1`.
- **ffopportunity** (`ffverse/ffopportunity`, release `latest-data`,
  `ep_weekly_YYYY.csv`) gives touchdowns beside expected touchdowns and
  fantasy points beside expected fantasy points. Its points use its own
  full-PPR scoring, so they compare players and never stand in for a
  league's points. The file has no season type, so weeks after 18 are
  dropped as playoffs.
- **The player register** (`players/players.csv`) gives birth dates,
  draft capital and the PFR id that joins the snap counts. Only skill
  players from this season or the last are kept. A player the file
  names no complete pick for has no draft capital: undrafted and
  unrecorded look the same in the file, so neither is claimed.

The register has no weeks, so it is one coverage unit, and readiness
answers unknown for it in any week.

Each part of a game record is null when its source does not hold the
player's team in that week. It is zero only when the source holds the
week and lists nothing for him. A back on the field for 35 snaps with no
touches has zero targets, but a week the snap file has not reached
has unknown snaps. A blank optional column, such as `racr` for a player
with no air yards, is null and never zero. A source stored for a
different season than the weekly stats is left out of the join.
