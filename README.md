# Otto

A personal fantasy football assistant for one Sleeper league. Deterministic Java computes the facts; an LLM turns them into short Telegram messages. The domain language is in `CONTEXT.md`.

## Stack

- Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, Maven.
- No database yet: local JSON documents under `./data` stand in for the object and key-value stores that AWS supplies later.

## Run the tests

```sh
mvn test
```

The tests boot the real Spring context and invoke the Check entry point directly. Stub HTTP servers play Sleeper, Telegram, and the LLM with recorded fixtures. No test touches the network or spends tokens.

## Run a Check loop locally

Set the secrets, then start with the `local` profile:

```sh
export TELEGRAM_BOT_TOKEN=...   # from BotFather
export TELEGRAM_CHAT_ID=...     # your chat with the bot
export OPENAI_API_KEY=...
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

The loop runs one Check every 60 seconds. In a pre-draft league the cadence gate inside the Check drops this to one full Check per day, and no roster Alerts fire.
