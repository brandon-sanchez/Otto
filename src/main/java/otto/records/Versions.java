package otto.records;

import java.util.Objects;

import otto.OttoApplication;

/**
 * The versions of everything that could have changed the answer.
 *
 * @param prompt the prompt's version, or {@link #NONE} for a run that
 *        asked no model
 * @param model the id of the model that wrote the answer, or
 *        {@link #NONE} when no model answered
 */
public record Versions(String data, String code, String prompt, String model) {

    /** Stands for a version a run has none of: no data, no prompt or no model. */
    public static final String NONE = "none";

    /** The code version of a build run from source, which has no packaged version. */
    static final String UNPACKAGED = "dev";

    /** Returns the versions of a run made by this build of the code. */
    public static Versions ofThisBuild(String data, String prompt, String model) {
        String code = Objects.requireNonNullElse(
                OttoApplication.class.getPackage().getImplementationVersion(), UNPACKAGED);
        return new Versions(data, code, prompt, model);
    }

    /** Returns the versions of a run by this build that asked no model. */
    public static Versions withoutModel(String data) {
        return ofThisBuild(data, NONE, NONE);
    }
}
