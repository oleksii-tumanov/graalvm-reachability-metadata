/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.graalvm.internal.tck.utils.CoordinateUtils;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.jetbrains.annotations.NotNull;
import org.gradle.util.internal.VersionNumber;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Single task that:
 * 1) Resolves a set of index.json files based on selected coordinates (supports fractional batching k/n)
 * 2) Maps each file to its corresponding JSON Schema
 * 3) Validates the JSON well-formedness and schema compliance
 * 4) Collects and reports all validation failures at the end of execution
 * <p>
 * Coordinates can be provided via:
 * - -Pcoordinates=<filter> (preferred, supports space-separated lists for CI)
 * - --coordinates=<filter>
 * The filter can be <code>group:artifact[:version]</code>, a fractional batch <code>k/n</code> (e.g., 1/16), or 'all'.
 */
public abstract class ValidateIndexFilesTask extends CoordinatesAwareTask {

    private static final Pattern METADATA_PATTERN = Pattern.compile("metadata/[^/]+/[^/]+/index\\.json");

    @Input
    @Optional
    public abstract Property<@NotNull String> getCoordinates();

    @Option(option = "coordinates", description = "Coordinate filter (group[:artifact[:version]] or k/n fractional batch)")
    public void setCoordinatesOption(String value) {
        getCoordinates().set(value);
    }

    /**
     * Determines the effective filter string by checking the CLI option first,
     * then fallback to the project property.
     */
    protected String effectiveCoordinateFilter() {
        String opt = getCoordinates().getOrNull();
        if (opt != null) {
            return opt;
        }
        Object prop = getProject().findProperty("coordinates");
        return prop == null ? "" : prop.toString();
    }

    @TaskAction
    public void validate() {
        Set<String> targetFiles = new LinkedHashSet<>();
        List<String> override = getCoordinatesOverride().getOrElse(Collections.emptyList());

        if (!override.isEmpty()) {
            override.stream()
                    .filter(coord -> !coord.startsWith("samples:"))
                    .map(this::toArtifactIndexPath)
                    .forEach(targetFiles::add);
        } else {
            String filter = effectiveCoordinateFilter();
            if (filter.isBlank()) {
                filter = "all";
            }
            // Split by whitespace to support lists passed from GitHub Actions/CLI
            for (String singleFilter : filter.split("\\s+")) {
                if (!singleFilter.isEmpty()) {
                    targetFiles.addAll(resolveTargetFiles(singleFilter));
                }
            }
        }

        executeValidation(targetFiles);
    }

    private Set<String> resolveTargetFiles(String filter) {
        if (CoordinateUtils.isFractionalBatch(filter)) {
            int[] frac = CoordinateUtils.parseFraction(filter);
            List<String> allFiles = new ArrayList<>(listMatchingIndexFiles(null, null));
            return new LinkedHashSet<>(CoordinateUtils.computeBatchedCoordinates(allFiles, frac[0], frac[1]));
        }

        String[] parts = filter.split(":", -1);
        String group = normalizeFilterPart(parts, 0);
        String artifact = normalizeFilterPart(parts, 1);
        return listMatchingIndexFiles(group, artifact);
    }

    private String normalizeFilterPart(String[] parts, int index) {
        if (parts.length <= index) {
            return null;
        }
        String value = parts[index].trim();
        if (value.isEmpty() || "all".equals(value)) {
            return null;
        }
        return value;
    }

