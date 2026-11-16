package otto.research;

import java.time.Instant;
import java.util.Set;

/**
 * One news story about one player, as the research cache keeps it.
 *
 * @param storyKey what identifies the story across reads: the credited
 *        outlet and its own story id ({@code rotowire:801}), else
 *        {@code url:} and the canonical address, else {@code hash:} and
 *        the content hash
 * @param creditedSource the outlet the story is credited to; two items
 *        with the same credited source are one source however many
 *        feeds carried them
 * @param canonicalUrl the article address in {@link CanonicalUrl} form;
 *        null when the story links nowhere
 * @param published when the outlet published this version; null when
 *        the feed does not say
 * @param retrieved when Otto first read this version of the story. A
 *        later read of the same words leaves it alone; a read of changed
 *        words moves it.
 * @param contentHash SHA-256 of the story's normalized words
 * @param copyOf the story key of the earlier item this one repeats
 *        nearly word for word, from the same outlet or another; null for
 *        an original report. A copy is kept as evidence of how far the
 *        story spread, and never counts as an independent source.
 * @param topics the decision-changing facts the story reports, empty
 *        when it reports none
 */
public record ResearchItem(
        String storyKey,
        String creditedSource,
        String canonicalUrl,
        String headline,
        String detail,
        String analysis,
        Instant published,
        Instant retrieved,
        String contentHash,
        String copyOf,
        Set<ResearchTopic> topics) {

    public ResearchItem {
        topics = Set.copyOf(topics);
    }

    /** True for a report of its own rather than a copy of an earlier one. */
    public boolean original() {
        return copyOf == null;
    }

    /** True when the story reports an injury, availability or roster fact. */
    public boolean decisionChanging() {
        return !topics.isEmpty();
    }
}
