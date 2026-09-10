package com.github.dtretyakov.monkeyc.run.session;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived connection to the Connect IQ simulator, and everything it has to say.
 *
 * This is what `monkeydo` does, made into something the plugin can talk to. `monkeydo` is a black
 * box: it pushes an app, relays its output and exits, and everything the simulator says about the
 * app on the way — launching, started, crashed, terminated, signature rejected — it keeps to
 * itself. So Stop could only kill `monkeydo`, and the app went on running in the simulator with
 * nothing attached; the next debug session then could not get past it. The simulator's channel
 * takes one client at a time, so the answer cannot be a second connection alongside `monkeydo` —
 * it has to be one connection that stays.
 *
 * The connection is built out of Garmin's own client classes from `monkeybrains.jar`, which is
 * what `monkeydo` is built out of too. Reached by reflection because that jar belongs to the SDK
 * the user installed, not to this plugin's build: this class is compiled against nothing and told
 * where the jar is at run time.
 *
 * In Java, and in a JVM of its own, deliberately. Of its own because these are internal SDK
 * classes with no compatibility promise, and a crash in them must not reach the IDE — and because
 * the jar changes with every SDK, which is a classloader problem worth not having. In Java because
 * the plugin does not ship the Kotlin runtime, and this process starts with nothing on its
 * classpath but that one jar.
 *
 * The wiring order was read out of `MonkeyDoDeux`, and three parts of it are not guessable: the
 * channels have to be tuned before the process starts, the word `ciq` has to be sent down the
 * shell afterwards — without it the connection is open and permanently silent — and the manager
 * must not be subscribed to the process by hand, because handing it the process already did that
 * and doing it twice makes every event and every printed line arrive in duplicate.
 */
public final class SessionHelper {

    private static final String GARMIN = "com.garmin.connectiq.common.communication.";

    /** How often to look at whether the process that started this one is still there. */
    private static final long WATCH_INTERVAL_MS = 2_000;

    private final File shell;
    /** The shell reports its exit once per thread that noticed; this says it once. */
    private final AtomicBoolean closed = new AtomicBoolean();
    private Object process;
    private Object platform;
    private Object device;

    public static void main(String[] args) {
        if (args.length < 2) {
            say("error the helper needs the path to the SDK's shell and the pid to outlive");
            System.exit(2);
        }
        outlive(Long.parseLong(args[1]));
        try {
            new SessionHelper(new File(args[0])).run();
        } catch (Throwable failure) {
            say("error " + describe(failure));
            System.exit(1);
        }
    }

    /**
     * Ends this process when the one that started it is gone.
     *
     * Closing the pipe is supposed to be enough — the read loop ends at end of input — and in
     * practice it is not always: a parent that is killed rather than closed can leave this process
     * reading a pipe that never reports the end. What it leaves behind is not a stray JVM but a
     * held connection, and the simulator's channel takes one client, so every later run and every
     * debug session fails until someone finds it in a process list. Watching the parent directly
     * costs one sleeping thread and does not depend on how the parent died.
     */
    private static void outlive(long parent) {
        Thread watch = new Thread(() -> {
            while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(WATCH_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.exit(0);
        }, "parent watch");
        watch.setDaemon(true);
        watch.start();
    }

    private SessionHelper(File shell) {
        this.shell = shell;
    }

    /**
     * Connects, then does as it is told until told to stop.
     *
     * One command per line in, one message per line out, because the other end is an IDE process
     * reading a pipe and anything richer would be a protocol to get wrong on both sides.
     */
    private void run() throws Exception {
        connect();
        say("ready");

        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            List<String> words = words(line);
            if (words.isEmpty()) continue;
            try {
                if (!obey(words, line)) break;
            } catch (Throwable failure) {
                // Reported rather than fatal: one command the SDK would not take is no reason to
                // drop a connection the next run needs.
                say("error " + describe(failure));
            }
        }
        disconnect();
    }

    private boolean obey(List<String> words, String line) throws Exception {
        String command = words.get(0).toLowerCase(Locale.ROOT);
        switch (command) {
            case "push":
                // The `.prg` goes over the same transport, and has to be there before the app can
                // be opened.
                push(new File(rest(line, words.get(0))));
                say("pushed");
                return true;

            case "device":
                call(platform, "openDevice", new Class<?>[] { String.class }, words.get(1));
                return true;

            case "open":
                // `native` puts the app in native pairing mode, which is a different message on
                // the wire rather than a flag on the same one.
                boolean pairing = words.contains("native");
                call(device, "openApp", new Class<?>[] { UUID.class, boolean.class },
                    uuid(words.get(1)), pairing);
                return true;

            case "debug":
                call(device, "debugApp", new Class<?>[] { UUID.class }, uuid(words.get(1)));
                return true;

            case "tests":
                // No names means every test in the app, which is what an empty list means here too.
                List<String> names = new ArrayList<>(words.subList(Math.min(2, words.size()), words.size()));
                call(device, "runTests", new Class<?>[] { UUID.class, List.class }, uuid(words.get(1)), names);
                return true;

            case "close":
                call(device, "closeApp", new Class<?>[] { UUID.class }, uuid(words.get(1)));
                return true;

            case "quit":
                return false;

            default:
                say("error unknown command " + command);
                return true;
        }
    }