    private Set<String> listMatchingIndexFiles(String groupFilter, String artifactFilter) {
        Set<String> matches = new LinkedHashSet<>();
        Path metadataRoot = getProject().getProjectDir().toPath().resolve("metadata");
        if (!Files.isDirectory(metadataRoot)) {
            return matches;
        }

        try (var groups = Files.list(metadataRoot)) {
            groups.filter(Files::isDirectory).forEach(groupDir -> {
                String group = groupDir.getFileName().toString();
                if (groupFilter != null && !groupFilter.equals(group)) {
                    return;
                }
                try (var artifacts = Files.list(groupDir)) {
                    artifacts.filter(Files::isDirectory).forEach(artifactDir -> {
                        String artifact = artifactDir.getFileName().toString();
                        if (artifactFilter != null && !artifactFilter.equals(artifact)) {
                            return;
                        }
                        Path indexFile = artifactDir.resolve("index.json");
                        if (Files.isRegularFile(indexFile)) {
                            matches.add("metadata/" + group + "/" + artifact + "/index.json");
                        }
                    });
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        return matches;
    }

    private String toArtifactIndexPath(String coordinates) {
        String[] parts = coordinates.split(":", -1);
        if (parts.length < 2) {
            throw new GradleException("Invalid coordinates: " + coordinates);
        }
        return String.format("metadata/%s/%s/index.json", parts[0], parts[1]);
    }

    private void executeValidation(Set<String> targetFiles) {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        Map<String, JsonSchema> schemaCache = new HashMap<>();
        List<String> failures = new ArrayList<>();

        for (String filePath : targetFiles) {
            File jsonFile = getProject().file(filePath.replace('\\', '/'));

            if (!jsonFile.exists()) {
                getLogger().warn("⚠️ File not found: " + filePath);
                continue;
            }

            String schemaPath = mapToSchemaPath(filePath);
            if (schemaPath.isEmpty()) continue;

            try {
                JsonSchema schema = schemaCache.computeIfAbsent(schemaPath, path ->
                        factory.getSchema(getProject().file(path).toURI())
                );

                JsonNode json = mapper.readTree(jsonFile);
                int beforeFailures = failures.size();

                // Schema validation
                Set<ValidationMessage> errors = schema.validate(json);
                if (!errors.isEmpty()) {
                    for (ValidationMessage err : errors) {
                        failures.add("❌ " + filePath + ": " + err.getMessage());
                    }
                }

                // Additional semantic validations for library metadata index files
                if (METADATA_PATTERN.matcher(filePath).matches()) {
                    failures.addAll(validateLibraryIndexSemantics(
                            json,
                            jsonFile.getParentFile().toPath(),
                            getProject().getProjectDir().toPath().resolve("metadata"),
                            getProject().getProjectDir().toPath().resolve("tests/src"),
                            filePath
                    ));
                }

                // Print success only if no new failures were added by schema or semantic checks
                if (failures.size() == beforeFailures) {
                    getLogger().lifecycle("✅ " + filePath + ": Valid");
                }
            } catch (Exception e) {
                failures.add("💥 " + filePath + ": Parse Error (" + e.getMessage() + ")");
            }
        }

        if (!failures.isEmpty()) {
            failures.forEach(f -> getLogger().error(f));
            throw new GradleException("Validation failed for " + failures.size() + " file(s).");
        }
    }

    static List<String> validateLibraryIndexSemantics(
            JsonNode json,
            Path artifactDir,
            Path metadataRoot,
            Path testRoot,
            String filePath
    ) {
        List<String> failures = new ArrayList<>();
        if (json == null || !json.isArray()) {
            return failures;
        }

        checkLatestEntryCardinality(json, filePath, failures);
        checkMetadataVersionUniqueness(json, filePath, failures);
        checkMetadataVersionDirectories(json, artifactDir, filePath, failures);
        checkTestVersionDirectories(json, artifactDir, testRoot, filePath, failures);
        Map<String, Pattern> defaultForPatterns = compileDefaultForPatterns(json, filePath, failures);
        checkDefaultForOverlap(json, defaultForPatterns, filePath, failures);
        checkRequiresTargets(json, metadataRoot, filePath, failures);
        checkLibraryIndexTestedVersions(json, filePath, failures);
        return failures;
    }

    private static void checkLatestEntryCardinality(JsonNode json, String filePath, List<String> failures) {
        int latestEntries = 0;
        for (JsonNode entry : json) {
            if (entry.path("latest").isBoolean() && entry.get("latest").asBoolean()) {
                latestEntries++;
            }
        }
        if (latestEntries != 1) {
            failures.add("❌ " + filePath + ": expected exactly one entry with latest=true but found " + latestEntries);
        }
    }

    private static void checkMetadataVersionUniqueness(JsonNode json, String filePath, List<String> failures) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (JsonNode entry : json) {
            JsonNode metadataVersion = entry.get("metadata-version");
            if (metadataVersion != null && metadataVersion.isTextual()) {
                seen.merge(metadataVersion.asText(), 1, Integer::sum);
            }
        }
        seen.forEach((metadataVersion, count) -> {
            if (count > 1) {
                failures.add("❌ " + filePath + ": metadata-version '" + metadataVersion + "' is declared " + count + " times");
            }
        });
    }

    private static void checkMetadataVersionDirectories(JsonNode json, Path artifactDir, String filePath, List<String> failures) {
        for (JsonNode entry : json) {
            JsonNode metadataVersion = entry.get("metadata-version");
            if (metadataVersion == null || !metadataVersion.isTextual()) {
                continue;
            }
            Path metadataDir = artifactDir.resolve(metadataVersion.asText());
            boolean overrideOnly = entry.path("override").isBoolean() && entry.get("override").asBoolean();
            if (!Files.isDirectory(metadataDir) && !overrideOnly) {
                failures.add("❌ " + filePath + ": metadata-version '" + metadataVersion.asText()
                        + "' points to missing directory " + metadataDir.toString().replace('\\', '/'));
            }
        }
    }

    private static void checkTestVersionDirectories(JsonNode json, Path artifactDir, Path testRoot, String filePath, List<String> failures) {
        Path groupDir = artifactDir.getParent();
        if (groupDir == null) {
            return;
        }
        String group = groupDir.getFileName().toString();
        String artifact = artifactDir.getFileName().toString();
        for (JsonNode entry : json) {
            JsonNode testVersion = entry.get("test-version");
            if (testVersion == null || !testVersion.isTextual()) {
                continue;
            }
            Path testDir = testRoot.resolve(group).resolve(artifact).resolve(testVersion.asText());
            if (!Files.isDirectory(testDir)) {
                failures.add("❌ " + filePath + ": test-version '" + testVersion.asText()
                        + "' points to missing directory " + testDir.toString().replace('\\', '/'));
            }
        }
    }

    private static Map<String, Pattern> compileDefaultForPatterns(JsonNode json, String filePath, List<String> failures) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (JsonNode entry : json) {
            JsonNode metadataVersion = entry.get("metadata-version");
            JsonNode defaultFor = entry.get("default-for");
            if (metadataVersion == null || !metadataVersion.isTextual() || defaultFor == null || !defaultFor.isTextual()) {
                continue;
            }
            try {
                patterns.put(metadataVersion.asText(), Pattern.compile(defaultFor.asText()));
            } catch (PatternSyntaxException ex) {
                failures.add("❌ " + filePath + ": default-for for metadata-version '" + metadataVersion.asText()
                        + "' is not a valid regex: " + ex.getMessage());
            }
        }
        return patterns;
    }

    private static void checkDefaultForOverlap(
            JsonNode json,
            Map<String, Pattern> defaultForPatterns,
            String filePath,
            List<String> failures
    ) {
        if (defaultForPatterns.isEmpty()) {
            return;
        }
        for (JsonNode entry : json) {
            JsonNode testedVersions = entry.get("tested-versions");
            if (testedVersions == null || !testedVersions.isArray()) {
                continue;
            }
            for (JsonNode testedVersion : testedVersions) {
                if (testedVersion == null || !testedVersion.isTextual()) {
                    continue;
                }
                List<String> matches = new ArrayList<>();
                for (Map.Entry<String, Pattern> patternEntry : defaultForPatterns.entrySet()) {
                    if (patternEntry.getValue().matcher(testedVersion.asText()).matches()) {
                        matches.add(patternEntry.getKey());
                    }
                }
                if (matches.size() > 1) {
                    failures.add("❌ " + filePath + ": tested-version '" + testedVersion.asText()
                            + "' matches multiple default-for entries " + matches);
                }
            }
        }
    }

    private static void checkRequiresTargets(JsonNode json, Path metadataRoot, String filePath, List<String> failures) {
        for (JsonNode entry : json) {
            JsonNode requires = entry.get("requires");
            if (requires == null || !requires.isArray()) {
                continue;
            }
            for (JsonNode requiredModule : requires) {
                if (requiredModule == null || !requiredModule.isTextual()) {
                    continue;
                }
                String[] parts = requiredModule.asText().split(":", -1);
                if (parts.length != 2) {
                    continue;
                }
                Path targetIndex = metadataRoot.resolve(parts[0]).resolve(parts[1]).resolve("index.json");
                if (!Files.isRegularFile(targetIndex)) {
                    failures.add("❌ " + filePath + ": requires target '" + requiredModule.asText()
                            + "' is missing index " + targetIndex.toString().replace('\\', '/'));
                }
            }
        }
    }

    /**
     * Ensures "tested-versions" are mapped to the most appropriate metadata entry.
     * <p>
     * Rule: A version in {@code tested-versions} must be strictly less than the
     * next higher {@code metadata-version} available in the file.
     * <p>
     * This prevents "stray" versions from being associated with obsolete metadata
     * when a more recent metadata entry exists.
     *
     * @param json     The JSON array of index entries.
     * @param filePath Path for error reporting.
     * @param failures Accumulator for validation errors.
     */
    private static void checkLibraryIndexTestedVersions(JsonNode json, String filePath, List<String> failures) {
        if (json == null || !json.isArray()) {
            return;
        }

        // Collect unique metadata-version strings
        java.util.Set<String> metaStrings = new java.util.LinkedHashSet<>();
        for (JsonNode entry : json) {
            JsonNode mv = entry.get("metadata-version");
            if (mv != null && mv.isTextual()) {
                metaStrings.add(mv.asText());
            }
        }
        if (metaStrings.isEmpty()) {
            return;
        }

        // Parse and sort metadata versions
        List<VersionNumber> metasSorted = new ArrayList<>();
        for (String s : metaStrings) {
            try {
                metasSorted.add(VersionNumber.parse(s));
            } catch (Exception ignore) {
                // Ignore unparsable versions; schema should handle invalid shapes
            }
        }
        if (metasSorted.isEmpty()) {
            return;
        }
        metasSorted.sort(java.util.Comparator.naturalOrder());

        // For each entry, enforce: tested-version < next(metadata-version), if next exists
        for (JsonNode entry : json) {
            String underMetaStr = entry.path("metadata-version").isTextual() ? entry.get("metadata-version").asText() : null;
            if (underMetaStr == null) continue;

            VersionNumber underMeta;
            try {
                underMeta = VersionNumber.parse(underMetaStr);
            } catch (Exception ignore) {
                continue;
            }

            // Locate index of current metadata-version in the sorted list
            int idx = -1;
            for (int i = 0; i < metasSorted.size(); i++) {
                if (metasSorted.get(i).compareTo(underMeta) == 0) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) continue;

            VersionNumber nextMeta = (idx < metasSorted.size() - 1) ? metasSorted.get(idx + 1) : null;

            JsonNode tvs = entry.get("tested-versions");
            if (tvs != null && tvs.isArray() && nextMeta != null) {
                for (JsonNode tvNode : tvs) {
                    if (tvNode != null && tvNode.isTextual()) {
                        try {
                            VersionNumber tv = VersionNumber.parse(tvNode.asText());
                            // Must be strictly less than the next metadata-version
                            if (tv.compareTo(nextMeta) >= 0) {
                                failures.add("❌ " + filePath + ": tested-versions contains version " + tv
                                        + " not less than next metadata-version " + nextMeta
                                        + " (under metadata-version " + underMetaStr + ")");
                            }
                        } catch (Exception ignore) {
                            // ignore unparsable tested versions; schema should catch most cases
                        }
                    }
                }
            }
        }
    }

    public static String mapToSchemaPath(String filePath) {
        if (METADATA_PATTERN.matcher(filePath).matches()) {
            return "metadata/schemas/metadata-library-index-schema-v2.0.0.json";
        }
        return "";
    }
}
