package dev.ide.gradle.tooling;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.LongRunningOperation;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.ProgressListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskSkippedResult;
import org.gradle.tooling.events.task.TaskStartEvent;
import org.gradle.tooling.events.task.TaskSuccessResult;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.build.BuildEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Gradle Tooling API client. One connection per {@code initialize}. A reader thread accepts
 * {@code cancel} while {@code execute} is blocked inside the tooling connection.
 */
public final class ToolingServer {
    private final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private volatile CancellationTokenSource current;
    private ProjectConnection connection;
    private File projectDir;
    private File javaHome;
    private File gradleUserHome;
    private List<String> args = List.of();
    private List<String> jvmArgs = List.of();
    private Map<String, String> env = Map.of();
    private File modelOut;

    public void serve(FrameChannel channel) throws Exception {
        Thread reader = new Thread(() -> readLoop(channel), "tooling-reader");
        reader.setDaemon(true);
        reader.start();
        while (running) {
            Object msg = inbox.take();
            if (msg == POISON) break;
            try {
                dispatch(channel, msg);
            } catch (Throwable t) {
                long id = Json.id(msg);
                String message = t.getMessage() == null ? t.toString() : t.getMessage();
                channel.fail(id, message);
            }
        }
        closeQuietly();
    }

    private void readLoop(FrameChannel channel) {
        try {
            while (running) {
                Object msg = channel.read();
                String method = Json.str(msg, "method");
                if ("cancel".equals(method)) {
                    CancellationTokenSource token = current;
                    if (token != null) token.cancel();
                    continue;
                }
                inbox.put(msg);
            }
        } catch (Exception closed) {
            running = false;
            inbox.offer(POISON);
        }
    }

    private void dispatch(FrameChannel channel, Object msg) throws Exception {
        String method = Json.str(msg, "method");
        long id = Json.id(msg);
        Object params = Json.obj(msg) == null ? null : Json.obj(msg).get("params");
        if (method == null) {
            channel.fail(id, "missing method");
            return;
        }
        switch (method) {
            case "initialize" -> {
                initialize(params);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("accepted", Boolean.TRUE);
                channel.respond(id, ok);
            }
            case "fetchModel" -> channel.respond(id, fetchModel());
            case "execute" -> execute(channel, id, params);
            case "shutdown" -> {
                running = false;
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stopped", Boolean.TRUE);
                channel.respond(id, ok);
                closeQuietly();
                inbox.offer(POISON);
            }
            default -> channel.fail(id, "unknown method " + method);
        }
    }

    private void initialize(Object params) throws IOException {
        closeQuietly();
        String project = Json.str(params, "projectDir");
        if (project == null) throw new IOException("initialize requires projectDir");
        projectDir = new File(project);
        Object dist = Json.obj(params) == null ? null : Json.obj(params).get("distribution");
        String type = Json.str(dist, "type");
        String value = Json.str(dist, "value");
        if (type == null) type = "WRAPPER";
        String java = Json.str(params, "javaHome");
        javaHome = java == null ? null : new File(java);
        String userHome = Json.str(params, "gradleUserHome");
        gradleUserHome = userHome == null ? null : new File(userHome);
        args = strings(params, "args");
        jvmArgs = strings(params, "jvmArgs");
        env = stringMap(params, "env");
        String model = Json.str(params, "modelOut");
        if (model == null) {
            modelOut = File.createTempFile("dev-ide-model-", ".json");
            modelOut.deleteOnExit();
        } else {
            modelOut = new File(model);
        }
        if ("WRAPPER".equals(type)) {
            String version = wrapperVersion(projectDir);
            if (version != null && !atLeast(version, 8, 6)) {
                throw new IOException("Gradle " + version
                        + " is older than 8.6. This project stays on the static Gradle reader.");
            }
        }
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(projectDir);
        if (gradleUserHome != null) connector.useGradleUserHomeDir(gradleUserHome);
        if ("LOCAL".equals(type)) {
            if (value == null) throw new IOException("LOCAL distribution requires a path");
            connector.useInstallation(new File(value));
        } else if ("VERSION".equals(type)) {
            if (value == null) throw new IOException("VERSION distribution requires a version");
            connector.useGradleVersion(value);
        }
        connection = connector.connect();
    }

