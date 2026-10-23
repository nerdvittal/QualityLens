package com.shyam.qualitylens;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryWorkflowExecutionTest {

    @Test
    void runsTheCucumberSuiteThroughTheIntegratedWorkflow() throws IOException {
        RetryWorkflow.WorkflowResult result = RetryWorkflow.forProject(Path.of("target")).execute();

        assertEquals(0, result.exitCode(),
                () -> "Retry workflow outcome: " + result.classification().outcome()
                        + "; evidence issues: " + result.classification().evidenceIssues());
    }
}
