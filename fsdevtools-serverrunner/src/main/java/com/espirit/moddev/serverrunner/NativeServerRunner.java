package com.espirit.moddev.serverrunner;


import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

import static java.util.stream.Collectors.joining;

@Slf4j
public class NativeServerRunner implements ServerRunner {

    private static final String PROBLEM_READING = "Problem reading data from FirstSpirit server process";

    protected ServerProperties serverProperties;
    /**
     * potentially contains the task that logs the FirstSpirit output (might not be filled in case we did not start the server ourselves)
     */
    protected Optional<FutureTask<Void>> serverTask = Optional.empty();
    protected ExecutorService executor = Executors.newCachedThreadPool();

    public NativeServerRunner(final ServerProperties serverProperties) {
        this.serverProperties = Objects.requireNonNull(serverProperties);
    }

    /**
     * Waits for a given condition, retrying if necessary, blocking the thread in between.
     *
     * @param condition the condition to be checked
     * @param waitTime  the time to wait between queries to `condition`
     * @param triesLeft the number of tries that should be used at max until the condition needs to be true. Should be larger than 0.
     * @return the value of the last call of `condition`.
     */
    static boolean waitForCondition(final Supplier<Boolean> condition, final Duration waitTime, final int triesLeft) {
        if (triesLeft > 0) {
            if (condition.get()) {
                return true;
            } else {
                try {
                    Thread.sleep(waitTime.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); //reset interrupt flag
                }
                return waitForCondition(condition, waitTime, triesLeft - 1);
            }
        } else {
            return false;
        }
    }

    /**
     * Prepares the file system for startup of a FirstSpirit server, e.g. creates `fs-init`, `fs-server.policy` and `fs-license.conf`.
     *
     * @param serverProperties the server properties to be used
     * @return Commandline arguments that should be added to the startup of the server
     * @throws java.io.IOException on file system access problems
     */
    static List<String> prepareFilesystem(final ServerProperties serverProperties) throws IOException {
        final List<String> args = new ArrayList<>();
        final Path fsServerRoot = serverProperties.getServerRoot();

        final Path serverDir = fsServerRoot.resolve("server");
        final Path confDir = fsServerRoot.resolve("conf");

        final Path initFile = serverDir.resolve("fs-init");
        final Path policyFile = confDir.resolve("fs-server.policy");
        final Path confFile = confDir.resolve("fs-server.conf");

        Files.createDirectories(serverDir);
        Files.createDirectories(confDir);

        Files.write(initFile, Collections.emptyList());

        if (serverProperties.getLicenseFileSupplier().get().isPresent()) {
            Files
                .copy(serverProperties.getLicenseFileSupplier().get().get(), confDir.resolve("fs-license.conf"), StandardCopyOption.REPLACE_EXISTING);
        }

        //either update an existing conf file, or if none exists, use the one from the class path
        try (BufferedReader reader = confFile.toFile().exists() ?
                                     Files.newBufferedReader(confFile) :
                                     new BufferedReader(
                                         new InputStreamReader(NativeServerRunner.class.getResourceAsStream("/" + confFile.getFileName().toString()),
                                                               StandardCharsets.UTF_8)
                                     )) {
            final Properties properties = new Properties();
            properties.load(reader);
            properties.setProperty("HTTP_PORT", String.valueOf(serverProperties.getServerPort()));

            try (FileWriter fileWriter = new FileWriter(confFile.toFile())) {
                properties.store(fileWriter, "");
            }
        }
        Files.write(policyFile, Arrays.asList(
            "/* policies for CMS-Server */",
            "",
            "grant {",
            "  permission java.security.AllPermission;",
            "};")); // basic file

        return args;
    }

    /**
     * Prepare system and generate startup parameter list. Performs side-effects on the file system.
     *
     * @param serverProperties the server properties to be used
     * @return startup parameter list
     * @throws java.io.IOException on file system access problems
     */
    static List<String> prepareStartup(final ServerProperties serverProperties) throws IOException {
        final Path fsServerRoot = serverProperties.getServerRoot();
        final ArrayList<String> args = new ArrayList<>();
        args.add("java");

        if (serverProperties.isServerGcLog()) {
            args.add("-Xloggc:" + serverProperties.getServerRoot().resolve("log").resolve("fs-gc.log"));
        }

        args.addAll(serverProperties.getServerOps());

        if (serverProperties.isServerInstall()) {
            prepareFilesystem(serverProperties);
        }
        args.add("-Dcmsroot=" + fsServerRoot);
        args.add("-Djava.security.policy=" + fsServerRoot.resolve("conf").resolve("fs-server.policy"));
        args.addAll(Arrays.asList("-cp", serverProperties.getFirstSpiritJars().stream().map(File::toString).collect(joining(String.valueOf(java.io.File.pathSeparatorChar)))));
        args.add("de.espirit.firstspirit.server.CMSServer");

        return args;
    }

    /**
     * Tests if a connection to the booted FirstSpirit server can be made, or not
     *
     * @param serverProperties the server properties to be used
     * @return whether the connection was successfully established
     */
    static boolean testConnection(final ServerProperties serverProperties) {
        //currently HTTP_MODE is the only mode available - just want to point out clearly that this will only work for that mode.
        if (serverProperties.getMode() == ServerProperties.ConnectionMode.HTTP_MODE) {
            return HttpConnectionTester.testConnection(serverProperties.getServerUrl());
        } else {
            return false;
        }
    }

