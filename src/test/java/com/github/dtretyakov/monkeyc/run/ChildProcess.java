package com.github.dtretyakov.monkeyc.run;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.List;

/**
 * A child process the stream tests can drive on any platform.
 *
 * They used to run {@code sh -c}, which Windows has not got, so the four tests guarding
 * {@code MtpTool.run} against a pipe deadlock were skipped there — and the deadlock is a property
 * of the JVM, not of the shell.
 *
 * Java rather than Kotlin on purpose: it needs no stdlib on the child's classpath, so the whole
 * command line is one directory. A Windows command line has 32 KB, which the test classpath of an
 * IntelliJ plugin would exhaust on its own.
 */
public final class ChildProcess {

    /** Far more than any pipe buffer holds, so a reader that waits on stdout first will hang. */
    private static final int FLOOD_LINES = 20_000;

    /**
     * {@code MtpTool.NO_DEVICE}, written out because the child has no {@code MtpTool} on its
     * classpath. The test asserts the two agree.
     */
    private static final int NO_DEVICE = 2;

    private ChildProcess() {
    }

    /** What to run to get this class doing {@code mode}. */
    public static List<String> command(String mode) {
        return List.of(java(), "-cp", ownClasses(), ChildProcess.class.getName(), mode);
    }

    public static void main(String[] arguments) throws InterruptedException {
        String mode = arguments.length > 0 ? arguments[0] : "";
        switch (mode) {
            // Progress on stderr throughout and the result on stdout at the end, which is the
            // shape of a real mtp-rs transfer and the shape that deadlocks a naive reader.
            case "flood" -> {
                for (int i = 0; i < FLOOD_LINES; i++) {
                    System.err.println("progress line for the buffer");
                }
                System.out.println("{\"ok\":true}");
            }
            case "both" -> {
                System.out.println("out");
                System.err.println("err");
            }
            case "fail" -> {
                System.err.println("no MTP device found");
                System.exit(NO_DEVICE);
            }
            // Long enough that a test waiting a second on it is testing the timeout and not a race.
            case "hang" -> Thread.sleep(600_000);
            default -> System.exit(64);
        }
    }

    /**
     * Only the directory this class is in, not the classpath the tests run with.
     *
     * Walked up from this class's own resource rather than read off its protection domain, which
     * is the shorter spelling and has no code source under the platform test framework's loader.
     */
    private static String ownClasses() {
        String name = ChildProcess.class.getName();
        URL url = ChildProcess.class.getResource("/" + name.replace('.', '/') + ".class");
        if (url == null) {
            throw new IllegalStateException("Cannot find " + name + " as a resource");
        }

        Path classFile;
        try {
            classFile = Path.of(url.toURI());
        } catch (URISyntaxException | FileSystemNotFoundException | IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not a file on disk: " + url, e);
        }

        // One step out of the file, then one out of each package directory.
        Path root = classFile;
        for (int i = 0; i < name.split("\\.").length; i++) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalStateException("Unexpected layout for " + url);
            }
        }
        return root.toString();
    }

    private static String java() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
