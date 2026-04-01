/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.graalvm.internal.tck.harness.TckExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidateIndexFilesTaskTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validateLibraryIndexSemanticsAcceptsCoherentIndex() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "default-for": "1\\\\.0\\\\..*",
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "test-version": "0.9.0",
                    "tested-versions": [
                      "1.0.0",
                      "1.0.1"
                    ],
                    "requires": [
                      "com.example:dependency"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(artifactDir.resolve("1.0.0"));
        Files.createDirectories(tempDir.resolve("tests/src/com.example/demo/0.9.0"));
        Files.createDirectories(tempDir.resolve("metadata/com.example/dependency"));
        Files.writeString(tempDir.resolve("metadata/com.example/dependency/index.json"), "[]");

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).isEmpty();
    }

    @Test
    void validateLibraryIndexSemanticsRejectsMissingMetadataDirectory() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "tested-versions": [
                      "1.0.0"
                    ]
                  }
                ]
                """
        );

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).anyMatch(message -> message.contains("metadata-version '1.0.0' points to missing directory"));
    }

    @Test
    void validateLibraryIndexSemanticsAllowsOverrideOnlyEntryWithoutMetadataDirectory() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "override": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "tested-versions": [
                      "1.0.0"
                    ]
                  }
                ]
                """
        );

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).isEmpty();
    }

    @Test
    void validateLibraryIndexSemanticsRejectsMissingExplicitTestVersionDirectory() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "test-version": "0.9.0",
                    "tested-versions": [
                      "1.0.0"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(artifactDir.resolve("1.0.0"));

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).anyMatch(message -> message.contains("test-version '0.9.0' points to missing directory"));
    }

    @Test
    void validateLibraryIndexSemanticsRejectsInvalidLatestAndOverlappingDefaults() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "default-for": "1\\\\..*",
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "tested-versions": [
                      "1.0.0"
                    ]
                  },
                  {
                    "latest": true,
                    "default-for": "1\\\\.0\\\\..*",
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.1",
                    "tested-versions": [
                      "1.0.1"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(artifactDir.resolve("1.0.0"));
        Files.createDirectories(artifactDir.resolve("1.0.1"));

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).anyMatch(message -> message.contains("expected exactly one entry with latest=true but found 2"));
        assertThat(failures).anyMatch(message -> message.contains("matches multiple default-for entries"));
    }

    @Test
    void validateLibraryIndexSemanticsRejectsInvalidRequiresTargets() throws IOException {
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "tested-versions": [
                      "1.0.0"
                    ],
                    "requires": [
                      "com.example:missing"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(artifactDir.resolve("1.0.0"));

        List<String> failures = ValidateIndexFilesTask.validateLibraryIndexSemantics(
                readArtifactIndex(artifactDir),
                artifactDir,
                tempDir.resolve("metadata"),
                tempDir.resolve("tests/src"),
                "metadata/com.example/demo/index.json"
        );

        assertThat(failures).anyMatch(message -> message.contains("requires target 'com.example:missing' is missing index"));
    }

    @Test
    void validateTaskRejectsSemanticErrors() throws IOException {
        writeSchemaFile();
        createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "test-version": "0.9.0",
                    "tested-versions": [
                      "1.0.0"
                    ],
                    "requires": [
                      "com.example:missing"
                    ]
                  }
                ]
                """
        );

        Project project = createProject();
        TestValidateIndexFilesTask task = project.getTasks().create("validateIndexFiles", TestValidateIndexFilesTask.class);
        task.setCoordinatesOption("com.example:demo:1.0.0");

        assertThatThrownBy(task::validate)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Validation failed");
    }

    @Test
    void validateTaskAcceptsSemanticValidIndex() throws IOException {
        writeSchemaFile();
        Path artifactDir = createArtifactIndex(
                """
                [
                  {
                    "latest": true,
                    "default-for": "1\\\\.0\\\\..*",
                    "allowed-packages": [
                      "com.example"
                    ],
                    "metadata-version": "1.0.0",
                    "test-version": "0.9.0",
                    "tested-versions": [
                      "1.0.0"
                    ],
                    "requires": [
                      "com.example:dependency"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(artifactDir.resolve("1.0.0"));
        Files.createDirectories(tempDir.resolve("tests/src/com.example/demo/0.9.0"));
        Files.createDirectories(tempDir.resolve("metadata/com.example/dependency"));
        Files.writeString(tempDir.resolve("metadata/com.example/dependency/index.json"), "[]");

        Project project = createProject();
        TestValidateIndexFilesTask task = project.getTasks().create("validateIndexFiles", TestValidateIndexFilesTask.class);
        task.setCoordinatesOption("com.example:demo:1.0.0");

        assertThatCode(task::validate).doesNotThrowAnyException();
    }

    private Project createProject() throws IOException {
        Files.createDirectories(tempDir.resolve("tests/tck-build-logic"));
        Files.writeString(tempDir.resolve("LICENSE"), "test");
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        project.getExtensions().create("tck", TckExtension.class, project);
        return project;
    }

    private Path createArtifactIndex(String json) throws IOException {
        Path artifactDir = tempDir.resolve("metadata/com.example/demo");
        Files.createDirectories(artifactDir);
        Files.writeString(artifactDir.resolve("index.json"), json);
        return artifactDir;
    }

    private JsonNode readArtifactIndex(Path artifactDir) throws IOException {
        return MAPPER.readTree(artifactDir.resolve("index.json").toFile());
    }

    private void writeSchemaFile() throws IOException {
        Path source = findRepoFile("metadata/schemas/metadata-library-index-schema-v2.0.0.json");
        Path target = tempDir.resolve("metadata/schemas/metadata-library-index-schema-v2.0.0.json");
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
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

    abstract static class TestValidateIndexFilesTask extends ValidateIndexFilesTask {
        @Inject
        public TestValidateIndexFilesTask() {
        }
    }
}