    /**
     * Boots a FirstSpirit server, according to configuration
     *
     * @param serverProperties The server properties to be used
     * @param executor         The executor where tasks should be run on. Needs to supply at least 2 threads at the same time.
     * @return a cancellable task that is already running
     * @throws java.io.IOException on file system access problems
     */
    @SuppressWarnings({"squid:S1141", "squid:S1188"}) //nested try and too long lambda
    static FutureTask<Void> startFirstSpiritServer(final ServerProperties serverProperties, final ExecutorService executor) throws IOException {
        final List<String> commands = Collections.unmodifiableList(new ArrayList<>(prepareStartup(serverProperties)));
        if (log.isInfoEnabled()) {
            log.info("Execute command " + String.join(" ", commands));
        }

        //start FirstSpirit async
        /* Construct a cancellable logging task. It will be cancelled in `stopFirstSpiritServer`.
           The inner logTask is necessary since the implicit `readLine()` on the `BufferedReader` has a blocking API that cannot be interrupted. This
           task is stopped by destroying the process outputting data, which implicitly closes the input stream that is being blocked on. You can view
           cancellableLogTask as an entity that does the very same job as logTask with the added functionality of gracefully shutting down on server
           stop.
         */
        final FutureTask<Void> cancellableLogTask = new FutureTask<>(() -> {
            final ProcessBuilder builder = new ProcessBuilder(commands);
            builder.redirectErrorStream(true);
            final Process process;
            try {
                process = builder.start();
                //start logging on another task to be able to be interrupted to destroy the original process because it hangs sometimes
                final FutureTask<Void> logTask = new FutureTask<>(() -> {
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).lines()
                        .forEach(line -> log.info("FirstSpirit Server log:" + line));
                    return null; //that one hurts
                });
                executor.submit(logTask);
                try {
                    logTask.get();
                } catch (final InterruptedException ie) {
                    process.destroy();  //kill the process if it did not die on its own
                    Thread.currentThread().interrupt();
                }
            } catch (final IOException ioe) {
                log.error(PROBLEM_READING, ioe);
            }
            return null; //that one hurts
        });

        executor.submit(cancellableLogTask);
        return cancellableLogTask;
    }

    /**
     * Prepare command line arguments to stop the server
     *
     * @param serverProperties the server properties to be used
     * @return command line arguments to stop the server
     */
    static List<String> prepareStop(final ServerProperties serverProperties) {
        final List<String> args = new ArrayList<>();
        args.add("java");
        args.addAll(Arrays.asList("-cp", serverProperties.getFirstSpiritJars().stream().map(File::toString).collect(joining(String.valueOf(java.io.File.pathSeparatorChar)))));
        args.add("-Dhost=" + serverProperties.getServerHost());
        args.add("-Dport=" + serverProperties.getServerPort());
        args.add("-Dmode=HTTP");
        args.add("de.espirit.firstspirit.server.ShutdownServer");
        return args;
    }

    private static boolean stopFirstSpiritServer(final ServerProperties serverProperties, final Optional<FutureTask<Void>> serverTask) {
        final ProcessBuilder builder = new ProcessBuilder(prepareStop(serverProperties));
        builder.redirectErrorStream(true);
        try {
            final Process process = builder.start();
            new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).lines()
                .forEach(line -> log.info("FirstSpirit shutdown log:" + line));
        } catch (final IOException ioe) {
            log.error(PROBLEM_READING, ioe);
            return false;
        }
        //ensure the FS lock file is removed (indicates that the server is still running if the lock file is still there)
        waitForCondition(() -> !serverProperties.getLockFile().exists(), Duration.ofSeconds(1), 15);
        try {
            Thread.sleep(500);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        serverTask.ifPresent(x -> x.cancel(true)); //kill running process if it did not die itself
        return !testConnection(serverProperties);
    }

    @Override
    public boolean start() {
        if (!testConnection(serverProperties)) {
            log.info("Starting FirstSpirit Server...");
            boolean serverRunning = false;
            try {
                if (!serverTask.isPresent()) {
                    serverTask = Optional.of(startFirstSpiritServer(serverProperties, executor));
                }

                serverRunning = waitForCondition(() -> {
                                                     log.info("Trying to connect to FirstSpirit server...");
                                                     return testConnection(serverProperties);
                                                 }, serverProperties.getThreadWait(),
                                                 serverProperties.getConnectionRetryCount()
                                                 + 1); //retry count means we try one more time allover
            } catch (final IOException ioe) {
                //nothing to do, server will not be running in this case, normal behaviour following
                log.error(PROBLEM_READING, ioe);
            }
            if (!serverRunning) {
                log.error("Could not start FirstSpirit server.");
            }
            return serverRunning;

        } else {
            log.info("FirstSpirit Server already running.");
            return true;
        }
    }

    @Override
    public boolean isRunning() {
        return testConnection(serverProperties);
    }

    @Override
    public boolean stop() {
        return stopFirstSpiritServer(serverProperties, serverTask);
    }
}
