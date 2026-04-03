/*
 * Copyright and related rights waived via CC0
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.graalvm.internal.tck.harness.tasks;

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

class ComputeAndPullAllowedDockerImagesTaskTests {

    @TempDir
    Path tempDir;

    @Test
    void resolveMatchingCoordinatesSupportsFractionalBatchForSharedTestVersions() throws IOException {
        Project project = createProject();
        TestComputeAndPullAllowedDockerImagesTask task = project.getTasks().create(
                "pullAllowedDockerImages",
                TestComputeAndPullAllowedDockerImagesTask.class
        );
        TckExtension extension = project.getExtensions().getByType(TckExtension.class);

        assertThat(task.exposedResolveMatchingCoordinates(extension, "1/2"))
                .containsExactly("com.example:demo:1.0.0");
        assertThat(task.exposedResolveMatchingCoordinates(extension, "2/2"))
                .containsExactly("com.example:demo:1.0.1");
    }

    private Project createProject() throws IOException {
        createSharedVersionFixture();
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir.toFile())
                .build();
        project.getExtensions().create("tck", TckExtension.class, project);
        return project;
    }

    private void createSharedVersionFixture() throws IOException {
        Files.createDirectories(tempDir.resolve("metadata/com.example/demo/1.0.0"));
        Files.writeString(
                tempDir.resolve("metadata/com.example/demo/index.json"),
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
                      "1.0.0",
                      "1.0.1"
                    ]
                  }
                ]
                """
        );
        Files.createDirectories(tempDir.resolve("tests/src/com.example/demo/0.9.0"));
        Files.createDirectories(tempDir.resolve("tests/tck-build-logic"));
        Files.writeString(tempDir.resolve("LICENSE"), "test");
    }

    abstract static class TestComputeAndPullAllowedDockerImagesTask extends ComputeAndPullAllowedDockerImagesTask {
        @Inject
        public TestComputeAndPullAllowedDockerImagesTask() {
        }

        List<String> exposedResolveMatchingCoordinates(TckExtension tck, String filter) {
            return resolveMatchingCoordinates(tck, filter);
        }
    }
}
