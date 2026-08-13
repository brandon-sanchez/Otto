package otto.storage;

/**
 * Two runs changed one document, and neither may win. The later run's
 * copy was derived from a version that no longer exists, so storing it
 * would erase what the other run stored.
 *
 * <p>This is a storage-seam concept rather than an S3 one: any backend
 * that lets two entry points write at once can raise it, and callers
 * decide what a lost run costs them. The Check lets it fail, because
 * the next Check is a minute away. The webhook asks Telegram to send
 * the update again, because the tap it carries is the user's and there
 * is no next one.
 */
public class ConcurrentDocumentChange extends IllegalStateException {

    public ConcurrentDocumentChange(String name) {
        super("The other entry point changed " + name + " while this run held it."
                + " This run's copy is dropped rather than written over it.");
    }
}