    private Map<String, Object> fetchModel() throws Exception {
        requireConnection();
        if (modelOut.exists() && !modelOut.delete()) {
            // A previous fetch's file must not be mistaken for this one.
        }
        BuildEnvironment envModel = operation(connection.model(BuildEnvironment.class)).get();
        String version = envModel.getGradle().getGradleVersion();
        if (modelOut.isFile()) {
            String text = Files.readString(modelOut.toPath(), StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            Map<String, Object> model = Json.obj(parsed);
            if (model == null) throw new IOException("model file was not a JSON object");
            model.put("gradleVersion", version);
            return model;
        }
        GradleProject project = operation(connection.model(GradleProject.class)).get();
        Map<String, Object> fallback = fallback(project);
        fallback.put("gradleVersion", version);
        return fallback;
    }

    private void execute(FrameChannel channel, long id, Object params) throws Exception {
        requireConnection();
        List<String> tasks = new ArrayList<>();
        for (Object t : Json.arr(params, "tasks")) {
            if (t instanceof String) tasks.add((String) t);
        }
        if (tasks.isEmpty()) throw new IOException("execute requires tasks");
        List<String> extra = strings(params, "args");
        CancellationTokenSource cts = GradleConnector.newCancellationTokenSource();
        current = cts;
        FrameChannel.LineLog stdout = new FrameChannel.LineLog(channel, id, "stdout");
        FrameChannel.LineLog stderr = new FrameChannel.LineLog(channel, id, "stderr");
        try {
            BuildLauncher launcher = connection.newBuild();
            configure(launcher);
            if (!extra.isEmpty()) {
                List<String> all = new ArrayList<>(buildArgs());
                all.addAll(extra);
                launcher.withArguments(all);
            }
            launcher.forTasks(tasks.toArray(String[]::new));
            launcher.setStandardOutput(stdout);
            launcher.setStandardError(stderr);
            launcher.withCancellationToken(cts.token());
            launcher.addProgressListener((ProgressListener) event -> onProgress(channel, id, event),
                    java.util.Set.of(OperationType.TASK));
            launcher.run();
            stdout.flushRemainder();
            stderr.flushRemainder();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", Boolean.TRUE);
            channel.respond(id, ok);
        } finally {
            current = null;
            cts.cancel();
        }
    }

    private void onProgress(FrameChannel channel, long id, ProgressEvent event) {
        try {
            String task;
            String state;
            if (event instanceof TaskStartEvent start) {
                task = start.getDescriptor().getTaskPath();
                state = "started";
            } else if (event instanceof TaskFinishEvent finish) {
                task = finish.getDescriptor().getTaskPath();
                Object result = finish.getResult();
                if (result instanceof TaskFailureResult) state = "failed";
                else if (result instanceof TaskSuccessResult success && success.isUpToDate()) state = "up-to-date";
                else if (result instanceof TaskSkippedResult) state = "skipped";
                else state = "succeeded";
            } else {
                return;
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("event", "progress");
            msg.put("id", id);
            msg.put("task", task);
            msg.put("state", state);
            channel.event(msg);
        } catch (IOException ignored) {
            // The client went away; the build's own cancellation follows.
        }
    }

    private <T extends LongRunningOperation> T operation(T op) {
        return configure(op);
    }

    private <T extends LongRunningOperation> T configure(T op) {
        if (javaHome != null) op.setJavaHome(javaHome);
        if (!jvmArgs.isEmpty()) op.setJvmArguments(jvmArgs);
        if (!env.isEmpty()) op.setEnvironmentVariables(env);
        List<String> arguments = buildArgs();
        if (!arguments.isEmpty()) op.withArguments(arguments);
        return op;
    }

    private List<String> buildArgs() {
        List<String> all = new ArrayList<>(args);
        boolean hasModel = false;
        for (String arg : all) {
            if (arg.startsWith("-Pdev.ide.model.out=")) hasModel = true;
        }
        if (!hasModel && modelOut != null) {
            all.add("-Pdev.ide.model.out=" + modelOut.getAbsolutePath());
        }
        return all;
    }

    private void requireConnection() throws IOException {
        if (connection == null) throw new IOException("initialize was not called");
    }

    private void closeQuietly() {
        ProjectConnection c = connection;
        connection = null;
        if (c != null) {
            try { c.close(); } catch (RuntimeException ignored) {}
        }
    }

    private static Map<String, Object> fallback(GradleProject project) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", project.getName());
        List<Object> modules = new ArrayList<>();
        collect(project, modules);
        root.put("modules", modules);
        root.put("issues", List.of());
        root.put("buildToolsVersions", List.of());
        root.put("platforms", List.of());
        root.put("ndkVersions", List.of());
        root.put("cmakeVersions", List.of());
        return root;
    }

    private static void collect(GradleProject project, List<Object> modules) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("name", project.getName());
        module.put("path", project.getPath());
        module.put("dir", project.getProjectDirectory().getAbsolutePath());
        module.put("plugins", List.of());
        module.put("application", Boolean.FALSE);
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("name", "main");
        variant.put("buildType", "main");
        variant.put("flavors", List.of());
        variant.put("assembleTask", "assemble");
        variant.put("debuggable", Boolean.TRUE);
        module.put("variants", List.of(variant));
        module.put("sourceSets", List.of());
        module.put("dependencies", List.of());
        modules.add(module);
        for (GradleProject child : project.getChildren()) collect(child, modules);
    }

    private static List<String> strings(Object params, String key) {
        List<String> out = new ArrayList<>();
        for (Object item : Json.arr(params, key)) {
            if (item instanceof String) out.add((String) item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object params, String key) {
        Map<String, Object> parent = Json.obj(params);
        if (parent == null) return Map.of();
        Object raw = parent.get(key);
        if (!(raw instanceof Map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
            if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
        }
        return out;
    }

    static String wrapperVersion(File projectDir) {
        File props = new File(projectDir, "gradle/wrapper/gradle-wrapper.properties");
        if (!props.isFile()) return null;
        try {
            String text = Files.readString(props.toPath(), StandardCharsets.UTF_8);
            int idx = text.indexOf("distributionUrl=");
            if (idx < 0) return null;
            String url = text.substring(idx);
            int nl = url.indexOf('\n');
            if (nl >= 0) url = url.substring(0, nl);
            int marker = url.indexOf("gradle-");
            if (marker < 0) return null;
            String rest = url.substring(marker + "gradle-".length());
            int dash = rest.indexOf('-');
            if (dash < 0) return null;
            return rest.substring(0, dash);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when {@code version} is at least {@code major.minor}. {@code 8.10} is above {@code 8.6}. */
    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("[.+-]");
        int maj;
        int min;
        try {
            maj = Integer.parseInt(parts[0]);
            min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return false;
        }
        if (maj != major) return maj > major;
        return min >= minor;
    }

    private static final Object POISON = new Object();
}
