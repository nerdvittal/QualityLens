package com.shyam.qualitylens;

import com.shyam.qualitylens.RetryClassification.AttemptResult;
import com.shyam.qualitylens.RetryClassification.AttemptStatus;
import com.shyam.qualitylens.RetryClassification.AttachmentKind;
import com.shyam.qualitylens.RetryClassification.AttachmentReference;
import com.shyam.qualitylens.RetryClassification.EvidenceIssue;
import com.shyam.qualitylens.RetryClassification.EvidenceIssueType;
import com.shyam.qualitylens.RetryClassification.ObservedStatus;
import com.shyam.qualitylens.RetryClassification.OverallOutcome;
import com.shyam.qualitylens.RetryClassification.ScenarioKey;
import com.shyam.qualitylens.RetryClassification.ScenarioOutcome;
import com.shyam.qualitylens.RetryClassification.ScenarioResult;
import com.shyam.qualitylens.RetryClassification.StatusEvidence;
import com.shyam.qualitylens.RetryClassification.StatusObservation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.cucumber.core.feature.FeatureWithLines;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class RetryClassifier {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public RetryClassification classify(
            Path firstRunJson,
            Path firstRunRerun,
            Optional<Path> retryJson) {
        Objects.requireNonNull(firstRunJson, "firstRunJson");
        Objects.requireNonNull(firstRunRerun, "firstRunRerun");
        Objects.requireNonNull(retryJson, "retryJson");

        RunEvidence firstRun = readRun(firstRunJson);
        List<EvidenceIssue> issues = new ArrayList<>(firstRun.issues());
        List<ScenarioResult> scenarios = new ArrayList<>(firstRun.scenarios());
        Set<ScenarioKey> rerunKeys = readRerun(firstRunRerun, issues);
        if (rerunKeys == null) {
            return classification(scenarios, issues);
        }

        reconcileRerun(firstRun, rerunKeys, scenarios, issues);
        if (!issues.isEmpty()) {
            return classification(markAllIssueRelated(scenarios, issues), issues);
        }

        if (firstRun.nonPassingKeys().isEmpty()) {
            if (retryJson.isPresent()) {
                RunEvidence unexpectedRetry = readRun(retryJson.get());
                issues.addAll(unexpectedRetry.issues());
                if (!unexpectedRetry.keys().isEmpty()) {
                    issues.add(issue(EvidenceIssueType.UNMATCHED, Optional.empty(),
                            "Retry evidence exists although no first-run scenarios were rerun"));
                }
                if (!issues.isEmpty()) {
                    return classification(markAllIssueRelated(scenarios, issues), issues);
                }
            }
            return classification(withOutcome(scenarios, ScenarioOutcome.PASS),
                    List.of(), OverallOutcome.PASS);
        }

        if (retryJson.isEmpty()) {
            issues.add(issue(EvidenceIssueType.MISSING, Optional.empty(),
                    "Retry JSON is required for non-passing first-run scenarios"));
            return classification(markAllIssueRelated(scenarios, issues), issues);
        }

        RunEvidence retry = readRun(retryJson.get());
        issues.addAll(retry.issues());
        if (!retry.keys().equals(rerunKeys)) {
            Set<ScenarioKey> missing = new HashSet<>(rerunKeys);
            missing.removeAll(retry.keys());
            for (ScenarioKey key : missing) {
                issues.add(issue(EvidenceIssueType.MISSING, Optional.of(key),
                        "Rerun scenario is missing from retry JSON"));
            }
            Set<ScenarioKey> extra = new HashSet<>(retry.keys());
            extra.removeAll(rerunKeys);
            for (ScenarioKey key : extra) {
                issues.add(issue(EvidenceIssueType.UNMATCHED, Optional.of(key),
                        "Retry JSON contains a scenario not present in the rerun file"));
            }
        }
        if (!issues.isEmpty()) {
            return classification(markAllIssueRelated(scenarios, issues), issues);
        }

        List<ScenarioResult> classifiedScenarios = new ArrayList<>();
        OverallOutcome overall = null;
        for (ScenarioResult first : scenarios) {
            ScenarioResult retryResult = retry.byKey().get(first.key());
            if (retryResult == null) {
                ScenarioOutcome outcome = first.firstAttempt().status() == AttemptStatus.PASSED
                        ? ScenarioOutcome.PASS
                        : ScenarioOutcome.INCONCLUSIVE;
                classifiedScenarios.add(new ScenarioResult(
                        first.key(), first.name(), first.firstAttempt(),
                        Optional.empty(), outcome));
                overall = combine(overall, outcome);
                continue;
            }
            ScenarioOutcome outcome = classifyPair(first.firstAttempt(), retryResult.firstAttempt());
            ScenarioResult combined = new ScenarioResult(
                    first.key(), first.name(), first.firstAttempt(),
                    Optional.of(retryResult.firstAttempt()), outcome);
            classifiedScenarios.add(combined);
            overall = combine(overall, outcome);
        }
        if (overall == null) {
            overall = OverallOutcome.INCONCLUSIVE;
        }
        return classification(classifiedScenarios, List.of(), overall);
    }

    private static JsonNode readJson(Path path, List<EvidenceIssue> issues) {
        final String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exception) {
            issues.add(issue(EvidenceIssueType.MISSING, Optional.empty(),
                    "First-run JSON file does not exist: " + path));
            return null;
        } catch (IOException | SecurityException exception) {
            issues.add(issue(EvidenceIssueType.UNREADABLE, Optional.empty(),
                    "Could not read first-run JSON file: " + path));
            return null;
        }

        try {
            JsonNode root = JSON_MAPPER.readTree(json);
            if (root == null) {
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                        "First-run JSON contains no JSON value: " + path));
            }
            return root;
        } catch (JacksonException exception) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "First-run JSON is malformed: " + path));
            return null;
        }
    }

    private static RunEvidence readRun(Path path) {
        List<EvidenceIssue> issues = new ArrayList<>();
        List<ScenarioResult> scenarios = new ArrayList<>();
        Set<ScenarioKey> keys = new HashSet<>();
        Set<ScenarioKey> nonPassingKeys = new HashSet<>();
        JsonNode root = readJson(path, issues);
        if (root == null) {
            return new RunEvidence(scenarios, Map.of(), keys, nonPassingKeys, issues);
        }
        if (!root.isArray() || root.size() == 0) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Cucumber JSON must be a non-empty top-level feature array: " + path));
            return new RunEvidence(scenarios, Map.of(), keys, nonPassingKeys, issues);
        }

        for (int featureIndex = 0; featureIndex < root.size(); featureIndex++) {
            JsonNode feature = root.get(featureIndex);
            String featurePointer = "/" + featureIndex;
            if (!feature.isObject()) {
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                        "Feature entry at " + featurePointer + " must be an object"));
                continue;
            }
            URI featureUri = readFeatureUri(feature.get("uri"), featurePointer, issues);
            JsonNode elements = feature.get("elements");
            if (elements == null || !elements.isArray() || elements.size() == 0) {
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                        "Feature elements at " + featurePointer + "/elements must be a non-empty array"));
                continue;
            }
            if (featureUri == null) {
                continue;
            }

            for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
                JsonNode element = elements.get(elementIndex);
                String pointer = featurePointer + "/elements/" + elementIndex;
                if (!element.isObject()) {
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                            "Scenario element at " + pointer + " must be an object"));
                    continue;
                }
                JsonNode type = element.get("type");
                JsonNode keyword = element.get("keyword");
                if (type == null || !type.isTextual()
                        || !"scenario".equals(type.asText())
                        || keyword == null || !keyword.isTextual()
                        || keyword.asText().toLowerCase(Locale.ROOT).contains("outline")) {
                    issues.add(issue(EvidenceIssueType.AMBIGUOUS, Optional.empty(),
                            "Unsupported or ambiguous scenario element at " + pointer));
                    continue;
                }

                ScenarioKey key = readScenarioKey(featureUri, element.get("line"), pointer, issues);
                if (key == null) {
                    continue;
                }
                String name = readScenarioName(element.get("name"), key, pointer, issues);
                boolean[] malformed = {
                        element.get("name") == null || !element.get("name").isTextual()
                };
                boolean[] ambiguous = {false};
                List<StatusObservation> observations = new ArrayList<>();
                List<String> errors = new ArrayList<>();
                List<AttachmentReference> attachments = new ArrayList<>();
                readResultCollection(element.get("steps"), "steps", pointer, key, path,
                        observations, errors, attachments, issues, malformed, ambiguous, true);
                readResultCollection(element.get("before"), "before", pointer, key, path,
                        observations, errors, attachments, issues, malformed, ambiguous, false);
                readResultCollection(element.get("after"), "after", pointer, key, path,
                        observations, errors, attachments, issues, malformed, ambiguous, false);

                AttemptStatus status = aggregateStatus(observations, malformed[0], ambiguous[0]);
                if (malformed[0]) {
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                            "Scenario has incomplete or invalid step/hook result evidence"));
                }
                ScenarioOutcome scenarioOutcome = switch (status) {
                    case EVIDENCE_ERROR, AMBIGUOUS -> ScenarioOutcome.EVIDENCE_ERROR;
                    case PASSED, FAILED, UNDEFINED, PENDING, SKIPPED, INCONCLUSIVE ->
                            ScenarioOutcome.INCONCLUSIVE;
                };
                ScenarioResult scenario = new ScenarioResult(
                        key, name, new AttemptResult(status, observations, errors, attachments),
                        Optional.empty(), scenarioOutcome);
                if (!keys.add(key)) {
                    issues.add(issue(EvidenceIssueType.DUPLICATE, Optional.of(key),
                            "Duplicate scenario key in Cucumber JSON: " + key));
                    for (int index = 0; index < scenarios.size(); index++) {
                        if (scenarios.get(index).key().equals(key)) {
                            scenarios.set(index, withEvidenceError(scenarios.get(index)));
                        }
                    }
                    scenario = withEvidenceError(scenario);
                } else if (status != AttemptStatus.PASSED) {
                    nonPassingKeys.add(key);
                }
                scenarios.add(scenario);
            }
        }
        if (keys.isEmpty()) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Cucumber JSON contains no supported ordinary scenarios: " + path));
        }
        Map<ScenarioKey, ScenarioResult> byKey = new java.util.HashMap<>();
        for (ScenarioResult scenario : scenarios) {
            byKey.putIfAbsent(scenario.key(), scenario);
        }
        return new RunEvidence(scenarios, byKey, keys, nonPassingKeys, issues);
    }

    private static URI readFeatureUri(
            JsonNode uriNode,
            String pointer,
            List<EvidenceIssue> issues) {
        if (uriNode == null || !uriNode.isTextual()) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Feature URI at " + pointer + "/uri must be a string"));
            return null;
        }
        try {
            return validatedClasspathUri(uriNode.asText());
        } catch (IllegalArgumentException exception) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Invalid or unsupported feature URI at " + pointer + "/uri"));
            return null;
        }
    }

    private static ScenarioKey readScenarioKey(
            URI featureUri,
            JsonNode lineNode,
            String pointer,
            List<EvidenceIssue> issues) {
        if (lineNode == null || !lineNode.isIntegralNumber() || !lineNode.canConvertToInt()) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Scenario line at " + pointer + "/line must be a positive integer"));
            return null;
        }
        int line = lineNode.intValue();
        if (line < 1) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                    "Scenario line at " + pointer + "/line must be positive"));
            return null;
        }
        return new ScenarioKey(featureUri, line);
    }

    private static String readScenarioName(
            JsonNode nameNode,
            ScenarioKey key,
            String pointer,
            List<EvidenceIssue> issues) {
        if (nameNode == null || !nameNode.isTextual()) {
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                    "Scenario name at " + pointer + "/name must be a string"));
            return "";
        }
        return nameNode.asText();
    }

    private static void readResultCollection(
            JsonNode collection,
            String field,
            String scenarioPointer,
            ScenarioKey key,
            Path reportPath,
            List<StatusObservation> observations,
            List<String> errors,
            List<AttachmentReference> attachments,
            List<EvidenceIssue> issues,
            boolean[] hasEvidenceError,
            boolean[] hasAmbiguousEvidence,
            boolean requireNonEmpty) {
        if (collection == null || !collection.isArray()
                || (requireNonEmpty && collection.isEmpty())) {
            hasEvidenceError[0] = true;
            issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                    "Scenario " + field + " at " + scenarioPointer + "/" + field
                            + (requireNonEmpty ? " must be a non-empty array" : " must be an array")));
            return;
        }

        for (int index = 0; index < collection.size(); index++) {
            JsonNode item = collection.get(index);
            String resultPointer = scenarioPointer + "/" + field + "/" + index + "/result";
            if (!item.isObject()) {
                hasEvidenceError[0] = true;
                observations.add(new StatusObservation(StatusEvidence.MALFORMED, Optional.empty()));
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                        "Result entry at " + scenarioPointer + "/" + field + "/" + index
                                + " must be an object"));
                continue;
            }
            JsonNode result = item.get("result");
            if (result == null || !result.isObject()) {
                hasEvidenceError[0] = true;
                observations.add(new StatusObservation(StatusEvidence.MALFORMED, Optional.empty()));
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                        "Result at " + resultPointer + " must be an object"));
                continue;
            }

            JsonNode statusNode = result.get("status");
            boolean passed = false;
            if (statusNode == null) {
                hasEvidenceError[0] = true;
                observations.add(new StatusObservation(StatusEvidence.MISSING, Optional.empty()));
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                        "Status is missing at " + resultPointer + "/status"));
            } else if (!statusNode.isTextual()) {
                hasEvidenceError[0] = true;
                observations.add(new StatusObservation(StatusEvidence.MALFORMED, Optional.empty()));
                issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                        "Status at " + resultPointer + "/status must be a string"));
            } else {
                String rawStatus = statusNode.asText();
                Optional<ObservedStatus> status = observedStatus(rawStatus);
                passed = status.orElse(ObservedStatus.UNKNOWN) == ObservedStatus.PASSED;
                observations.add(new StatusObservation(StatusEvidence.PRESENT,
                        Optional.of(status.orElse(ObservedStatus.UNKNOWN))));
                if (status.isEmpty()) {
                    hasAmbiguousEvidence[0] = true;
                    issues.add(issue(EvidenceIssueType.AMBIGUOUS, Optional.of(key),
                            "Unrecognized Cucumber status '" + rawStatus + "' at "
                                    + resultPointer + "/status"));
                }
            }

            JsonNode errorMessage = result.get("error_message");
            if (errorMessage != null && !errorMessage.isNull()) {
                if (errorMessage.isTextual()) {
                    errors.add(errorMessage.asText());
                    if (passed && !errorMessage.asText().isBlank()) {
                        hasAmbiguousEvidence[0] = true;
                        issues.add(issue(EvidenceIssueType.AMBIGUOUS, Optional.of(key),
                                "Passed result contains a non-empty error message at "
                                        + resultPointer + "/error_message"));
                    }
                } else {
                    hasEvidenceError[0] = true;
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                            "Error message at " + resultPointer + "/error_message must be a string"));
                }
            }
            JsonNode embeddings = item.get("embeddings");
            if (embeddings != null) {
                if (!embeddings.isArray()) {
                    hasEvidenceError[0] = true;
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                            "Embeddings at " + scenarioPointer + "/" + field + "/" + index
                                    + "/embeddings must be an array"));
                    continue;
                }
                for (int embeddingIndex = 0; embeddingIndex < embeddings.size(); embeddingIndex++) {
                    JsonNode embedding = embeddings.get(embeddingIndex);
                    String embeddingPointer = scenarioPointer + "/" + field + "/" + index
                            + "/embeddings/" + embeddingIndex;
                    if (!embedding.isObject()) {
                        hasEvidenceError[0] = true;
                        issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                                "Attachment embedding at " + embeddingPointer + " must be an object"));
                        continue;
                    }
                    JsonNode mimeType = embedding.get("mime_type");
                    JsonNode data = embedding.get("data");
                    if (mimeType == null || !mimeType.isTextual()
                            || data == null || !data.isTextual() || data.asText().isEmpty()) {
                        hasEvidenceError[0] = true;
                        issues.add(issue(EvidenceIssueType.MALFORMED, Optional.of(key),
                                "Attachment embedding at " + embeddingPointer
                                        + " requires string mime_type and data fields"));
                        continue;
                    }
                    if (mimeType.asText().startsWith("image/")) {
                        AttachmentKind kind = switch (field) {
                            case "steps" -> AttachmentKind.STEP;
                            case "before" -> AttachmentKind.BEFORE_HOOK;
                            case "after" -> AttachmentKind.AFTER_HOOK;
                            default -> throw new IllegalArgumentException("Unexpected result field");
                        };
                        attachments.add(new AttachmentReference(
                                reportPath, embeddingPointer, kind, mimeType.asText()));
                    }
                }
            }
        }
    }

    private static Optional<ObservedStatus> observedStatus(String status) {
        return switch (status) {
            case "passed" -> Optional.of(ObservedStatus.PASSED);
            case "failed" -> Optional.of(ObservedStatus.FAILED);
            case "undefined" -> Optional.of(ObservedStatus.UNDEFINED);
            case "pending" -> Optional.of(ObservedStatus.PENDING);
            case "skipped" -> Optional.of(ObservedStatus.SKIPPED);
            default -> Optional.empty();
        };
    }

    private static AttemptStatus aggregateStatus(
            List<StatusObservation> observations,
            boolean hasEvidenceError,
            boolean hasAmbiguousEvidence) {
        if (hasEvidenceError) {
            return AttemptStatus.EVIDENCE_ERROR;
        }
        if (hasAmbiguousEvidence || observations.stream().anyMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.UNKNOWN)) {
            return AttemptStatus.AMBIGUOUS;
        }
        if (observations.stream().anyMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.FAILED)) {
            return AttemptStatus.FAILED;
        }
        if (observations.stream().anyMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.UNDEFINED)) {
            return AttemptStatus.UNDEFINED;
        }
        if (observations.stream().anyMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.PENDING)) {
            return AttemptStatus.PENDING;
        }
        if (observations.stream().anyMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.SKIPPED)) {
            return AttemptStatus.SKIPPED;
        }
        if (!observations.isEmpty() && observations.stream().allMatch(observation ->
                observation.status().orElse(ObservedStatus.UNKNOWN) == ObservedStatus.PASSED)) {
            return AttemptStatus.PASSED;
        }
        return AttemptStatus.EVIDENCE_ERROR;
    }

    private static Set<ScenarioKey> readRerun(Path path, List<EvidenceIssue> issues) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exception) {
            issues.add(issue(EvidenceIssueType.MISSING, Optional.empty(),
                    "First-run rerun file does not exist: " + path));
            return null;
        } catch (IOException | SecurityException exception) {
            issues.add(issue(EvidenceIssueType.UNREADABLE, Optional.empty(),
                    "Could not read first-run rerun file: " + path));
            return null;
        }

        Set<ScenarioKey> keys = new HashSet<>();
        for (String line : lines) {
            for (String entry : line.trim().split("\\s+")) {
                if (entry.isEmpty()) {
                    continue;
                }
                final FeatureWithLines location;
                try {
                    location = FeatureWithLines.parse(entry);
                } catch (RuntimeException exception) {
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                            "Malformed rerun entry: " + entry));
                    continue;
                }

                URI uri;
                try {
                    uri = validatedClasspathUri(location.uri().toString());
                } catch (IllegalArgumentException exception) {
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                            "Invalid or unsupported URI in rerun entry: " + entry));
                    continue;
                }
                if (location.lines().isEmpty()) {
                    issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                            "Rerun entry has no line numbers: " + entry));
                    continue;
                }
                for (int lineNumber : location.lines()) {
                    if (lineNumber < 1) {
                        issues.add(issue(EvidenceIssueType.MALFORMED, Optional.empty(),
                                "Rerun entry has a non-positive line number: " + entry));
                        continue;
                    }
                    ScenarioKey key = new ScenarioKey(uri, lineNumber);
                    if (!keys.add(key)) {
                        issues.add(issue(EvidenceIssueType.DUPLICATE, Optional.of(key),
                                "Duplicate rerun scenario key " + key));
                    }
                }
            }
        }
        return keys;
    }

    private static URI validatedClasspathUri(String value) {
        URI uri = URI.create(value);
        if (!uri.isAbsolute()
                || !uri.isOpaque()
                || !"classpath".equals(uri.getScheme())
                || uri.getRawFragment() != null
                || uri.getRawQuery() != null) {
            throw new IllegalArgumentException("Unsupported classpath URI");
        }
        String resourcePath = uri.getRawSchemeSpecificPart();
        if (resourcePath == null
                || resourcePath.isEmpty()
                || resourcePath.startsWith("/")
                || resourcePath.contains("\\")
                || resourcePath.contains("?")
                || resourcePath.contains("#")) {
            throw new IllegalArgumentException("Invalid classpath resource path");
        }
        if (!resourcePath.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Unsupported classpath resource path characters");
        }
        for (String segment : resourcePath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid classpath resource path segment");
            }
        }
        return uri;
    }

    private static void markEvidenceError(
            List<ScenarioResult> scenarios,
            ScenarioKey key) {
        for (int index = 0; index < scenarios.size(); index++) {
            if (scenarios.get(index).key().equals(key)) {
                scenarios.set(index, withEvidenceError(scenarios.get(index)));
            }
        }
    }

    private static ScenarioResult withEvidenceError(ScenarioResult result) {
        return new ScenarioResult(
                result.key(),
                result.name(),
                result.firstAttempt(),
                result.retryAttempt(),
                ScenarioOutcome.EVIDENCE_ERROR);
    }

    private static RetryClassification classification(
            List<ScenarioResult> scenarios,
            List<EvidenceIssue> issues) {
        return classification(scenarios, issues,
                issues.isEmpty() ? OverallOutcome.INCONCLUSIVE : OverallOutcome.EVIDENCE_ERROR);
    }

    private static RetryClassification classification(
            List<ScenarioResult> scenarios,
            List<EvidenceIssue> issues,
            OverallOutcome outcome) {
        List<ScenarioResult> results = scenarios.stream()
                .sorted((left, right) -> {
                    int uriComparison = left.key().featureUri().toString()
                            .compareTo(right.key().featureUri().toString());
                    return uriComparison != 0
                            ? uriComparison
                            : Integer.compare(left.key().line(), right.key().line());
                })
                .toList();
        return new RetryClassification(
                outcome, results, issues);
    }

    private static void reconcileRerun(
            RunEvidence firstRun,
            Set<ScenarioKey> rerunKeys,
            List<ScenarioResult> scenarios,
            List<EvidenceIssue> issues) {
        for (ScenarioKey key : firstRun.nonPassingKeys()) {
            if (!rerunKeys.contains(key)) {
                issues.add(issue(EvidenceIssueType.MISSING, Optional.of(key),
                        "Non-passing first-run scenario is absent from the rerun file"));
                markEvidenceError(scenarios, key);
            }
        }
        for (ScenarioKey key : rerunKeys) {
            if (!firstRun.keys().contains(key)) {
                issues.add(issue(EvidenceIssueType.UNMATCHED, Optional.of(key),
                        "Rerun entry has no matching first-run scenario"));
            } else if (!firstRun.nonPassingKeys().contains(key)) {
                issues.add(issue(EvidenceIssueType.UNMATCHED, Optional.of(key),
                        "Rerun entry refers to a passing first-run scenario"));
                markEvidenceError(scenarios, key);
            }
        }
    }

    private static List<ScenarioResult> markAllIssueRelated(
            List<ScenarioResult> scenarios,
            List<EvidenceIssue> issues) {
        Set<ScenarioKey> affected = new HashSet<>();
        for (EvidenceIssue issue : issues) {
            issue.key().ifPresent(affected::add);
        }
        List<ScenarioResult> marked = new ArrayList<>(scenarios);
        for (ScenarioKey key : affected) {
            markEvidenceError(marked, key);
        }
        return marked;
    }

    private static List<ScenarioResult> withOutcome(
            List<ScenarioResult> scenarios,
            ScenarioOutcome outcome) {
        return scenarios.stream()
                .map(scenario -> new ScenarioResult(
                        scenario.key(), scenario.name(), scenario.firstAttempt(),
                        scenario.retryAttempt(), outcome))
                .toList();
    }

    private static ScenarioOutcome classifyPair(
            AttemptResult first,
            AttemptResult retry) {
        if (first.status() == AttemptStatus.FAILED && retry.status() == AttemptStatus.PASSED) {
            return ScenarioOutcome.FLAKY_PASS;
        }
        if (first.status() == AttemptStatus.FAILED && retry.status() == AttemptStatus.FAILED) {
            return ScenarioOutcome.FLAKY_FAIL;
        }
        return ScenarioOutcome.INCONCLUSIVE;
    }

    private static OverallOutcome combine(
            OverallOutcome current,
            ScenarioOutcome next) {
        if (next == ScenarioOutcome.EVIDENCE_ERROR) {
            return OverallOutcome.EVIDENCE_ERROR;
        }
        if (current == null) {
            return switch (next) {
                case PASS -> OverallOutcome.PASS;
                case FLAKY_PASS -> OverallOutcome.FLAKY_PASS;
                case FLAKY_FAIL -> OverallOutcome.FLAKY_FAIL;
                default -> OverallOutcome.INCONCLUSIVE;
            };
        }
        if (current == OverallOutcome.PASS) {
            return switch (next) {
                case PASS -> OverallOutcome.PASS;
                case FLAKY_PASS -> OverallOutcome.FLAKY_PASS;
                case FLAKY_FAIL -> OverallOutcome.FLAKY_FAIL;
                default -> OverallOutcome.INCONCLUSIVE;
            };
        }
        if (current == OverallOutcome.EVIDENCE_ERROR) {
            return current;
        }
        if (next == ScenarioOutcome.FLAKY_FAIL || current == OverallOutcome.FLAKY_FAIL) {
            return OverallOutcome.FLAKY_FAIL;
        }
        if (next == ScenarioOutcome.INCONCLUSIVE || current == OverallOutcome.INCONCLUSIVE) {
            return OverallOutcome.INCONCLUSIVE;
        }
        if (next == ScenarioOutcome.PASS) {
            return current;
        }
        return OverallOutcome.FLAKY_PASS;
    }

    private static EvidenceIssue issue(
            EvidenceIssueType type,
            Optional<ScenarioKey> key,
            String message) {
        return new EvidenceIssue(type, key, message);
    }

    private record RunEvidence(
            List<ScenarioResult> scenarios,
            Map<ScenarioKey, ScenarioResult> byKey,
            Set<ScenarioKey> keys,
            Set<ScenarioKey> nonPassingKeys,
            List<EvidenceIssue> issues) {
        private RunEvidence {
            scenarios = List.copyOf(scenarios);
            byKey = Map.copyOf(byKey);
            keys = Set.copyOf(keys);
            nonPassingKeys = Set.copyOf(nonPassingKeys);
            issues = List.copyOf(issues);
        }
    }
}
