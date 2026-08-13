# Otto

A fantasy football assistant for one Sleeper league. It watches your team, texts you when something needs doing, and answers questions in plain language.

Sleeper tells you a starter is out when you remember to open the app. Otto tells you before kickoff.

## What it does

- **Texts you first.** A starter ruled out, an illegal lineup before lock, a bench player projecting better than the one you started.
- **Plans your waivers.** Every Tuesday evening: the top five free agents, what kind of pickup each one is, and what to bid out of the FAAB you have left.
- **Watches the league.** Trades, notable drops, and the moment another manager claims a player off your watchlist.
- **Answers questions.** "Who do I start at flex?" "Is Jacobs worth $30?" "What's my playoff seed?"

Deterministic Java computes every number. The model only writes the sentence.

## What it won't do

- **Change your lineup.** Sleeper's API is read-only, so Otto tells you and you tap. It checks a later snapshot to confirm the problem is gone.
- **Guess.** If a source is down it says so, rather than filling the gap with a zero.
- **Nag.** One alert per problem, one repeat 30 minutes before that player's game locks, nothing after. Mute anything you would rather not hear.
- **Talk during your draft.** Roster alerts stay off until the league is in season.

## Set it up

You need a Sleeper league, a Telegram bot from BotFather, and an OpenAI key.

**1.** Fill in the four values in your own env file.

```sh
cp .env.example .env
```

**2.** Clear any webhook on the bot. Telegram allows one reader at a time, and locally Otto is it.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook"
```

**3.** Message your bot once, then put the chat id it reports into `.env`.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates" | jq '.result[].message.chat.id'
```

**4.** Run it.

```sh
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Now text the bot. It answers in the same chat, and the buttons under an alert work from your phone.

## When it texts you

| Something happens | Otto texts |
| --- | --- |
| Your starter is ruled out | Yes, with the best legal replacement |
| A starting slot is empty, or on a bye | Yes, before that slot locks |
| A bench player projects a point or more better | Yes |
| It is Tuesday evening | Yes, the waiver board |
| Another manager claims a player you are watching | Yes, always |
| Anyone in the league trades | Yes |
| A top-24 running back is dropped | Yes |
| The same problem, an hour later | No, once on detect and once before lock |
| You already tapped Done | No |
| Anything you muted | No |

## Ask it anything

> **you:** who do I start at flex
>
> **otto:** Start Kamara over Pollard, +2.4 projected. Pollard draws the toughest run defense left on your bench.

> **you:** best wide receivers on waivers
>
> **otto:** Nacua, 100. Breakout, bid $40-60. Kupp is on IR and Nacua saw 15 targets.

It also answers: your roster and how each player is doing, two players compared, a hypothetical lineup change, standings and your playoff seed, another manager's roster and where it is thin, the latest news on anyone, and your watchlist, settings and mutes.

Replies run a couple of lines. Reply "why" or "more" for the reasoning behind one.

## Run it in the cloud

One CDK stack deploys the whole assistant, so you can rebuild it from this repo. See [docs/deploy.md](docs/deploy.md).

```sh
mvn package
cd infra && npx aws-cdk@2 deploy -c alertEmail=you@example.com
```

The same app, invoked two ways: a scheduled Lambda runs the Check every minute, and a Lambda function URL takes Telegram's updates. Both use SnapStart, so a restore answers without waiting for Spring to start. Documents go to one S3 object and one DynamoDB table, secrets to Parameter Store. If the Check loop stops, an alarm texts you.

## Under the hood

Java 25, Spring Boot, Spring AI, Maven. Storage is one seam with two backends: JSON files under `./data` on your machine, S3 plus DynamoDB when deployed.

Otto runs one Check a minute. It polls Sleeper, builds a snapshot, diffs it against the last one, finds the problems, and works out what to do about them. Only the result reaches the model, and only to be turned into English.

```sh
mvn test              # the app
mvn -f infra/pom.xml test   # the stack, read back off the template it synthesizes
```

No test touches the network or spends a token. Stub servers play Sleeper, Telegram and the model from recorded fixtures.

## Not built yet

- Trade evaluation ([#18](https://github.com/brandon-sanchez/Otto/issues/18))
- A live season ([#20](https://github.com/brandon-sanchez/Otto/issues/20))
