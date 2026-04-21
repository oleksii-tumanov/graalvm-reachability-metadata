/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.graalvm.internal.tck.MetadataFilesCheckerTask;
import org.graalvm.internal.tck.harness.TckExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CheckMetadataFilesAllTaskTests {

    @TempDir
    Path tempDir;

    @Test
    void computeMatchingCoordinatesSupportsSharedOnlyExactVersion() throws IOException {
        Project project = createProjectSkeleton();
        writeSharedMetadataIndex("com.example", "demo", "1.0.0", List.of("1.0.0", "1.0.1"));

        ExposedCheckMetadataFilesAllTask task = project.getTasks().create("checkMetadataFiles", ExposedCheckMetadataFilesAllTask.class);

        assertThat(task.exposedComputeMatchingCoordinates("com.example:demo:1.0.1"))
                .containsExactly("com.example:demo:1.0.1");
    }

    @Test
    void computeMatchingCoordinatesSupportsSharedOnlyVersionsForArtifactFilter() throws IOException {
        Project project = createProjectSkeleton();
        writeSharedMetadataIndex("com.example", "demo", "1.0.0", List.of("1.0.0", "1.0.1"));

        ExposedCheckMetadataFilesAllTask task = project.getTasks().create("checkMetadataFiles", ExposedCheckMetadataFilesAllTask.class);

        assertThat(task.exposedComputeMatchingCoordinates("com.example:demo"))
                .containsExactlyInAnyOrder("com.example:demo:1.0.0", "com.example:demo:1.0.1");
    }

    @Test
    void runAllCreatesMetadataValidationTaskForSharedOnlySupportedVersion() throws IOException {
        Project project = createProjectSkeleton();
        writeSharedMetadataIndex("com.example", "demo", "1.0.0", List.of("1.0.0", "1.0.1"));
        writeValidReachabilityMetadata("com.example", "demo", "1.0.0");
        copyReachabilitySchemaFile();
        project.getExtensions().getExtraProperties().set("coordinates", "com.example:demo:1.0.1");

        ExposedCheckMetadataFilesAllTask task = project.getTasks().create("checkMetadataFiles", ExposedCheckMetadataFilesAllTask.class);

        assertThatCode(task::runAll).doesNotThrowAnyException();

        List<String> createdTaskNames = project.getTasks().stream()
                .filter(MetadataFilesCheckerTask.class::isInstance)
                .map(Task::getName)
                .collect(Collectors.toList());

        assertThat(createdTaskNames).hasSize(1);
        assertThat(createdTaskNames.get(0)).startsWith("checkMetadataFiles_com.example_demo_1.0.1_");
    }

    private Project createProjectSkeleton() throws IOException {
        Files.createDirectories(tempDir.resolve("metadata"));
        Files.createDirectories(tempDir.resolve("tests/tck-build-logic"));
        Files.writeString(tempDir.resolve("LICENSE"), "test");

        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        project.getExtensions().create("tck", TckExtension.class, project);
        return project;
    }

    private void writeSharedMetadataIndex(String groupId, String artifactId, String metadataVersion, List<String> testedVersions) throws IOException {
        Path artifactRoot = tempDir.resolve("metadata").resolve(groupId).resolve(artifactId);
        Files.createDirectories(artifactRoot.resolve(metadataVersion));
        Files.writeString(
                artifactRoot.resolve("index.json"),
                """
                [
                  {
                    "latest": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "%s",
                    "tested-versions": %s
                  }
                ]
                """.formatted(metadataVersion, toJsonArray(testedVersions))
        );
    }

    private void writeValidReachabilityMetadata(String groupId, String artifactId, String metadataVersion) throws IOException {
        Path metadataDir = tempDir.resolve("metadata").resolve(groupId).resolve(artifactId).resolve(metadataVersion);
        Files.createDirectories(metadataDir);
        Files.writeString(
                metadataDir.resolve("reachability-metadata.json"),
                """
                {
                  "reflection": [
                    {
                      "type": "com.example.Demo",
                      "allDeclaredMethods": true
                    }
                  ]
                }
                """
        );
    }

    private void copyReachabilitySchemaFile() throws IOException {
        Path source = findRepoFile("metadata/schemas/reachability-metadata-schema-v1.2.0.json");
        Path target = tempDir.resolve("metadata/schemas/reachability-metadata-schema-v1.2.0.json");
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
    }

    private String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static Path findRepoFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate " + relativePath + " from " + Path.of("").toAbsolutePath());
    }

    abstract static class ExposedCheckMetadataFilesAllTask extends CheckMetadataFilesAllTask {
        @Inject
        public ExposedCheckMetadataFilesAllTask() {
        }

        List<String> exposedComputeMatchingCoordinates(String filter) {
            return computeMatchingCoordinates(filter);
        }
    }
}
