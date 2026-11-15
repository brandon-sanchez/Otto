package otto.records;

import java.util.Objects;

import otto.OttoApplication;

/** The versions of everything that could have changed the answer. */
public record Versions(String data, String code, String prompt, String model) {

    /** The code version of a build run from source, which has no packaged version. */
    static final String UNPACKAGED = "dev";

    /** Returns the versions of a run made by this build of the code. */
    public static Versions ofThisBuild(String data, String prompt, String model) {
        String code = Objects.requireNonNullElse(
                OttoApplication.class.getPackage().getImplementationVersion(), UNPACKAGED);
        return new Versions(data, code, prompt, model);
    }
}
