package otto.advice;

/**
 * The parts every chat answer's instructions share: who Otto is, the
 * rule that every fact comes from the evidence bundle, and the JSON
 * object the model answers with. A form adds its own task between the
 * {@link #preamble} and the {@link #answerShape}.
 */
public final class AdvicePrompt {

    /** Which players an answer may name. */
    public enum Naming {
        /** The answer names no player at all. */
        NO_PLAYER("Name no player."),
        /** The answer names only the bundle's players. */
        LISTED("Name only players listed in \"players\"."),
        /** The answer names only the bundle's players, each by full name first. */
        LISTED_BY_FULL_NAME("Name only players listed in \"players\", and introduce each "
                + "by full name before using his surname alone.");

        private final String rule;

        Naming(String rule) {
            this.rule = rule;
        }
    }

    private AdvicePrompt() {
    }

    /**
     * Returns the opening of an answer's instructions.
     *
     * @param about what the user is being told about, such as
     *        {@code "about his team"}; empty for no topic
     */
    public static String preamble(String about, Naming naming) {
        String topic = about.isEmpty() ? "" : " " + about;
        return FriendVoice.PERSONA + """
                You are writing to the user%s.

                The user message is an evidence bundle as JSON. State facts only from the
                evidence bundle: never from memory, never invented. Every number you write
                must appear in the evidence, written as the evidence writes it. %s
                """.formatted(topic, naming.rule);
    }

    /**
     * Returns the closing of an answer's instructions: the JSON object
     * {@link AdviceCheck} reads. A chat answer recommends no lineup move.
     */
    public static String answerShape(Naming naming) {
        String players = naming == Naming.NO_PLAYER
                ? "[]"
                : "[\"<playerId of every player the message names>\"]";
        return """

                Answer with only this JSON object:
                {
                  "message": "the text the user receives",
                  "move": null,
                  "players": %s,
                  "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}],
                  "comparisons": []
                }
                """.formatted(players);
    }
}