    /** Builds the connection, in the one order that works. */
    private void connect() throws Exception {
        int port = port();
        say("port " + port);

        process = make("shell.ShellProcess");
        Object shellManager = make("channels.shell.ShellChannelManager");

        // This is also what subscribes the manager to the process; it does not look like it, and
        // subscribing it again by hand is not harmless — the process keeps its listeners in a
        // plain list, so a manager registered twice dispatches every message twice, and every
        // event and every line the app prints arrives in duplicate.
        call(shellManager, "setShellProcess", new Class<?>[] { clazz("shell.IShellProcess") }, process);
        addListener(process, "shell.IShellProcessListener", "addShellProcessListener", watchTheProcess());

        platform = make("channels.platform.PlatformChannel");
        device = make("channels.device.DeviceChannel");
        addListener(platform, "channels.platform.IPlatformChannelListener",
            "addPlatformChannelListener", report("channels.platform.IPlatformChannelListener"));
        addListener(device, "channels.device.IDeviceChannelListener",
            "addDeviceChannelListener", report("channels.device.IDeviceChannelListener"));

        // An app and its tests print down different channels, and a client that tunes only the
        // first sees a test run produce whatever the tests logged and not one word from the test
        // runner itself — no test names, no results.
        Object appManager = relay("channels.app", "App");
        Object testManager = relay("channels.runnoevil", "RunNoEvil");

        // Tuned before the process starts, or the first messages arrive with nowhere to go.
        for (Object channel : new Object[] { platform, device, appManager, testManager }) {
            call(shellManager, "tune", new Class<?>[] { clazz("channels.shell.IShellSubChannel") }, channel);
        }

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) staticCall(clazz("shell.ShellUtils"), "getShellCommand",
            new Class<?>[] { File.class, int.class }, shell, port);
        call(process, "start", new Class<?>[] { String.class, List.class },
            command.get(0), command.subList(1, command.size()));

