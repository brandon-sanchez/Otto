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

## Run Otto locally

Copy `.env.example` to `.env` and fill it in.

Locally, Otto reads your messages with `getUpdates`, and Telegram refuses that call for a bot that has a webhook registered. So clear the webhook before anything else, or both commands below answer 409:

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook"
```

To read the chat id, message the bot once, then ask Telegram who wrote:

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates" | jq '.result[].message.chat.id'
```

Nothing loads `.env` by itself, so put it into the environment and then start with the `local` profile:

```sh
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

The `local` profile runs one Check every 60 seconds and reads your Telegram messages. In a pre-draft league the cadence gate inside the Check drops this to one full Check per day, and no roster Alerts fire.

## Ask the bot

Text the bot in plain language and the Ask loop answers. A tool-calling agent routes the question to deterministic tools - the roster, the league settings, the optimal lineup, and what-if swaps - and words what they computed; it never computes a number itself. Replies run 2 to 5 lines; reply "why" or "more" for the full reasoning. The loop answers only the configured chat, and it reads the Snapshot the last Check stored.

Deployed, Telegram posts each message to a webhook. Locally there is no address for it to post to, so the `local` profile asks Telegram for its own messages instead - a `getUpdates` long poll that hands each one to the same code the webhook calls. Buttons work the same way, so Done, Ignore, and Mute answer from the phone. Otto registers nothing with Telegram, which is why the webhook is cleared by hand above.
