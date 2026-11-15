package otto.research;

/**
 * A team-site feed or page no longer has the shape Otto reads. The
 * resource is skipped and reported; it never stands for a report with
 * no injuries in it.
 */
final class LayoutChanged extends RuntimeException {

    LayoutChanged(String what) {
        super("layout changed: " + what);
    }
}
