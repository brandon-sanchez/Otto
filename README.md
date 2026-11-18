# Otto

[![Build and deploy](https://github.com/brandon-sanchez/Otto/actions/workflows/deploy.yml/badge.svg)](https://github.com/brandon-sanchez/Otto/actions/workflows/deploy.yml)

A fantasy football assistant for every Sleeper league you play in. It watches your teams, texts you on Telegram when something needs doing, and answers questions in plain language.

Sleeper tells you a starter is out when you remember to open the app. Otto tells you before kickoff.

## What it does

- **Texts you first.** A starter ruled out, a starter or defense on bye, an empty slot before lock, or a bench player projecting better than the one you started.
- **Helps with player decisions.** When any player on your roster changes status, Otto picks one move from the ones Sleeper allows in your league: keep him, move him to IR or the taxi squad, drop him, or drop someone else to add a free agent.
- **Plans your waivers.** The evening before claims are due, a board of up to five free agents worth a claim, with the evidence behind each one, what to bid out of the FAAB you have left, and who to drop when your roster is full. When no pickup would help, it tells you to hold and save your budget.
- **Watches the league.** Trades, notable drops, and changes to players on your watchlist: claimed, dropped, trending or projected differently.
- **Answers questions.** Your lineup, where your team is thin, whether a trade is fair, who to buy low, a player's ceiling and stats, and more. See [Ask it anything](#ask-it-anything).

## Code computes the numbers, the model explains them

Java computes the numbers Otto works from: projections, ranks, trade values, FAAB bids and the list of legal lineup moves. The model reads those facts and writes the words.

Advice gets one more step. For alerts, the waiver board, player decisions, and the lineup, team, trade-idea, buy-low, ceiling and stat answers, the facts go to the model as an evidence bundle. A step called the Advice Check then holds the reply against that bundle: every player name, number, rank, dollar amount and recommended move. A reply that fails gets one correction attempt. If that fails too, Otto doesn't send it. Other chat replies, such as comparing two players or pricing a trade, are written from the same computed facts without that check.

When OpenAI is down, Otto asks Anthropic Claude, then Google Gemini. When all three fail, urgent alerts go out as plain facts that code writes, and advice that can't be checked is held back. A bench swap isn't sent, and the waiver board is replaced by a short note saying why.

## What it won't do

- **Change your lineup.** Sleeper's API is read-only, so Otto tells you and you tap. It checks a later snapshot to confirm the problem is gone.
- **Guess.** If a source is down it says so, rather than filling the gap with a zero. If no source gives an injury timeline, Otto says it doesn't know.
- **Nag.** One alert per problem, one last warning 30 minutes before that player's game locks, nothing after. Mute anything you would rather not hear.
- **Talk during your draft.** Roster alerts stay off until the league is in season.
- **Send or accept a trade.** It suggests offers. You send them.

## When it texts you

| Something happens | Otto texts |
| --- | --- |
| A player on your roster changes status | Yes, with the one move it recommends |
| A starting slot is empty, its player is ruled out, or its player or defense is on bye | Yes, before that slot locks |
| A bench player projects at least 1 point more than a starter (you can change the margin) | Yes, when the swap holds up |
| A team's own injury report says something Sleeper doesn't show yet | Yes |
| A healthy player's role shrinks, in the news or in his snaps | Yes |
| It is the evening before waiver claims are due | Yes, the waiver board |
| Another manager claims, or drops, a player on your watchlist | Yes |
| Anyone in the league trades, including commissioner edits | Yes |
| Another manager drops a top-24 running back or receiver, or a top-12 quarterback or tight end, in this week's projections (cutoffs you can change) | Yes |
| The same problem, an hour later | No, once when found and once before lock |
| You tapped **Done** | Otto checks the fix shows up in Sleeper, and still warns you before lock if it doesn't |
| You tapped **Ignore** | No more about it, unless your lineup is still illegal before lock |
| Anything you muted | No |

Every alert has **Done**, **Ignore** and **Mute** buttons. The waiver board adds **View all options**.

## What it looks like

These are real message formats. Messages go out as Telegram HTML, so the bold parts really are bold and nothing shows stray `**` marks. Code writes the fixed parts, such as the headline, the rank lines, the bid line and the lineup, and the model writes the short reason around them. Each colored dot means one thing everywhere: 🟢 good news, 🟡 fair, 🟠 worrying, 🔴 bad news, and health is never green. (Bold is lost inside the code blocks below.)

A player decision when a starter gets hurt:

```text
🔴 Christian McCaffrey is now Out
Move Christian McCaffrey to IR and start Josh Jacobs at RB.
He's expected to miss about four weeks with a hamstring strain, and IR
keeps him without using a roster spot.

[Done] [Ignore] [Mute]
```

One entry on the waiver board, with the reasoning folded into a quote you tap to open:

```text
Your waiver options

A pickup stands out this week, and a receiver is worth a claim behind him.

1. Bucky Irving · RB, TB
💰 Bid $9 (range $7–12)
➖ Drop: Rachaad White
│ Bucky Irving played 71% of offensive snaps in his latest game, and his
│ coach named him the starter. He projects 19.0 points this week.

[Done] [Ignore] [Mute]
[View all options]
```

## Ask it anything

The chat model picks from 28 tools, each one a piece of Java that computes the answer. Answers lead with exactly what you asked.

```text
you:  where does Bucky Irving rank

otto: Bucky Irving's season ranks among RBs (weeks 1–10)
      🟢 Carries per game: 4th of 79 (18.6)
      🟡 Target share: 16th of 60 (12%)
```

```text
you:  what was Isaiah Likely's snap share last week

otto: Isaiah Likely played 88% of snaps in week 10 (57 snaps).
      🟢 Snap share: 13th of 143 (76%)
```

```text
you:  who should I start this week

otto: Here's your week 2 lineup.
      ⬆️ Start Josh Jacobs over James Cook, +3.5 points
      Projected 145.0 → 148.5

      ----- STARTERS -----
      QB: Josh Allen 24.1
      RB: Josh Jacobs 16.5 ⬆️
      WR: Justin Jefferson 19.0 🟡 Q
      ...
      ----- BENCH -----
      RB: James Cook 13.0 ⬇️
      QB: Patrick Mahomes 22.0 💤 BYE
      ...
```

```text
you:  is Jefferson for Bijan a good trade

otto: ⚖️ Slight edge for you
      You give: Bijan Robinson
      You get: Justin Jefferson
      🟢 Your lineup: +2.1 points a week
      ...
```

```text
you:  what are my team's weaknesses

otto: Strong at QB and TE, thin at running back, with more receivers than
      you can start.

      🟢 QB: 1st of 4 (strong)
      🔴 RB: 4th of 4 (thin)
      🟡 WR: 6 for 4 spots (surplus)
      🟢 TE: 1st of 4 (strong)

      • Claim a running back off waivers: the best one averages 13.0 points
        a game and would be 2nd among your RBs.
```

```text
you:  who should I buy low

otto: 📉 Buy low: George Pickens, 4.6 points a game below his usage
      His targets held while his value fell 18% this month.
      📈 Sell high: Jaxon Smith-Njigba, 12.5 points a game above his usage
      Expect about 22.2 a game from here.
```

You can ask about:

- **Your lineup.** The best lineup for this week or a later one, one player or another at flex, and what a change would do to your projected total.
- **Waivers.** The best free agents at a position, who to drop for one, and what to bid. "Focus on TE this week" steers the next waiver board.
- **Your team.** Where it is strong or thin against every team in the league, with numbers, and up to three moves to fix it. Whether you are contending or rebuilding, and every other team's direction.
- **Trades.** Whether a trade is fair, priced two ways: what it does for each roster this season, and what both sides are worth on the trade market. Named offers to send to named managers, with why each one would say yes. Near and after the deadline, answers say whether trading is still open.
- **Buy low, sell high.** Players whose usage held while their points slumped, players scoring above what their usage is worth, and whether to trade for an injured player.
- **Players and stats.** News, ceiling and floor, snap share, target share, air yards, Next Gen Stats, where a player ranks, the top 10 (or up to 50) at any stat, and a team's usage by position.
- **Matchups.** How a defense ranks against a position, which of its starters are out, and how hard a team's remaining schedule is.
- **The league.** Standings, the playoff race, and another manager's roster.
- **Your habits.** Points you left on your bench, and how following or overriding Otto's advice turned out. Otto only brings this up when you ask.
- **Keeper costs.** What keeping a player costs next season, using the keeper rule you tell it.
- **Settings.** Your watchlist, mutes, alert switches, the bench-swap margin and your largest waiver bid.

Simple questions get a line or two, one fact to a line, and answers like a lineup or a team breakdown take the space they need. Reply "why" or "more" for the full reasoning behind one.

## Every league you play in

Otto follows every Sleeper league your account is in this season, and picks up a league you join within six hours. Each league keeps its own settings, mutes and alerts. Best-ball leagues are left out, because Sleeper sets those lineups itself.

When Otto follows more than one league, every message about one league starts with a "🏈 **league name**" line. A question goes to the league you name ("in my dynasty league"), or to the league the chat was last about. If neither is clear, Otto asks which one. "In all my leagues" answers once per league.

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
