package com.shyam.qualitylens;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RetryClassificationTest {

    private static final String FEATURE_URI = "classpath:features/sample.feature";
    private static final int SCENARIO_LINE = 4;

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAnAbsoluteFeatureUriAndPositiveLineNumber() {
        URI featureUri = URI.create("file:///features/example.feature");

        assertDoesNotThrow(() -> new RetryClassification.ScenarioKey(featureUri, 1));
    }

    @Test
    void rejectsARelativeFeatureUri() {
        URI featureUri = URI.create("features/example.feature");

        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.ScenarioKey(featureUri, 1));
    }

    @Test
    void rejectsAZeroLineNumber() {
        URI featureUri = URI.create("file:///features/example.feature");

        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.ScenarioKey(featureUri, 0));
    }

    @Test
    void rejectsANegativeLineNumber() {
        URI featureUri = URI.create("file:///features/example.feature");

        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.ScenarioKey(featureUri, -1));
    }

    @Test
    void rejectsANullFeatureUri() {
        assertThrows(
                NullPointerException.class,
                () -> new RetryClassification.ScenarioKey(null, 1));
    }

    @Test
    void acceptsPresentEvidenceWithARecognizedStatus() {
        assertDoesNotThrow(() -> new RetryClassification.StatusObservation(
                RetryClassification.StatusEvidence.PRESENT,
                Optional.of(RetryClassification.ObservedStatus.PASSED)));
    }

    @Test
    void acceptsPresentEvidenceWithAnUnknownStatus() {
        assertDoesNotThrow(() -> new RetryClassification.StatusObservation(
                RetryClassification.StatusEvidence.PRESENT,
                Optional.of(RetryClassification.ObservedStatus.UNKNOWN)));
    }

    @Test
    void acceptsMissingEvidenceWithoutAStatus() {
        assertDoesNotThrow(() -> new RetryClassification.StatusObservation(
                RetryClassification.StatusEvidence.MISSING,
                Optional.empty()));
    }

    @Test
    void acceptsMalformedEvidenceWithoutAStatus() {
        assertDoesNotThrow(() -> new RetryClassification.StatusObservation(
                RetryClassification.StatusEvidence.MALFORMED,
                Optional.empty()));
    }

    @Test
    void rejectsMissingEvidenceWithAStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.StatusObservation(
                        RetryClassification.StatusEvidence.MISSING,
                        Optional.of(RetryClassification.ObservedStatus.PASSED)));
    }

    @Test
    void rejectsMalformedEvidenceWithAStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.StatusObservation(
                        RetryClassification.StatusEvidence.MALFORMED,
                        Optional.of(RetryClassification.ObservedStatus.PASSED)));
    }

    @Test
    void rejectsPresentEvidenceWithoutAStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RetryClassification.StatusObservation(
                        RetryClassification.StatusEvidence.PRESENT,
                        Optional.empty()));
    }

    @Test
    void rejectsNullEvidence() {
        assertThrows(
                NullPointerException.class,
                () -> new RetryClassification.StatusObservation(null, Optional.empty()));
    }

    @Test
    void rejectsANullStatusOptional() {
        assertThrows(
                NullPointerException.class,
                () -> new RetryClassification.StatusObservation(
                        RetryClassification.StatusEvidence.MISSING, null));
    }

    @Test
    void classifiesCompletePassingEvidenceAndAnEmptyRerunFileAsPass() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "a passing scenario", "some-id",
                        result("passed"), result("passed"), result("passed"))),
                "");

        assertEquals(RetryClassification.OverallOutcome.PASS, result.outcome());
        assertTrue(result.evidenceIssues().isEmpty());
        assertEquals(RetryClassification.AttemptStatus.PASSED,
                result.scenarios().get(0).firstAttempt().status());
    }

    @Test
    void acceptsValidNonPassingStatusesWhenTheyMatchRerunEntries() throws Exception {
        for (String status : new String[]{"failed", "undefined", "pending", "skipped"}) {
            String json = featureJson(scenario(SCENARIO_LINE, "non-passing scenario", "scenario-id",
                    result(status), result("passed"), result("passed")));
            RetryClassification result = classify(
                    json,
                    rerunEntry(FEATURE_URI, SCENARIO_LINE),
                    featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                            result("passed"), result("passed"), result("passed"))));

            RetryClassification.OverallOutcome expected = "failed".equals(status)
                    ? RetryClassification.OverallOutcome.FLAKY_PASS
                    : RetryClassification.OverallOutcome.INCONCLUSIVE;
            assertEquals(expected, result.outcome(), status);
            assertTrue(result.evidenceIssues().isEmpty(), status);
        }
    }

    @Test
    void preservesStepAndHookErrorMessagesAndRecognizesFailedHooks() throws Exception {
        String first = featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed", "step failure"),
                        result("failed", "hook failure"),
                        result("passed")));
        RetryClassification result = classify(
                first,
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                        result("failed"), result("passed"), result("passed"))));

        assertEquals(RetryClassification.OverallOutcome.FLAKY_FAIL, result.outcome());
        assertEquals(RetryClassification.AttemptStatus.FAILED,
                result.scenarios().get(0).firstAttempt().status());
        assertTrue(result.scenarios().get(0).firstAttempt().errorMessages()
                .containsAll(List.of("step failure", "hook failure")));
    }

    @Test
    void reportsANonPassingScenarioMissingFromTheRerunFile() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MISSING);
        assertEquals(RetryClassification.AttemptStatus.FAILED,
                result.scenarios().get(0).firstAttempt().status());
    }

    @Test
    void reportsAnUnmatchedRerunEntry() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))),
                rerunEntry("classpath:features/other.feature", SCENARIO_LINE));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.UNMATCHED);
    }

    @Test
    void reportsARerunEntryForAPassingScenarioAsUnmatched() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.UNMATCHED);
    }

    @Test
    void reportsDuplicateFirstRunScenarioKeys() throws Exception {
        String duplicate = scenario(SCENARIO_LINE, "first name", "first-id",
                result("passed"), result("passed"), result("passed"))
                + ","
                + scenario(SCENARIO_LINE, "different name", "different-id",
                        result("passed"), result("passed"), result("passed"));
        RetryClassification result = classify(featureJson(duplicate), "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.DUPLICATE);
        assertEquals(RetryClassification.OverallOutcome.EVIDENCE_ERROR, result.outcome());
        assertEquals(2, result.scenarios().size());
        assertTrue(result.scenarios().stream().allMatch(scenario ->
                scenario.outcome() == RetryClassification.ScenarioOutcome.EVIDENCE_ERROR));
    }

    @Test
    void reportsAnEmptyFeatureElementsArrayAsEvidenceError() throws Exception {
        RetryClassification result = classify(
                "[{\"uri\":\"" + FEATURE_URI + "\",\"elements\":[]}]",
                "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
        assertEquals(RetryClassification.OverallOutcome.EVIDENCE_ERROR, result.outcome());
    }

    @Test
    void reportsDuplicateRerunEntries() throws Exception {
        String entry = rerunEntry(FEATURE_URI, SCENARIO_LINE);
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                entry + System.lineSeparator() + entry);

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.DUPLICATE);
    }

    @Test
    void reportsMalformedFirstRunJson() throws Exception {
        RetryClassification result = classify("{not-json", "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsMissingFirstRunJson() throws Exception {
        Path rerun = temporaryDirectory.resolve("first-run-rerun.txt");
        Files.writeString(rerun, "");

        RetryClassification result = new RetryClassifier().classify(
                temporaryDirectory.resolve("missing.json"), rerun, Optional.empty());

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MISSING);
    }

    @Test
    void reportsMissingFirstRunRerunFile() throws Exception {
        Path firstRunJson = write("first-run.json",
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))));

        RetryClassification result = new RetryClassifier().classify(
                firstRunJson,
                temporaryDirectory.resolve("missing-rerun.txt"),
                Optional.empty());

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MISSING);
    }

    @Test
    void rejectsUnsupportedOrUnsafeFeatureUris() throws Exception {
        for (String uri : new String[]{
                "https://example.test/features/sample.feature",
                "classpath:/features/sample.feature",
                "classpath:features/../sample.feature",
                "classpath:features//sample.feature",
                "classpath:features/bad name.feature",
                "classpath:features\\sample.feature"
        }) {
            RetryClassification result = classify(
                    featureJsonForUri(uri, scenario(SCENARIO_LINE, "scenario", "id",
                            result("passed"), result("passed"), result("passed"))),
                    "");

            assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
        }
    }

    @Test
    void rejectsMissingNonPositiveAndNonIntegralScenarioLines() throws Exception {
        for (String line : new String[]{"null", "0", "-1", "1.5"}) {
            RetryClassification result = classify(
                    featureJson(scenarioWithLine(line, "scenario", "id",
                            result("passed"), result("passed"), result("passed"))),
                    "");

            assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
        }
    }

    @Test
    void rejectsAMissingScenarioLine() throws Exception {
        String scenario = "{\"type\":\"scenario\",\"keyword\":\"Scenario\","
                + "\"name\":\"scenario\",\"id\":\"id\",\"steps\":[{\"result\":{\"status\":\"passed\"}}],"
                + "\"before\":[],\"after\":[]}";
        RetryClassification result = classify(featureJson(scenario), "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsMissingStepStatusAsEvidenceError() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "scenario", "id",
                        "{}", result("passed"), result("passed"))),
                "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
        assertEquals(RetryClassification.StatusEvidence.MISSING,
                result.scenarios().get(0).firstAttempt().statusObservations().get(0).evidence());
    }

    @Test
    void reportsAMalformedRerunEntry() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))),
                "not-a-feature-location");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsAnUnknownStatusAsAmbiguousEvidence() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "scenario", "id",
                        result("new-cucumber-status"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.AMBIGUOUS);
        assertEquals(RetryClassification.OverallOutcome.EVIDENCE_ERROR, result.outcome());
        assertEquals(Optional.of(RetryClassification.ObservedStatus.UNKNOWN),
                result.scenarios().get(0).firstAttempt().statusObservations().get(0).status());
        assertEquals(RetryClassification.AttemptStatus.AMBIGUOUS,
                result.scenarios().get(0).firstAttempt().status());
        assertEquals(RetryClassification.ScenarioOutcome.EVIDENCE_ERROR,
                result.scenarios().get(0).outcome());
    }

    @Test
    void rejectsAConsistentPassedStatusWithAnErrorMessage() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "contradictory scenario", "id",
                        result("passed", "contradictory error"),
                        result("passed"), result("passed"))),
                "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.AMBIGUOUS);
    }

    @Test
    void acceptsEmptyHookArraysWhenNoHooksRan() throws Exception {
        String scenario = "{\"type\":\"scenario\",\"keyword\":\"Scenario\","
                + "\"name\":\"scenario\",\"id\":\"id\","
                + "\"line\":" + SCENARIO_LINE + ",\"steps\":[{\"result\":{\"status\":\"passed\"}}],"
                + "\"before\":[],\"after\":[]}";
        RetryClassification result = classify(featureJson(scenario), "");

        assertEquals(RetryClassification.OverallOutcome.PASS, result.outcome());
        assertTrue(result.evidenceIssues().isEmpty());
    }

    @Test
    void rejectsAnEmptyStepArrayAsIncompleteEvidence() throws Exception {
        String scenario = "{\"type\":\"scenario\",\"name\":\"scenario\",\"id\":\"id\","
                + "\"line\":" + SCENARIO_LINE + ",\"steps\":[],\"before\":[],\"after\":[]}";
        RetryClassification result = classify(featureJson(scenario), "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsMissingHookCollectionsAsEvidenceError() throws Exception {
        String scenario = "{\"type\":\"scenario\",\"name\":\"scenario\",\"id\":\"id\","
                + "\"line\":" + SCENARIO_LINE + ",\"steps\":[{\"result\":{\"status\":\"passed\"}}],"
                + "\"before\":[]}";
        RetryClassification result = classify(featureJson(scenario), "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void doesNotClassifyScenarioOutlines() throws Exception {
        String scenario = scenario(SCENARIO_LINE, "outline", "id",
                result("passed"), result("passed"), result("passed"))
                .replace("\"keyword\":\"Scenario\"", "\"keyword\":\"Scenario Outline\"");
        RetryClassification result = classify(featureJson(scenario), "");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.AMBIGUOUS);
    }

    @Test
    void matchesUsingOnlyFeatureUriAndLine() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "name differs from rerun", "unrelated-id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry name", "different-id",
                        result("failed"), result("passed"), result("passed"))));

        assertEquals(RetryClassification.OverallOutcome.FLAKY_FAIL, result.outcome());
        assertTrue(result.evidenceIssues().isEmpty());
    }

    @Test
    void parsesSpecialCharactersAndMatchesRerunByFeatureUriAndLine() throws Exception {
        String name = "quoted \"name\", backslash \\ and\nnewline";
        String id = "quoted \"id\", backslash \\ and\nnewline";
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, name, id,
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry name", "retry id",
                        result("passed"), result("passed"), result("passed"))));

        assertEquals(RetryClassification.OverallOutcome.FLAKY_PASS, result.outcome());
        assertEquals(1, result.scenarios().size());
        assertEquals(name, result.scenarios().get(0).name());
        assertEquals(FEATURE_URI, result.scenarios().get(0).key().featureUri().toString());
        assertEquals(SCENARIO_LINE, result.scenarios().get(0).key().line());
        assertTrue(result.evidenceIssues().stream().noneMatch(issue ->
                issue.type() == RetryClassification.EvidenceIssueType.MISSING
                        || issue.type() == RetryClassification.EvidenceIssueType.UNMATCHED));
    }

    @Test
    void classifiesWithoutRetryJsonWhenNoScenarioNeedsRetry() throws Exception {
        Path firstRunJson = write("first-run.json",
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))));
        Path firstRunRerun = write("first-run-rerun.txt", "");
        RetryClassification result = new RetryClassifier().classify(
                firstRunJson, firstRunRerun, Optional.empty());

        assertEquals(RetryClassification.OverallOutcome.PASS, result.outcome());
        assertTrue(result.evidenceIssues().isEmpty());
    }

    @Test
    void rejectsUnexpectedRetryRecordsWhenNoScenarioWasRerun() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "passing scenario", "id",
                        result("passed"), result("passed"), result("passed"))),
                "",
                featureJson(scenario(SCENARIO_LINE, "unexpected retry", "id",
                        result("passed"), result("passed"), result("passed"))));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.UNMATCHED);
    }

    @Test
    void classifiesFailedThenPassingRetryAsFlakyPass() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "first attempt", "first-id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                        result("passed"), result("passed"), result("passed"))));

        assertEquals(RetryClassification.OverallOutcome.FLAKY_PASS, result.outcome());
        assertEquals(RetryClassification.ScenarioOutcome.FLAKY_PASS,
                result.scenarios().get(0).outcome());
        assertEquals(RetryClassification.AttemptStatus.FAILED,
                result.scenarios().get(0).firstAttempt().status());
        assertEquals(RetryClassification.AttemptStatus.PASSED,
                result.scenarios().get(0).retryAttempt().orElseThrow().status());
    }

    @Test
    void classifiesFailedThenFailedRetryAsFlakyFail() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "first attempt", "first-id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                        result("failed"), result("passed"), result("passed"))));

        assertEquals(RetryClassification.OverallOutcome.FLAKY_FAIL, result.outcome());
        assertEquals(RetryClassification.ScenarioOutcome.FLAKY_FAIL,
                result.scenarios().get(0).outcome());
    }

    @Test
    void requiresRetryJsonWhenTheRerunFileContainsScenarios() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MISSING);
    }

    @Test
    void reportsMalformedRetryJson() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                "{not-json");

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsMissingRetryStatus() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                        "{}", result("passed"), result("passed"))));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MALFORMED);
    }

    @Test
    void reportsMissingRetryScenarioKey() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE + 1, "different", "id",
                        result("passed"), result("passed"), result("passed"))));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.MISSING);
    }

    @Test
    void reportsExtraRetryScenarioKey() throws Exception {
        String retry = featureJson(
                scenario(SCENARIO_LINE, "retry", "id",
                        result("passed"), result("passed"), result("passed"))
                        + ","
                        + scenario(SCENARIO_LINE + 1, "extra", "extra-id",
                                result("passed"), result("passed"), result("passed")));
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                retry);

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.UNMATCHED);
    }

    @Test
    void reportsDuplicateRetryScenarioKeys() throws Exception {
        String duplicate = scenario(SCENARIO_LINE, "retry one", "one",
                result("passed"), result("passed"), result("passed"))
                + ","
                + scenario(SCENARIO_LINE, "retry two", "two",
                        result("passed"), result("passed"), result("passed"));
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(duplicate));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.DUPLICATE);
    }

    @Test
    void rejectsContradictoryRetryStatusEvidence() throws Exception {
        RetryClassification result = classify(
                featureJson(scenario(SCENARIO_LINE, "failed scenario", "id",
                        result("failed"), result("passed"), result("passed"))),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(scenario(SCENARIO_LINE, "retry", "retry-id",
                        result("passed", "unexpected error"), result("passed"), result("passed"))));

        assertEvidenceIssue(result, RetryClassification.EvidenceIssueType.AMBIGUOUS);
    }

    @Test
    void preservesRetryErrorsAndScreenshotReferencesWithoutBase64Data() throws Exception {
        String retryScenario = scenarioWithAfterEmbedding(SCENARIO_LINE, "retry", "retry",
                result("failed", "retry error"), result("passed"),
                result("passed"), "base64-image-payload");
        RetryClassification result = classify(
                featureJson(scenarioWithAfterEmbedding(SCENARIO_LINE, "first", "first",
                        result("failed", "first error"), result("passed"),
                        result("passed"), "first-base64-payload")),
                rerunEntry(FEATURE_URI, SCENARIO_LINE),
                featureJson(retryScenario));

        RetryClassification.AttemptResult first = result.scenarios().get(0).firstAttempt();
        RetryClassification.AttemptResult retry = result.scenarios().get(0).retryAttempt().orElseThrow();
        assertTrue(first.errorMessages().contains("first error"));
        assertTrue(retry.errorMessages().contains("retry error"));
        assertEquals(1, first.attachments().size());
        assertEquals(1, retry.attachments().size());
        assertEquals("image/png", first.attachments().get(0).mimeType());
        assertEquals("image/png", retry.attachments().get(0).mimeType());
        assertEquals(RetryClassification.AttachmentKind.AFTER_HOOK,
                retry.attachments().get(0).kind());
        assertTrue(!first.attachments().toString().contains("first-base64-payload"));
        assertTrue(!retry.attachments().toString().contains("base64-image-payload"));
    }

    private RetryClassification classify(String firstRunJson, String rerun) throws Exception {
        return new RetryClassifier().classify(
                write("first-run.json", firstRunJson),
                write("first-run-rerun.txt", rerun),
                Optional.empty());
    }

    private RetryClassification classify(
            String firstRunJson,
            String rerun,
            String retryJson) throws Exception {
        return new RetryClassifier().classify(
                write("first-run.json", firstRunJson),
                write("first-run-rerun.txt", rerun),
                Optional.of(write("retry.json", retryJson)));
    }

    private Path write(String fileName, String content) throws Exception {
        Path path = temporaryDirectory.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }

    private static void assertEvidenceIssue(
            RetryClassification result,
            RetryClassification.EvidenceIssueType type) {
        assertEquals(RetryClassification.OverallOutcome.EVIDENCE_ERROR, result.outcome());
        assertTrue(result.evidenceIssues().stream().anyMatch(issue -> issue.type() == type),
                "Expected evidence issue of type " + type);
    }

    private static String featureJson(String scenarioElements) {
        return featureJsonForUri(FEATURE_URI, scenarioElements);
    }

    private static String featureJsonForUri(String uri, String scenarioElements) {
        return "[{\"uri\":\"" + jsonEscape(uri) + "\",\"elements\":[" + scenarioElements + "]}]";
    }

    private static String scenario(
            int line,
            String name,
            String id,
            String stepResult,
            String beforeResult,
            String afterResult) {
        return scenarioWithLine(Integer.toString(line), name, id,
                stepResult, beforeResult, afterResult);
    }

    private static String scenarioWithAfterEmbedding(
            int line,
            String name,
            String id,
            String stepResult,
            String beforeResult,
            String afterResult,
            String imageData) {
        return "{\"type\":\"scenario\",\"keyword\":\"Scenario\",\"name\":\""
                + jsonEscape(name) + "\",\"id\":\"" + jsonEscape(id) + "\",\"line\":" + line
                + ",\"steps\":[{\"result\":" + stepResult + "}],"
                + "\"before\":[{\"result\":" + beforeResult + "}],"
                + "\"after\":[{\"result\":" + afterResult
                + ",\"embeddings\":[{\"mime_type\":\"image/png\",\"data\":\""
                + jsonEscape(imageData) + "\"}]}]}";
    }

    private static String scenarioWithLine(
            String line,
            String name,
            String id,
            String stepResult,
            String beforeResult,
            String afterResult) {
        return "{\"type\":\"scenario\",\"keyword\":\"Scenario\",\"name\":\""
                + jsonEscape(name) + "\",\"id\":\"" + jsonEscape(id) + "\",\"line\":" + line
                + ",\"steps\":[{\"result\":" + stepResult + "}],"
                + "\"before\":[{\"result\":" + beforeResult + "}],"
                + "\"after\":[{\"result\":" + afterResult + "}]}";
    }

    private static String result(String status) {
        return "{\"status\":\"" + status + "\"}";
    }

    private static String result(String status, String errorMessage) {
        return "{\"status\":\"" + status + "\",\"error_message\":\""
                + jsonEscape(errorMessage) + "\"}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(character);
                        escaped.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String rerunEntry(String uri, int line) {
        return uri + ":" + line;
    }
}
