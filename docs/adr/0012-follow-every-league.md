# ADR-0012: Otto follows every league the owner plays in

Status: accepted (2026-11-17, issue #235)

Otto was built for one Sleeper league, named by `otto.league-id`. The owner plays in several, so Otto now follows every league his Sleeper account is in this season. Every feature works per league: the scheduled Checks, Alerts, the waiver board, the news and market-value jobs, player decisions, habits, market values, Decision Records, Settings and Mutes. This ADR records how Otto finds the leagues, how it keeps each league's state apart, how one Check covers them all, and how a chat answer picks its league.

The rule that shaped every choice below: the league in `otto.league-id` behaves exactly as it did before. Its stored documents keep their names, its Alerts read the same when it is the only league, and nobody has to run a migration.

## Finding the leagues

Otto reads the owner's leagues from Sleeper's `/v1/user/<otto.user-id>/leagues/nfl/<season>`, where the season is the one `/v1/state/nfl` names. The league in `otto.league-id` is always the first followed league, whether or not that list names it. The other leagues follow in the order Sleeper lists them.

The list is read again every six hours (`otto.league-discovery-interval`), so a league the owner joins mid-season is picked up the same day. The Check does the reading, so no new schedule is needed. A failed read keeps the list from the last good one, and is tried again after the same interval, so a Sleeper outage costs no extra requests. When there has never been a good read, Otto follows the config league alone, which is exactly what it did before this change.

The owner can leave a league out, or bring one back, in chat. Otto keeps the leagues he left out in the same document as the list, so a later read of the list does not bring them back. He cannot leave out the last league Otto follows: an assistant that follows nothing would look broken rather than quiet. If a later list drops the one league he kept, Otto follows the leagues he left out again rather than none.

A best-ball league is not followed unless config names it. Sleeper sets a best-ball lineup itself, so lineup and waiver Alerts there would be advice nobody can act on.

The list lives in one small document, `followed-leagues`, in DynamoDB with the other small records.

## The league in scope

Every piece of work runs for one league at a time: one league's part of a Check, one button tap, one chat answer. The league is bound for the length of that work as a Java `ScopedValue` (`LeagueScope`). Every Sleeper read takes its league from that binding, and so does every stored document that belongs to a league. When nothing is bound, the league is the config league.

The other way to do this was to pass a league id through every method that reads Sleeper or the store. That is the more explicit design, and it is the one a new codebase should start with. Here it would change the signature of nearly every service in a 58,000-line codebase. The scope gets the same result with a change at four seams: the Sleeper adapter, the document store, the Check, and the two chat entry points. Code that has no idea leagues exist keeps working for whichever league is bound. The cost is that the league is implicit, so a test pins each seam (see Testing below).

A `ScopedValue` does not follow work onto another thread. Otto hands work to another thread in one place: `ModelChain` runs each model call on a virtual thread so that it can abandon a call that runs over. That hop carries the league binding with it, because a chat tool runs inside the model call and reads Sleeper.

## Keeping each league's state apart

A document either belongs to a league or is shared by all of them.

| Belongs to a league | Shared |
|---|---|
| `settings`, `mutes` | `conversation` (each turn notes its league), `chat-league` |
| `snapshot-current`, `snapshot-previous`, `last-check` | `watchlist` (a player is worth watching in any league) |
| `event-log`, `unsaved-run-outcomes` | `player-directory`, nflverse feeds, research, team reports |
| `habit-weeks`, `starter-points`, `role-slips` | `market-values-*` (kept per format, so per league already) |
| `watchlist-observations` | `waiver-preferences`, `team-directions`, `keeper-rules`, `waiver-comparisons` (each entry carries its league id already) |
| `alert-delivery-key:*`, `alert-delivery-lease:*` | `alert-delivery-id:*`, `alert-id-sequence`, `board-options-sent-*` |
| | Decision Records (their keys carry the league id already) |

The config league keeps every document name it had. Another league's copy of a document is the same name with `@league-<id>` after it, for example `settings@league-1180001`. `DocumentPlacement` reads the name without that suffix, so a league's copy is stored in the same place as the config league's. The rule is in one class, `LeagueDocumentNaming`, and the store applies it on every read, write and lease.

This makes the move from one league to many free: nothing is renamed, nothing is copied, and the documents already stored are the config league's.

## Alerts, boards and buttons

Alert dedup keys repeat across leagues: two leagues can have the same illegal-lineup week. The outbox's key and lease documents belong to a league, so each league dedups its own Alerts. Alert ids stay global, from the one `alert-id-sequence`, so a button's callback data (`done:42`) still names exactly one Alert and still fits Telegram's 64-byte limit.

Each delivery record keeps the league it was sent for. A tap looks up the delivery by its Alert id, binds that league, and only then acts, so Done, Ignore, Mute and View all options act on the league the Alert came from. A delivery stored before this change has no league, which reads as the config league, and that is where it came from.

When Otto follows more than one league, every message it sends for a league starts with a `League: <name>` line. The Telegram client adds the line for whatever league is bound, so Alerts, waiver boards, follow-ups, fix confirmations and Otto's own outage reports all name their league without each one remembering to. When Otto follows one league, nothing is added, and every message reads as before.

## One Check, every league

The Check still runs once a minute in one Lambda invocation (300-second timeout, a 240-second model budget from #150). Inside it, Otto updates the Player Directory once, refreshes the league list when it is due, and then runs each followed league in turn with that league bound.

All the leagues share the Check's model budget, and the existing rule still holds: a model run starts only while its whole deadline fits in what is left, and anything that does not fit waits for the next minute. To stop the first league from eating the budget every minute, the order rotates: each minute starts one league further along the list. A league that throws does not stop the leagues after it. Its error is logged and raised again once the others have run, so the Lambda still reports the failure.

The work that calls no model costs a few conditional Sleeper GETs per league per minute, almost all of them `304 Not Modified`. Three leagues stay far below Sleeper's published limit of 1,000 requests a minute.

## Which league a chat answer is about

A chat message picks its league before the Ask loop runs, in code, with no model call:

1. A message that names a league is about that league. It names one by its whole name, or by a distinctive word of its name or its format followed by "league", "team" or "one" ("the Canes league", "my dynasty league"), when only one followed league has that word or format. A bare "Mahomes" or "dynasty value" names no league, so a league called "Mahomes Mafia" does not take over a question about the player.
2. A message that says "every league", "all my leagues", "both leagues" or "all my teams" is about every league. "All of them" and "both" count only as a short reply to "which league?": in a question they usually mean players.
3. Otherwise the message is about the league the chat was last about, which Otto keeps for 24 hours, the length of the conversation window. After an answer about every league, the chat is about no one league, so the next question that names none is asked about.
4. Otherwise, when Otto follows more than one league, it asks which one, and lists them by name. A short reply that names a league, even by a bare word ("dynasty"), or says "all of them", answers the question that was waiting.

With one league followed, these steps always pick it, so the Ask loop sees exactly what it saw before.

Each conversation turn records its league, and the Ask loop's window shows the model only the turns about the league in scope. An answer about one league's roster never steers an answer about another's. A turn stored before this change has no league, which reads as the config league.

"All of them" runs one Ask loop per league, each with its league bound, at the same time on separate virtual threads. Each answer starts with its league's name, and each goes out as its own Telegram messages, split inside Telegram's 4,096-character limit. Running them side by side keeps the reply inside the webhook's 60-second timeout. Run one after another, three leagues could take three times the Ask deadline.

The owner manages the list in chat with `/leagues`, or in words: "which leagues do you follow", "leave out the office league", "bring back the office league". These are recognized in code before the Ask loop, so they cost no model call and work when the model is down.

## Cost

Model cost grows with what the owner sees, not with the number of leagues:

- A Check calls a model only for an Alert or a board that is about to go out. Two leagues with Alerts in the same minute cost two runs, which is what two Alerts cost before.
- League choice, the league list commands and the league heading are code, not model calls.
- "All of them" costs one Ask loop per league. It is the only path that multiplies cost, and only when the owner asks for it.
- Discovery costs one Sleeper GET every six hours.

## Testing

The scenario tests run two leagues side by side, a redraft league and a dynasty league, through the same stub Sleeper. They cover an Alert in each league that names its league, a button that acts on the right league, an "all of them" weaknesses answer, leaving a league out in chat and bringing it back, and the config league unchanged when it is the only one. Every scenario test written for one league still passes unchanged, which is the check that the config league behaves as before.

## Not done here

- A league that Sleeper renews for a new season gets a new league id. Otto follows the new id once the next season's list names it, but the config league keeps its old id until `otto.league-id` changes. Linking a renewed league to the old one through `previous_league_id` is left for a later change.
- Defense-versus-position is built once a night in the config league's scoring and shared by every league. Its ranks barely move between scoring formats, and a per-league table would leave a newly found league without one until the next night.
- The Watchlist is shared by every league. A per-league Watchlist would need its own chat wording, and nobody has asked for it.
