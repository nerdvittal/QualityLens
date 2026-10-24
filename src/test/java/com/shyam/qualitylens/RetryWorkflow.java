package com.shyam.qualitylens;

import com.shyam.qualitylens.RetryClassification.AttemptResult;
import com.shyam.qualitylens.RetryClassification.AttachmentReference;
import com.shyam.qualitylens.RetryClassification.EvidenceIssue;
import com.shyam.qualitylens.RetryClassification.OverallOutcome;
import com.shyam.qualitylens.RetryClassification.ScenarioResult;
import com.shyam.qualitylens.RetryClassification.StatusObservation;
import io.cucumber.core.cli.Main;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RetryWorkflow {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final List<String> OUTPUT_FILES = List.of(
            "cucumber-report.html",
            "first-run.json",
            "first-run-rerun.txt",
            "retry-cucumber-report.html",
            "retry.json",
            "retry-rerun.txt",
            "retry-classification.json",
            "retry-classification.html");

    private final Path targetDirectory;
    private final ExecutionSteps executionSteps;

    public RetryWorkflow(Path targetDirectory, ExecutionSteps executionSteps) {
        this.targetDirectory = Objects.requireNonNull(targetDirectory, "targetDirectory");
        this.executionSteps = Objects.requireNonNull(executionSteps, "executionSteps");
    }

    public static RetryWorkflow forProject(Path targetDirectory) {
        return forProject(targetDirectory, "classpath:features");
    }

    public static RetryWorkflow forProject(Path targetDirectory, String featureSelector) {
        Objects.requireNonNull(featureSelector, "featureSelector");
        return new RetryWorkflow(targetDirectory, new ExecutionSteps() {
            @Override
            public int runFirstRun(WorkflowPaths paths) {
                return Main.run(
                        featureSelector,
                        "--glue", "com.shyam.qualitylens.steps",
                        "--plugin", "html:" + paths.firstRunHtml(),
                        "--plugin", "json:" + paths.firstRunJson(),
                        "--plugin", "rerun:" + paths.firstRunRerun());
            }

            @Override
            public int runRetry(Path rerunFile, WorkflowPaths paths) throws IOException {
                return RunFailedScenarios.run(rerunFile, paths.targetDirectory());
            }
        });
    }

    public WorkflowResult execute() throws IOException {
        Files.createDirectories(targetDirectory);
        WorkflowPaths paths = workflowPaths();
        for (String fileName : OUTPUT_FILES) {
            Files.deleteIfExists(targetDirectory.resolve(fileName));
        }

        List<String> executionErrors = new ArrayList<>();
        int firstRunExitStatus;
        try {
            firstRunExitStatus = executionSteps.runFirstRun(paths);
        } catch (IOException | RuntimeException exception) {
            firstRunExitStatus = -1;
            executionErrors.add("First-run execution failed: " + message(exception));
        }
        boolean retryAttempted = false;
        try {
            retryAttempted = containsRerunEntries(paths.firstRunRerun());
        } catch (IOException | RuntimeException exception) {
            executionErrors.add("Could not inspect first-run rerun file: " + message(exception));
        }
        Optional<Integer> retryExitStatus = Optional.empty();
        if (retryAttempted) {
            try {
                retryExitStatus = Optional.of(
                        executionSteps.runRetry(paths.firstRunRerun(), paths));
            } catch (IOException | RuntimeException exception) {
                executionErrors.add("Retry execution failed: " + message(exception));
            }
        }

        Optional<Path> retryJson = retryAttempted
                ? Optional.of(paths.retryJson())
                : Optional.empty();
        RetryClassification classification = new RetryClassifier().classify(
                paths.firstRunJson(), paths.firstRunRerun(), retryJson);
        boolean expectedPass = classification.outcome() == OverallOutcome.PASS
                && firstRunExitStatus == 0
                && retryExitStatus.isEmpty();
        boolean expectedRecovery = classification.outcome() == OverallOutcome.FLAKY_PASS
                && firstRunExitStatus == 1
                && retryExitStatus.equals(Optional.of(0));
        int exitCode = executionErrors.isEmpty()
                && (expectedPass || expectedRecovery)
                ? exitCode(classification.outcome())
                : 1;
        WorkflowResult result = new WorkflowResult(
                classification, firstRunExitStatus, retryExitStatus, exitCode, executionErrors);
        writeReport(paths.classificationReport(), result);
        writeHtmlReport(paths.htmlReport(), paths, result);
        return result;
    }

    public static int exitCode(OverallOutcome outcome) {
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case PASS, FLAKY_PASS -> 0;
            case FLAKY_FAIL, INCONCLUSIVE, EVIDENCE_ERROR -> 1;
        };
    }

    private WorkflowPaths workflowPaths() {
        return new WorkflowPaths(
                targetDirectory,
                targetDirectory.resolve("cucumber-report.html"),
                targetDirectory.resolve("first-run.json"),
                targetDirectory.resolve("first-run-rerun.txt"),
                targetDirectory.resolve("retry-cucumber-report.html"),
                targetDirectory.resolve("retry.json"),
                targetDirectory.resolve("retry-rerun.txt"),
                targetDirectory.resolve("retry-classification.json"),
                targetDirectory.resolve("retry-classification.html"));
    }

    private static boolean containsRerunEntries(Path rerunFile) throws IOException {
        return Files.isRegularFile(rerunFile)
                && !Files.readString(rerunFile, StandardCharsets.UTF_8).isBlank();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static void writeReport(Path reportPath, WorkflowResult result) throws IOException {
        RetryClassification classification = result.classification();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("outcome", classification.outcome().name());
        report.put("firstRunExitStatus", result.firstRunExitStatus());
        report.put("retryExitStatus", result.retryExitStatus().orElse(null));
        report.put("workflowExitCode", result.exitCode());
        report.put("executionErrors", result.executionErrors());
        report.put("scenarios", classification.scenarios().stream()
                .map(RetryWorkflow::scenarioReport)
                .toList());
        report.put("evidenceIssues", classification.evidenceIssues().stream()
                .map(RetryWorkflow::evidenceIssueReport)
                .toList());
        Files.writeString(reportPath, JSON_MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
    }

    private static void writeHtmlReport(
            Path reportPath,
            WorkflowPaths paths,
            WorkflowResult result) throws IOException {
        RetryClassification classification = result.classification();
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Retry Classification Report</title>
                  <style>
                    body{font-family:system-ui,sans-serif;margin:2rem;color:#222}
                    table{border-collapse:collapse;width:100%%}
                    th,td{border:1px solid #bbb;padding:.55rem;text-align:left;vertical-align:top}
                    th{background:#eee}.errors{color:#8b0000}.attachments{font-size:.9em}
                    .issues{border-left:4px solid #b00;padding-left:1rem}
                  </style>
                </head>
                <body>
                <h1>Retry classification: %s</h1>
                <p>First-run process status: %d; retry process status: %s; workflow exit code: %d</p>
                <h2>Reports</h2>
                <ul>
                """.formatted(
                htmlEscape(classification.outcome().name()),
                result.firstRunExitStatus(),
                result.retryExitStatus().map(String::valueOf).orElse("not run"),
                result.exitCode()));
        appendReportLink(html, paths.firstRunHtml(), "First-run HTML report");
        appendReportLink(html, paths.firstRunJson(), "First-run JSON report");
        appendReportLink(html, paths.firstRunRerun(), "First-run rerun file");
        if (Files.exists(paths.retryHtml())) {
            appendReportLink(html, paths.retryHtml(), "Retry HTML report");
        }
        if (Files.exists(paths.retryJson())) {
            appendReportLink(html, paths.retryJson(), "Retry JSON report");
        }
        if (Files.exists(paths.retryRerun())) {
            appendReportLink(html, paths.retryRerun(), "Retry rerun file");
        }
        html.append("""
                </ul>
                <h2>Scenarios</h2>
                <table>
                <thead><tr><th>Scenario</th><th>Feature</th><th>First attempt</th>
                <th>Retry</th><th>Final outcome</th></tr></thead>
                <tbody>
                """);
        for (ScenarioResult scenario : classification.scenarios()) {
            html.append("<tr><td>").append(htmlEscape(scenario.name()))
                    .append("</td><td>").append(htmlEscape(scenario.key().featureUri().toString()))
                    .append(":").append(scenario.key().line()).append("</td><td>");
            appendAttemptHtml(html, scenario.firstAttempt());
            html.append("</td><td>");
            if (scenario.retryAttempt().isPresent()) {
                appendAttemptHtml(html, scenario.retryAttempt().orElseThrow());
            } else {
                html.append("Not run");
            }
            html.append("</td><td>").append(htmlEscape(scenario.outcome().name()))
                    .append("</td></tr>");
        }
        html.append("</tbody></table><h2>Evidence issues</h2>");
        if (classification.evidenceIssues().isEmpty()) {
            html.append("<p>None</p>");
        } else {
            html.append("<ul class=\"issues\">");
            classification.evidenceIssues().forEach(issue -> html.append("<li><strong>")
                    .append(htmlEscape(issue.type().name())).append("</strong>: ")
                    .append(htmlEscape(issue.message())).append("</li>"));
            html.append("</ul>");
        }
        if (!result.executionErrors().isEmpty()) {
            html.append("<h2>Execution errors</h2><ul class=\"errors\">");
            result.executionErrors().forEach(error ->
                    html.append("<li>").append(htmlEscape(error)).append("</li>"));
            html.append("</ul>");
        }
        html.append("</body></html>");
        Files.writeString(reportPath, html, StandardCharsets.UTF_8);
    }

    private static void appendReportLink(StringBuilder html, Path path, String label) {
        html.append("<li><a href=\"").append(htmlEscape(path.getFileName().toString()))
                .append("\">").append(htmlEscape(label)).append("</a></li>");
    }

    private static void appendAttemptHtml(StringBuilder html, AttemptResult attempt) {
        html.append(htmlEscape(attempt.status().name()));
        if (!attempt.errorMessages().isEmpty()) {
            html.append("<ul class=\"errors\">");
            attempt.errorMessages().forEach(error ->
                    html.append("<li>").append(htmlEscape(error)).append("</li>"));
            html.append("</ul>");
        }
        if (!attempt.attachments().isEmpty()) {
            html.append("<ul class=\"attachments\">");
            attempt.attachments().forEach(attachment -> html.append("<li>Screenshot reference: ")
                    .append("<a href=\"").append(htmlEscape(attachment.reportPath().getFileName().toString()))
                    .append("\">").append(htmlEscape(attachment.reportPath().getFileName().toString()))
                    .append("</a> — ").append(htmlEscape(attachment.kind().name()))
                    .append(", ").append(htmlEscape(attachment.mimeType()))
                    .append(", JSON pointer ").append(htmlEscape(attachment.jsonPointer()))
                    .append("</li>"));
            html.append("</ul>");
        }
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static Map<String, Object> scenarioReport(ScenarioResult scenario) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("featureUri", scenario.key().featureUri().toString());
        report.put("line", scenario.key().line());
        report.put("name", scenario.name());
        report.put("outcome", scenario.outcome().name());
        report.put("firstAttempt", attemptReport(scenario.firstAttempt()));
        report.put("retryAttempt", scenario.retryAttempt()
                .map(RetryWorkflow::attemptReport)
                .orElse(null));
        return report;
    }

    private static Map<String, Object> attemptReport(AttemptResult attempt) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", attempt.status().name());
        report.put("statusObservations", attempt.statusObservations().stream()
                .map(RetryWorkflow::statusObservationReport)
                .toList());
        report.put("errors", attempt.errorMessages());
        report.put("attachments", attempt.attachments().stream()
                .map(RetryWorkflow::attachmentReport)
                .toList());
        return report;
    }

    private static Map<String, Object> statusObservationReport(StatusObservation observation) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evidence", observation.evidence().name());
        report.put("status", observation.status().map(Enum::name).orElse(null));
        return report;
    }

    private static Map<String, Object> attachmentReport(AttachmentReference attachment) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportPath", attachment.reportPath().toString());
        report.put("jsonPointer", attachment.jsonPointer());
        report.put("kind", attachment.kind().name());
        report.put("mimeType", attachment.mimeType());
        return report;
    }

    private static Map<String, Object> evidenceIssueReport(EvidenceIssue issue) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", issue.type().name());
        report.put("key", issue.key().map(key -> Map.of(
                "featureUri", key.featureUri().toString(),
                "line", key.line())).orElse(null));
        report.put("message", issue.message());
        return report;
    }

    public interface ExecutionSteps {
        int runFirstRun(WorkflowPaths paths) throws IOException;

        int runRetry(Path rerunFile, WorkflowPaths paths) throws IOException;
    }

    public record WorkflowPaths(
            Path targetDirectory,
            Path firstRunHtml,
            Path firstRunJson,
            Path firstRunRerun,
            Path retryHtml,
            Path retryJson,
            Path retryRerun,
            Path classificationReport,
            Path htmlReport) {
    }

    public record WorkflowResult(
            RetryClassification classification,
            int firstRunExitStatus,
            Optional<Integer> retryExitStatus,
            int exitCode,
            List<String> executionErrors) {
        public WorkflowResult {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(retryExitStatus, "retryExitStatus");
            executionErrors = List.copyOf(Objects.requireNonNull(executionErrors, "executionErrors"));
        }
    }
}
