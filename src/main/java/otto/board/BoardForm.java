package otto.board;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.EvidenceBundle;
import otto.directory.DirectoryPlayer;
import otto.storage.OttoJson;

/**
 * The waiver board as a form of advice: the instructions the model
 * ranks the candidates under, and the checks its board must pass. The
 * board's text goes through every {@link AdviceCheck} rule, and the
 * board itself must recommend only screened free agents, each once,
 * with no score, no "Cons:" list and nothing longer than an entry may
 * be.
 */
final class BoardForm implements AdviceForm<BoardAnswer> {

    static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about this
            week's waiver claims, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence. Name only players listed in "players". Recommend
            only the free agents that have a "candidate-<playerId>" evidence item.

            Weigh each candidate's role and opportunity first: snaps, targets, carries
            and their shares, air yards, red-zone, end-zone and first-read work, and
            expected points. Read a quiet box score against how much he was used.
            Treat falling usage as a warning even after good production. Let a verified
            role change outweigh a lagging projection. One strong game can justify a
            pickup, but never call it a breakout. A value written as unknown is not
            zero. Last season's numbers are early-season context only. Trending adds
            are attention, never a reason on their own. Weigh every candidate against
            the roster needs and the user's roster: an empty slot first, then weak
            slots, and a large upgrade anywhere can beat a small patch.

            Rank only players worth a claim, best first, and never pad the list. When
            no pickup improves the roster, set "hold" to true, open by telling the user
            to hold and save FAAB, and list any reasonable speculative moves under
            "optional". Otherwise "optional" is empty.

            Write the opening and each reasoning in two or three short sentences of
            plain text, at most 700 characters each, with recent usage, role, news and
            roster fit. Introduce every player by full name before using a surname
            alone. Never write a score, a confidence label such as High or Medium, or a
            "Cons:" list; when something is uncertain, say exactly what is uncertain.
            Never say a player "locks". Never state a dollar amount.

            Answer with only this JSON object:
            {
              "hold": false,
              "opening": "the board's first paragraph",
              "picks": [{"playerId": "<candidate playerId>", "reasoning": "<why>"}],
              "optional": [{"playerId": "<candidate playerId>", "reasoning": "<why>"}],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}]
            }
            """;

    private static final Pattern CODE_FENCE =
            Pattern.compile("(?s)^```(?:json)?\\s*(.*?)\\s*```$");
    private static final Pattern SCORE =
            Pattern.compile("(?i)\\bscore[sd]?\\s+(?:of\\s+)?\\d|\\b\\d+\\s*(?:/|out of)\\s*100\\b");
    private static final Pattern CONS = Pattern.compile("(?i)\\bcons\\s*:");

    private final AdviceCheck check;
    private final Map<String, DirectoryPlayer> candidates;

    /** @param candidates the screened pool, the only players the board may recommend */
    BoardForm(AdviceCheck check, Map<String, DirectoryPlayer> candidates) {
        this.check = check;
        this.candidates = Map.copyOf(candidates);
    }

    @Override
    public String instructions() {
        return INSTRUCTIONS;
    }

    @Override
    public AdviceCheck.Verdict<BoardAnswer> check(EvidenceBundle bundle, String reply) {
        Optional<BoardAnswer> parsed = parse(reply);
        if (parsed.isEmpty()) {
            return new AdviceCheck.Verdict<>(Optional.empty(), List.of(
                    "The answer is not the JSON object the instructions ask for, with a "
                            + "non-empty \"opening\"."));
        }
        BoardAnswer answer = parsed.get();
        List<String> failures = new ArrayList<>(checkShape(answer));
        if (failures.isEmpty()) {
            String text = BoardMessage.checkedText(answer, candidates);
            List<String> named = answer.entries().stream().map(BoardAnswer.Entry::playerId)
                    .toList();
            failures.addAll(check.checkAnswer(bundle, new AdviceAnswer(text, Optional.empty(),
                    named, answer.claims(), List.of(), Optional.empty(), List.of())));
            if (SCORE.matcher(text).find()) {
                failures.add("Never write a score; give the reasons instead.");
            }
            if (CONS.matcher(text).find()) {
                failures.add("Never write a \"Cons:\" list; say what could change the call.");
            }
        }
        return new AdviceCheck.Verdict<>(parsed, failures);
    }

    private List<String> checkShape(BoardAnswer answer) {
        List<String> failures = new ArrayList<>();
        if (answer.hold() && !answer.picks().isEmpty()) {
            failures.add("A holding board recommends no claim: move the picks to \"optional\" "
                    + "or set \"hold\" to false.");
        }
        if (!answer.hold() && answer.picks().isEmpty()) {
            failures.add("A board that does not hold must recommend at least one pick.");
        }
        if (!answer.hold() && !answer.optional().isEmpty()) {
            failures.add("Only a holding board lists optional moves.");
        }
        if (answer.opening().length() > WaiverEvidence.PART_MAX_LENGTH) {
            failures.add("The opening is %d characters; the limit is %d."
                    .formatted(answer.opening().length(), WaiverEvidence.PART_MAX_LENGTH));
        }
        Set<String> seen = new HashSet<>();
        for (BoardAnswer.Entry entry : concat(answer.picks(), answer.optional())) {
            if (!candidates.containsKey(entry.playerId())) {
                failures.add("Player id %s is not a free agent on this board; recommend only "
                        .formatted(entry.playerId()) + "the candidates.");
            } else if (!seen.add(entry.playerId())) {
                failures.add("%s appears on the board more than once."
                        .formatted(candidates.get(entry.playerId()).fullName()));
            }
            if (entry.reasoning().isBlank()) {
                failures.add("Every entry needs its reasoning.");
            } else if (entry.reasoning().length() > WaiverEvidence.PART_MAX_LENGTH) {
                failures.add("The reasoning for player id %s is %d characters; the limit is %d."
                        .formatted(entry.playerId(), entry.reasoning().length(),
                                WaiverEvidence.PART_MAX_LENGTH));
            }
        }
        return failures;
    }

    private static List<BoardAnswer.Entry> concat(List<BoardAnswer.Entry> first,
            List<BoardAnswer.Entry> second) {
        List<BoardAnswer.Entry> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    static Optional<BoardAnswer> parse(String reply) {
        String text = reply.strip();
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.matches()) {
            text = fence.group(1);
        }
        JsonNode root;
        try {
            root = OttoJson.MAPPER.readTree(text);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!root.isObject() || !root.path("opening").isTextual()
                || root.path("opening").asText().isBlank()) {
            return Optional.empty();
        }
        List<AdviceAnswer.Claim> claims = new ArrayList<>();
        root.path("claims").forEach(claim -> {
            List<String> evidence = new ArrayList<>();
            claim.path("evidence").forEach(id -> evidence.add(id.asText()));
            claims.add(new AdviceAnswer.Claim(claim.path("text").asText(""), evidence));
        });
        return Optional.of(new BoardAnswer(root.path("hold").asBoolean(false),
                root.path("opening").asText(), entries(root.path("picks")),
                entries(root.path("optional")), claims));
    }

    private static List<BoardAnswer.Entry> entries(JsonNode array) {
        List<BoardAnswer.Entry> entries = new ArrayList<>();
        array.forEach(entry -> entries.add(new BoardAnswer.Entry(
                entry.path("playerId").asText(""), entry.path("reasoning").asText(""))));
        return entries;
    }
}
