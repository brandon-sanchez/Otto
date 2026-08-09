# Otto

A personal fantasy football assistant for one Sleeper league. Deterministic Java computes the facts; an LLM turns them into short Telegram messages.

## Stack

- Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, Maven.
- No database yet: local JSON documents under `./data` stand in for the object and key-value stores that AWS supplies later.

## Run the tests

```sh
mvn test
```

The tests boot the real Spring context and invoke the two entry points - the Check and the Telegram webhook - directly. Stub HTTP servers play Sleeper, Telegram, and the LLM with recorded fixtures, including canned tool-call sequences for the Ask loop. No test touches the network or spends tokens.

## Run a Check loop locally

Copy `.env.example` to `.env` and fill it in. Nothing loads that file by itself, so put it into the environment and then start with the `local` profile:

```sh
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

To read the chat id, message the bot once, then ask Telegram who wrote:

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates" | jq '.result[].message.chat.id'
```

The loop runs one Check every 60 seconds. In a pre-draft league the cadence gate inside the Check drops this to one full Check per day, and no roster Alerts fire.

## Ask the bot

Text the bot in plain language and the Ask loop answers. A tool-calling agent routes the question to deterministic tools - the roster, the league settings, the optimal lineup, and what-if swaps - and words what they computed; it never computes a number itself. Replies run 2 to 5 lines; reply "why" or "more" for the full reasoning. The loop answers only the configured chat, and it reads the Snapshot the last Check stored.
