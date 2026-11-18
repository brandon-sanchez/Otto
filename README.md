# Otto

[![Build and deploy](https://github.com/brandon-sanchez/Otto/actions/workflows/deploy.yml/badge.svg)](https://github.com/brandon-sanchez/Otto/actions/workflows/deploy.yml)

A fantasy football assistant for every Sleeper league you play in. It watches your teams, texts you on Telegram when something needs doing, and answers questions in plain language.

Sleeper tells you a starter is out when you remember to open the app. Otto tells you before kickoff.

## What it does

- **Texts you first.** A starter ruled out, a defense or a starter on bye, an empty slot before lock, a bench player projecting better than the one you started.
- **Plans your waivers.** The evening before your league's claims are due, a board of the five best free agents, each with the evidence behind it, who to drop and what to bid out of the FAAB you have left.
- **Helps with player decisions.** When a player's status changes, Otto says whether to hold him, move him to IR or taxi, or drop him, and only offers moves Sleeper would allow.
- **Watches the league.** Trades, notable drops, and the moment another manager claims a player off your watchlist.
- **Answers questions.** Start or sit, where your team is thin, whether a trade is fair, who to buy low, what a player's ceiling is, and more. See [Ask it anything](#ask-it-anything).

## Code computes the numbers, the model explains them

Java computes every number Otto sends: projections, ranks, trade values, FAAB bids and the list of legal lineup moves. The model only reads those facts and writes the message.

For advice, the facts go to the model as an evidence bundle. Before anything reaches you, a step called the Advice Check holds the reply against that bundle. It checks every player name, number, rank, dollar amount and recommended move. A reply that fails gets one correction attempt. If that fails too, Otto doesn't send it.

When OpenAI is down, Otto asks Anthropic Claude, then Google Gemini. When all three fail, it sends a short message that code writes, never a guess.

## What it won't do

- **Change your lineup.** Sleeper's API is read-only, so Otto tells you and you tap. It checks a later snapshot to confirm the problem is gone.
- **Guess.** If a source is down it says so, rather than filling the gap with a zero. If no source gives an injury timeline, Otto says it doesn't know.
- **Nag.** One alert per problem, one repeat 30 minutes before that player's game locks, nothing after. Mute anything you would rather not hear.
- **Talk during your draft.** Roster alerts stay off until the league is in season.
- **Send or accept a trade.** It suggests offers. You send them.

## When it texts you

| Something happens | Otto texts |
| --- | --- |
| Your starter is ruled out | Yes, with what to do about him and the best legal replacement |
| A starting slot is empty, or its player or defense is on bye | Yes, before that slot locks |
| A bench player projects a point or more better | Yes |
| A team's own injury report says something Sleeper doesn't show yet | Yes |
| A healthy player's role shrinks, in the news or in his snaps | Yes |
| It is the evening before waiver claims are due | Yes, the waiver board |
| Another manager claims a player you are watching | Yes, always |
| Anyone in the league trades | Yes |
| A top-24 running back or receiver, or a top-12 quarterback or tight end, is dropped (cutoffs you can change) | Yes |
| The same problem, an hour later | No, once on detect and once before lock |
| You already tapped Done | No |
| Anything you muted | No |

Every alert has **Done**, **Ignore** and **Mute** buttons.

## Ask it anything

> **you:** who do I start at flex
>
> **otto:** Start Kamara over Pollard. Kamara projects 14.2 to Pollard's 11.8, and Pollard faces the 3rd-toughest run defense this week.

> **you:** best wide receivers on waivers
>
> **otto:** Nacua is the one to chase. Kupp is on IR and Nacua saw 15 targets last week. I'd bid $40-60 of the $112 you have left.

The chat model picks from 28 tools, each one a piece of Java that computes the answer. You can ask about:

- **Your lineup.** Start or sit, the best lineup for this week or a later one, and what a change would do to your projected total.
- **Waivers.** The best free agents at a position, who to drop for one, and what to bid. "Focus on TE this week" steers the next waiver board.
- **Your team.** Where it is strong or thin against every team in the league, with numbers, and up to three moves to fix it. Whether you are contending or rebuilding, and every other team's direction.
- **Trades.** Whether a trade is fair, priced two ways: what it does for each roster this season, and what both sides are worth on the trade market. Named offers to send to named managers. In the deadline week and after it, answers say whether trading is still open.
- **Buy low, sell high.** Players whose usage held while their points slumped, players scoring above what their usage is worth, and whether to trade for an injured player.
- **Players and stats.** News, ceiling and floor, snap share, target share, air yards, Next Gen Stats, where a player ranks, the top 10 at any stat, and a team's usage by position.
- **Matchups.** How a defense ranks against a position, which of its starters are out, and how hard a team's remaining schedule is.
- **The league.** Standings, the playoff race, and another manager's roster.
- **Your habits.** Points you left on your bench, and how following or overriding Otto's advice turned out. Otto only brings this up when you ask.
- **Keeper costs.** What keeping a player costs next season, using the keeper rule you tell it.
- **Settings.** Your watchlist, mutes, alert switches and your largest waiver bid.

Replies run a couple of lines. Reply "why" or "more" for the reasoning behind one.

## Every league you play in

Otto follows every Sleeper league your account is in this season, and picks up a league you join within six hours. Each league keeps its own settings, mutes and alerts. Best-ball leagues are left out, because Sleeper sets those lineups itself.

When Otto follows more than one league, every message starts with a `League: <name>` line. A question goes to the league you name ("in my dynasty league"), or to the league the chat was last about. If neither is clear, Otto asks which one. "In all my leagues" answers once per league.

- `/leagues` lists the leagues Otto follows.
- "Leave out the office league" stops alerts and answers for one league. "Bring back the office league" undoes it.
- `/deleteaccount` deletes your saved advice history, after you confirm.

It handles redraft, keeper and dynasty leagues, Superflex and two-quarterback lineups, TE premium scoring, IR and taxi squads, and FAAB.

## Where the data comes from

Every source is free.

- **Sleeper.** Leagues, rosters, matchups, transactions, projections, stats, player news and trending adds.
- **nflverse.** Play-by-play, snap counts, depth charts, weekly rosters and the schedule, plus the Next Gen Stats, Pro Football Reference and FTN charting files it publishes.
- **FantasyCalc.** Trade-market values from real trades, read once a day, with **DynastyProcess** as the backup.
- **NFL team sites.** Each team's own injury reports and inactives, read from its news feed.

## Set it up

You need a Sleeper account, a Telegram bot from BotFather, and an OpenAI key. Anthropic and Gemini keys are optional fallbacks.

**1.** Fill in the four values in your own env file.

```sh
cp .env.example .env
```

**2.** Tell Otto who you are on Sleeper. Find your user id, then set `otto.user-id` to it in `src/main/resources/application.yml`. Set `otto.league-id` to the league Otto follows first. Otto finds your other leagues from your user id.

```sh
curl -s "https://api.sleeper.app/v1/user/YOUR_SLEEPER_USERNAME" | jq -r '.user_id'
```

**3.** Clear any webhook on the bot. Telegram allows one reader at a time, and locally Otto is it.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook"
```

**4.** Message your bot once, then put the chat id it reports into `.env`.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates" | jq '.result[].message.chat.id'
```

**5.** Run it. You need JDK 25 and Maven.

```sh
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Now text the bot. It answers in the same chat, and the buttons under an alert work from your phone.

## Run it in the cloud

One CDK stack deploys the whole assistant to AWS, so you can rebuild it from this repo. After the first setup, every merge to `main` deploys through GitHub Actions. See [docs/deploy.md](docs/deploy.md).

```sh
mvn package
cd infra && npx aws-cdk@2 deploy OttoStack -c alertEmail=you@example.com
```

## How it's built

Java 25, Spring Boot, Spring AI and Maven, running as AWS Lambda functions. A scheduled function runs the Check once a minute: it reads Sleeper, compares the new snapshot with the last one, finds problems and sends alerts. A second function behind a Lambda function URL answers Telegram. State lives in S3 and DynamoDB, and secrets in Parameter Store. If the Check stops, an alarm emails you.

[docs/architecture.md](docs/architecture.md) explains the design with diagrams, and [docs/adr/](docs/adr/) records each major decision.

```sh
mvn test                    # the app
mvn -f infra/pom.xml test   # the stack, read back off the template it synthesizes
```

No test in the normal build touches the network or spends a token. Stub servers play Sleeper, Telegram, nflverse, the team sites, FantasyCalc and the model.
