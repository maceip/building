package dev.ide.gradle.tooling;

import java.io.FileDescriptor;

/**
 * Entry point of the tooling fat jar.
 *
 * <p>The protocol owns the raw stdout file descriptor. Anything a library prints on {@link System#out}
 * would split a frame, so stdout is redirected to stderr before any other class is initialized.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.setOut(System.err);
        try {
            FrameChannel channel = new FrameChannel(FileDescriptor.in, FileDescriptor.out);
            new ToolingServer().serve(channel);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
