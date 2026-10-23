package com.shyam.qualitylens;

import io.cucumber.core.cli.Main;
import io.cucumber.core.feature.FeatureWithLines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class RunFailedScenarios {

    private RunFailedScenarios() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !args[0].startsWith("@") || args[0].length() == 1) {
            throw new IllegalArgumentException("Expected exactly one @<rerun-file> argument");
        }

        Path rerunFile = Path.of(args[0].substring(1));
        byte exitStatus = run(rerunFile, Path.of("target"));
        if (exitStatus != 0) {
            throw new IllegalStateException("Cucumber retry run failed with exit status " + exitStatus);
        }
    }

    public static byte run(Path rerunFile, Path outputDirectory) throws IOException {
        if (!Files.isRegularFile(rerunFile)) {
            throw new IllegalArgumentException("Rerun file does not exist: " + rerunFile);
        }

        List<String> entries = Files.readAllLines(rerunFile, StandardCharsets.UTF_8).stream()
                .flatMap(line -> Arrays.stream(line.trim().split("\\s+")))
                .filter(entry -> !entry.isEmpty())
                .toList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Rerun file contains no scenario locations: " + rerunFile);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (String entry : entries) {
            FeatureWithLines location = FeatureWithLines.parse(entry);
            if (location.lines().isEmpty()
                    || location.lines().stream().anyMatch(line -> line < 1)
                    || !"classpath".equals(location.uri().getScheme())
                    || classLoader.getResource(location.uri().getSchemeSpecificPart()) == null) {
                throw new IllegalArgumentException("Invalid or unresolved scenario location: " + entry);
            }
        }

        return Main.run(
                "@" + rerunFile,
                "--glue", "com.shyam.qualitylens.steps",
                "--plugin", "html:" + outputDirectory.resolve("retry-cucumber-report.html"),
                "--plugin", "json:" + outputDirectory.resolve("retry.json"),
                "--plugin", "rerun:" + outputDirectory.resolve("retry-rerun.txt")
        );
    }
}
