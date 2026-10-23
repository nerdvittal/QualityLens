package com.shyam.qualitylens;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RetryClassification(
        OverallOutcome outcome,
        List<ScenarioResult> scenarios,
        List<EvidenceIssue> evidenceIssues) {

    public RetryClassification {
        Objects.requireNonNull(outcome, "outcome");
        scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        evidenceIssues = List.copyOf(Objects.requireNonNull(evidenceIssues, "evidenceIssues"));
    }

    public enum OverallOutcome {
        PASS, FLAKY_PASS, FLAKY_FAIL, INCONCLUSIVE, EVIDENCE_ERROR
    }

    public enum ScenarioOutcome {
        PASS, FLAKY_PASS, FLAKY_FAIL, INCONCLUSIVE, EVIDENCE_ERROR
    }

    public enum AttemptStatus {
        PASSED, FAILED, UNDEFINED, PENDING, SKIPPED,
        AMBIGUOUS, INCONCLUSIVE, EVIDENCE_ERROR
    }

    public enum ObservedStatus {
        PASSED, FAILED, UNDEFINED, PENDING, SKIPPED, UNKNOWN
    }

    public enum StatusEvidence {
        PRESENT, MISSING, MALFORMED
    }

    public enum EvidenceIssueType {
        MISSING, UNREADABLE, MALFORMED, DUPLICATE, UNMATCHED, AMBIGUOUS
    }

    public enum AttachmentKind {
        STEP, BEFORE_HOOK, AFTER_HOOK
    }

    public record ScenarioKey(URI featureUri, int line) {
        public ScenarioKey {
            Objects.requireNonNull(featureUri, "featureUri");
            if (!featureUri.isAbsolute()) {
                throw new IllegalArgumentException("featureUri must be absolute");
            }
            if (line < 1) {
                throw new IllegalArgumentException("line must be positive");
            }
        }
    }

    public record StatusObservation(
            StatusEvidence evidence,
            Optional<ObservedStatus> status) {
        public StatusObservation {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(status, "status");
            if ((evidence == StatusEvidence.PRESENT) != status.isPresent()) {
                throw new IllegalArgumentException(
                        "PRESENT evidence requires a status; MISSING and MALFORMED must not have one");
            }
        }
    }

    public record ScenarioResult(
            ScenarioKey key,
            String name,
            AttemptResult firstAttempt,
            Optional<AttemptResult> retryAttempt,
            ScenarioOutcome outcome) {
        public ScenarioResult {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(firstAttempt, "firstAttempt");
            Objects.requireNonNull(retryAttempt, "retryAttempt");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record AttemptResult(
            AttemptStatus status,
            List<StatusObservation> statusObservations,
            List<String> errorMessages,
            List<AttachmentReference> attachments) {
        public AttemptResult {
            Objects.requireNonNull(status, "status");
            statusObservations = List.copyOf(
                    Objects.requireNonNull(statusObservations, "statusObservations"));
            errorMessages = List.copyOf(
                    Objects.requireNonNull(errorMessages, "errorMessages"));
            attachments = List.copyOf(
                    Objects.requireNonNull(attachments, "attachments"));
        }
    }

    public record AttachmentReference(
            Path reportPath,
            String jsonPointer,
            AttachmentKind kind,
            String mimeType) {
        public AttachmentReference {
            Objects.requireNonNull(reportPath, "reportPath");
            Objects.requireNonNull(jsonPointer, "jsonPointer");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(mimeType, "mimeType");
        }
    }

    public record EvidenceIssue(
            EvidenceIssueType type,
            Optional<ScenarioKey> key,
            String message) {
        public EvidenceIssue {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(message, "message");
        }
    }
}