        // The shell starts as a general-purpose console. This is the command that opens the Connect
        // IQ sub-channel on it, and until it is sent the connection answers nothing at all.
        call(process, "sendMessage", new Class<?>[] { String.class }, "ciq");
    }

    private void disconnect() {
        if (process == null) return;
        try {
            call(process, "stop", new Class<?>[0]);
        } catch (Throwable ignored) {
            // Going away regardless: a shell that will not close is the exiting JVM's problem.
        }
    }

    /** The port the simulator is listening on, or a failure that names the reason. */
    private int port() throws Exception {
        Class<?> utils = clazz("shell.ShellUtils");
        int port = (int) staticCall(utils, "findSimulatorPort", new Class<?>[] { File.class }, shell);
        if (port == (int) utils.getField("INVALID_PORT").get(null)) {
            throw new IllegalStateException("no simulator is listening");
        }
        return port;
    }

    private void push(File prg) throws Exception {
        staticCall(clazz("shell.ShellUtils"), "pushPrg",
            new Class<?>[] { File.class, File.class, int.class }, prg, shell, port());
    }

    /**
     * Builds the manager for one kind of printed output, with a listener already on it.
     *
     * The app channel and the test runner's channel are the same shape twice: a manager that is
     * itself a shell sub-channel, and which hands messages only to something that is both the
     * thing being tuned and the thing listening — so each listener is one object wearing both
     * interfaces. `DEFAULT` is the channel that carries what gets printed; the app manager's other
     * one, `DEBUGGING`, carries the debug protocol, which the debug adapter reads over a
     * connection of its own.
     *
     * The text is passed on exactly as the wire has it, newlines and all still written `\n` —
     * which is what keeps one message to one line, and is the only reason this protocol can be
     * read a line at a time. The reader turns them back into newlines on the way to a console.
     *
     * @return the manager, for the caller to tune into the shell
     */
    private Object relay(String pkg, String kind) throws Exception {
        Object manager = make(pkg + "." + kind + "ChannelManager");
        Class<?> subChannel = clazz(pkg + ".I" + kind + "SubChannel");
        Class<?> listener = clazz(pkg + ".I" + kind + "ChannelListener");
        Object channel = clazz(pkg + "." + kind + "ChannelManager$" + kind + "Channel")
            .getField("DEFAULT").get(null);
        String getter = "get" + kind + "Channel";

        Object proxy = proxy(new Class<?>[] { subChannel, listener }, (method, arguments) -> {
            if (method.getName().equals(getter)) return channel;
            if (method.getName().equals("messageReceived")) {
                // Both overloads land here; the text is the last argument either way.
                Object text = arguments[arguments.length - 1];
                // Escaped again rather than trusted: a real newline getting through would be read
                // at the other end as the start of a message that never came.
                if (text != null) say("app " + text.toString().replaceAll("\\R", "\\\\n"));
            }
            return null;
        });
        call(manager, "tune", new Class<?>[] { subChannel }, proxy);
        return manager;
    }

    /**
     * Reports the end of the connection, once, as the last thing this process says.
     *
     * The helper is one connection and nothing else, so a dead shell is the end of it rather than
     * something to recover from: the plugin sees the line, tears down whatever was running on it,
     * and starts a new helper for the next run.
     */
    private Object watchTheProcess() throws Exception {
        return proxy(new Class<?>[] { clazz("shell.IShellProcessListener") }, (method, arguments) -> {
            switch (method.getName()) {
                case "processExited":
                    if (closed.compareAndSet(false, true)) {
                        say("closed " + arguments[0]);
                        System.exit(0);
                    }
                    break;
                case "exceptionOccurred":
                    say("error " + describe((Throwable) arguments[0]));
                    break;
                default:
                    break;
            }
            return null;
        });
    }

    /** Reports every event a channel raises, by name. */
    private Object report(String listenerType) throws Exception {
        return proxy(new Class<?>[] { clazz(listenerType) }, (method, arguments) -> {
            say("event " + method.getName() + argument(arguments));
            return null;
        });
    }

    /** The first argument, which for every event here is the app or the device it concerns. */
    private static String argument(Object[] arguments) {
        if (arguments == null || arguments.length == 0 || arguments[0] == null) return "";
        return " " + arguments[0];
    }

    private static Object uuid(String text) throws Exception {
        // Garmin's own parser: the id in a manifest has no hyphens, and `UUID.fromString` insists.
        return staticCall(clazz("channels.utils.MessageUtilities"),
            "createUuidFromString", new Class<?>[] { String.class }, text);
    }

    // --- reflection, kept in one place ------------------------------------------------------

    /** What a proxy actually has to answer, minus the three questions every object is asked. */
    private interface Answer {
        Object to(Method method, Object[] arguments) throws Exception;
    }

    private static Object proxy(Class<?>[] interfaces, Answer answer) {
        InvocationHandler handler = (self, method, arguments) -> {
            Object standard = standardAnswer(self, method, arguments);
            if (standard != null) return standard;
            try {
                return answer.to(method, arguments);
            } catch (Throwable failure) {
                // A listener that throws would take the channel's dispatch down with it, and with
                // it every other listener on the same event.
                say("error " + describe(failure));
                return null;
            }
        };
        return Proxy.newProxyInstance(SessionHelper.class.getClassLoader(), interfaces, handler);
    }

    /**
     * The three `Object` methods a proxy is asked, answered before anything else sees them.
     *
     * The channels keep their listeners in lists, so `equals` and `hashCode` decide whether a
     * listener is registered once or many times; answering them by identity is what makes a proxy
     * behave like an ordinary listener.
     */
    private static Object standardAnswer(Object self, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "hashCode":
                return System.identityHashCode(self);
            case "equals":
                return self == (arguments == null ? null : arguments[0]);
            case "toString":
                return "monkeyc session listener";
            default:
                return null;
        }
    }

    private void addListener(Object target, String listenerType, String addMethod, Object listener)
        throws Exception {
        call(target, addMethod, new Class<?>[] { clazz(listenerType) }, listener);
    }

    private static Object make(String name) throws Exception {
        return clazz(name).getDeclaredConstructor().newInstance();
    }

    private static Class<?> clazz(String name) throws ClassNotFoundException {
        return Class.forName(GARMIN + name);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... arguments)
        throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, arguments);
    }

    private static Object staticCall(Class<?> owner, String name, Class<?>[] types, Object... arguments)
        throws Exception {
        return owner.getMethod(name, types).invoke(null, arguments);
    }

    // --- text ------------------------------------------------------------------------------

    private static List<String> words(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    /** Everything on the line after the command word, spaces and all. */
    private static String rest(String line, String command) {
        return line.trim().substring(command.length()).trim();
    }

    /** The cause rather than the wrapper: a reflective failure says nothing about itself. */
    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static synchronized void say(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
