package com.shyam.qualitylens;

import com.shyam.qualitylens.RetryClassification.AttemptStatus;
import com.shyam.qualitylens.RetryClassification.OverallOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryWorkflowControlledExecutionTest {

    @Test
    void retriesAndReportsTheControlledFailureInAnIsolatedDirectory() throws IOException {
        Path outputDirectory = Path.of("target", "controlled-retry");
        RetryWorkflow.WorkflowResult result = RetryWorkflow.forProject(
                outputDirectory, "classpath:controlled-failure").execute();

        assertEquals(OverallOutcome.FLAKY_FAIL, result.classification().outcome());
        var retriedScenarios = result.classification().scenarios().stream()
                .filter(scenario -> scenario.retryAttempt().isPresent())
                .toList();
        assertEquals(1, retriedScenarios.size());
        RetryClassification.ScenarioResult scenario = retriedScenarios.get(0);
        assertEquals(AttemptStatus.FAILED, scenario.firstAttempt().status());
        assertEquals(AttemptStatus.FAILED, scenario.retryAttempt().orElseThrow().status());
        assertNotEquals(0, result.firstRunExitStatus());
        assertNotEquals(0, result.retryExitStatus().orElseThrow());
        assertEquals(1, result.exitCode());

        assertTrue(Files.isRegularFile(outputDirectory.resolve("first-run.json")));
        assertTrue(Files.isRegularFile(outputDirectory.resolve("first-run-rerun.txt")));
        assertTrue(Files.isRegularFile(outputDirectory.resolve("retry.json")));
        assertTrue(Files.isRegularFile(outputDirectory.resolve("retry-rerun.txt")));
        assertTrue(Files.isRegularFile(outputDirectory.resolve("retry-classification.json")));
        assertTrue(Files.isRegularFile(outputDirectory.resolve("retry-classification.html")));
    }
}
