package com.shyam.qualitylens;

import com.shyam.qualitylens.RetryClassification.OverallOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryWorkflowTest {

    private static final String FEATURE_URI = "classpath:features/sample.feature";
    private static final int SCENARIO_LINE = 4;

    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsRetryForAnEmptyRerunFileAndRemovesStaleRetryOutputs() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("retry.json"), "stale retry", StandardCharsets.UTF_8);
        Files.writeString(target.resolve("retry-classification.json"), "stale report",
                StandardCharsets.UTF_8);
        List<String> calls = new ArrayList<>();
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                calls.add("first");
                write(paths.firstRunJson(), featureJson("passed"));
                write(paths.firstRunRerun(), "");
                return 0;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) {
                calls.add("retry");
                return 0;
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();

        assertEquals(List.of("first"), calls);
        assertEquals(OverallOutcome.PASS, result.classification().outcome());
        assertEquals(Optional.empty(), result.retryExitStatus());
        assertFalse(Files.exists(target.resolve("retry.json")));
        assertReportContains(target.resolve("retry-classification.json"), "\"outcome\":\"PASS\"");
        assertReportContains(target.resolve("retry-classification.html"), "Retry classification: PASS");
    }

    @Test
    void runsOneRetryAfterTheFirstRunAndPreservesFirstRunArtifacts() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        List<String> calls = new ArrayList<>();
        String html = "<html>first-run</html>";
        String rerun = FEATURE_URI + ":" + SCENARIO_LINE;
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                calls.add("first");
                write(paths.firstRunJson(), featureJson("failed"));
                write(paths.firstRunHtml(), html);
                write(paths.firstRunRerun(), rerun);
                return 1;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) throws IOException {
                calls.add("retry");
                assertEquals(rerun, Files.readString(rerunFile, StandardCharsets.UTF_8));
                assertEquals(html, Files.readString(paths.firstRunHtml(), StandardCharsets.UTF_8));
                assertEquals(featureJson("failed"),
                        Files.readString(paths.firstRunJson(), StandardCharsets.UTF_8));
                assertEquals(rerun,
                        Files.readString(paths.firstRunRerun(), StandardCharsets.UTF_8));
                write(paths.retryJson(), featureJson("passed"));
                return 0;
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();

        assertEquals(List.of("first", "retry"), calls);
        assertEquals(1, result.firstRunExitStatus());
        assertEquals(Optional.of(0), result.retryExitStatus());
        assertEquals(OverallOutcome.FLAKY_PASS, result.classification().outcome());
        assertEquals(1, result.exitCode());
        assertReportContains(target.resolve("retry-classification.json"), "\"workflowExitCode\":1");
        assertEquals(html, Files.readString(target.resolve("cucumber-report.html"),
                StandardCharsets.UTF_8));
        assertEquals(featureJson("failed"), Files.readString(target.resolve("first-run.json"),
                StandardCharsets.UTF_8));
        assertEquals(rerun, Files.readString(target.resolve("first-run-rerun.txt"),
                StandardCharsets.UTF_8));
    }

    @Test
    void writesAClassificationReportWhenEvidenceIsInvalid() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.firstRunJson(), "{invalid");
                write(paths.firstRunRerun(), "");
                return 0;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) {
                throw new AssertionError("Retry must not run for an empty rerun file");
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();
        Path report = target.resolve("retry-classification.json");

        assertEquals(OverallOutcome.EVIDENCE_ERROR, result.classification().outcome());
        assertEquals(1, result.exitCode());
        assertTrue(Files.isRegularFile(report));
        assertReportContains(report, "\"outcome\":\"EVIDENCE_ERROR\"");
        assertReportContains(report, "\"evidenceIssues\"");
    }

    @Test
    void reportContainsAttachmentReferencesButNotScreenshotPayloads() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        String payload = "private-base64-screenshot-payload";
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.firstRunJson(), featureJsonWithAttachment("failed", payload));
                write(paths.firstRunRerun(), FEATURE_URI + ":" + SCENARIO_LINE);
                return 1;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.retryJson(), featureJson("passed"));
                return 0;
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();
        String report = Files.readString(target.resolve("retry-classification.json"),
                StandardCharsets.UTF_8);

        assertEquals(OverallOutcome.FLAKY_PASS, result.classification().outcome());
        assertTrue(report.contains("\"attachments\""));
        assertTrue(report.contains("\"mimeType\":\"image/png\""));
        assertFalse(report.contains(payload));
        String html = Files.readString(target.resolve("retry-classification.html"),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("Screenshot reference"));
        assertTrue(html.contains("first-run.json"));
        assertFalse(html.contains(payload));
    }

    @Test
    void htmlReportEscapesDynamicTextAndShowsBothAttemptsAndErrors() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        String scenarioName = "<script>alert('x') & \"quoted\"</script>";
        String error = "<img src=x onerror=\"alert('x')\">";
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.firstRunJson(), featureJson(scenarioName, "failed", error));
                write(paths.firstRunRerun(), FEATURE_URI + ":" + SCENARIO_LINE);
                return 1;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.retryJson(), featureJson(scenarioName, "passed", null));
                return 0;
            }
        });

        workflow.execute();
        String html = Files.readString(target.resolve("retry-classification.html"),
                StandardCharsets.UTF_8);

        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;) &amp; &quot;quoted&quot;&lt;/script&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=&quot;alert(&#39;x&#39;)&quot;&gt;"));
        assertFalse(html.contains(scenarioName));
        assertTrue(html.contains("FAILED"));
        assertTrue(html.contains("PASSED"));
        assertTrue(html.contains("FLAKY_PASS"));
        assertTrue(html.contains("First-run JSON report"));
        assertTrue(html.contains("Retry JSON report"));
    }

    @Test
    void nonzeroFirstRunStatusCannotReturnSuccessfulWorkflowResult() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.firstRunJson(), featureJson("passed"));
                write(paths.firstRunRerun(), "");
                return 2;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) {
                throw new AssertionError("Retry must not run for an empty rerun file");
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();

        assertEquals(OverallOutcome.PASS, result.classification().outcome());
        assertEquals(2, result.firstRunExitStatus());
        assertEquals(1, result.exitCode());
        assertReportContains(target.resolve("retry-classification.json"), "\"workflowExitCode\":1");
        assertReportContains(target.resolve("retry-classification.html"), "workflow exit code: 1");
    }

    @Test
    void nonzeroRetryStatusCannotReturnSuccessfulWorkflowResult() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        RetryWorkflow workflow = new RetryWorkflow(target, new RetryWorkflow.ExecutionSteps() {
            @Override
            public int runFirstRun(RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.firstRunJson(), featureJson("failed"));
                write(paths.firstRunRerun(), FEATURE_URI + ":" + SCENARIO_LINE);
                return 0;
            }

            @Override
            public int runRetry(Path rerunFile, RetryWorkflow.WorkflowPaths paths) throws IOException {
                write(paths.retryJson(), featureJson("passed"));
                return 3;
            }
        });

        RetryWorkflow.WorkflowResult result = workflow.execute();

        assertEquals(OverallOutcome.FLAKY_PASS, result.classification().outcome());
        assertEquals(Optional.of(3), result.retryExitStatus());
        assertEquals(1, result.exitCode());
        assertReportContains(target.resolve("retry-classification.json"), "\"retryExitStatus\":3");
        assertReportContains(target.resolve("retry-classification.html"), "workflow exit code: 1");
    }

    @Test
    void mapsEveryClassificationOutcomeToTheRequiredExitStatus() {
        assertEquals(0, RetryWorkflow.exitCode(OverallOutcome.PASS));
        assertEquals(0, RetryWorkflow.exitCode(OverallOutcome.FLAKY_PASS));
        assertEquals(1, RetryWorkflow.exitCode(OverallOutcome.FLAKY_FAIL));
        assertEquals(1, RetryWorkflow.exitCode(OverallOutcome.INCONCLUSIVE));
        assertEquals(1, RetryWorkflow.exitCode(OverallOutcome.EVIDENCE_ERROR));
    }

    private static String featureJson(String status) {
        return featureJson("sample", status, null);
    }

    private static String featureJson(String name, String status, String error) {
        String result = "{\"status\":\"" + status + "\"";
        if (error != null) {
            result += ",\"error_message\":\"" + jsonEscape(error) + "\"";
        }
        result += "}";
        return "[{\"uri\":\"" + FEATURE_URI + "\",\"elements\":[{"
                + "\"type\":\"scenario\",\"keyword\":\"Scenario\",\"name\":\""
                + jsonEscape(name) + "\","
                + "\"id\":\"sample\",\"line\":" + SCENARIO_LINE + ","
                + "\"steps\":[{\"result\":" + result + "}],"
                + "\"before\":[],\"after\":[]}]}]";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String featureJsonWithAttachment(String status, String imageData) {
        return featureJson(status).replace("\"after\":[]",
                "\"after\":[{\"result\":{\"status\":\"passed\"},\"embeddings\":[{"
                        + "\"mime_type\":\"image/png\",\"data\":\"" + imageData + "\"}]}]");
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void assertReportContains(Path path, String expected) throws IOException {
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains(expected));
    }
}
