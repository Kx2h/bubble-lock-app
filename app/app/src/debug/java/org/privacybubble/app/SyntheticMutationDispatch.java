package org.privacybubble.app;

/** Test-only guard between an instance preflight and a synthetic mutation. */
final class SyntheticMutationDispatch {
    interface Preflight { void run() throws Exception; }
    interface Mutation<T> { T run() throws Exception; }
    interface Closed { boolean get(); }

    static <T> T run(Preflight preflight, Mutation<T> mutation, Closed closed) throws Exception {
        preflight.run();
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Synthetic request stopped");
        return mutation.run();
    }
    private SyntheticMutationDispatch() { }
}
