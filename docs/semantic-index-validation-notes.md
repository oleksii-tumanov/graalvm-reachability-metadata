# Shared Version Batching Handoff

Branch: `shared-version-batching`

## Goal

Make fractional batching (`k/n`) include supported library versions that reuse shared metadata/test directories via artifact-level `index.json` mapping (`metadata-version`, `tested-versions`, `test-version`).

## Why This Change Is Needed

After strict batching behavior was introduced in `30cf3f46`, fractional filters used `getMatchingCoordinatesStrict("all")`.  
That strict mode requires a physical `metadata/<group>/<artifact>/<tested-version>/` directory for each tested version.

For shared-version entries, this excludes valid supported versions, for example:

- `metadata-version: "1.0.0"`
- `tested-versions: ["1.0.0", "1.0.1"]`
- only `metadata/.../1.0.0/` exists
- `test-version: "0.9.0"` may be reused

In that case, strict batching dropped `1.0.1` even though it is explicitly supported.

## Code Changes

Updated fractional-batch coordinate resolution to use non-strict matching:

- `tests/tck-build-logic/src/main/groovy/org/graalvm/internal/tck/harness/tasks/CoordinatesAwareTask.java`
  - `computeMatchingCoordinates(...)` now uses `tckExtension.getMatchingCoordinates("all")` for `k/n`.

- `tests/tck-build-logic/src/main/groovy/org/graalvm/internal/tck/harness/tasks/ComputeAndPullAllowedDockerImagesTask.java`
  - Added `resolveMatchingCoordinates(...)` helper.
  - Fractional path now uses `tck.getMatchingCoordinates("all")` for `k/n`.

## Deliberate Non-Changes

Changed-coordinates detection remains strict:

- `tests/tck-build-logic/src/main/groovy/org/graalvm/internal/tck/harness/TckExtension.java`
  - `diffCoordinates(...)` still uses `getMatchingCoordinatesStrict("")`.

This preserves existing CI behavior for changed-coordinate selection and avoids broadening that scope in this patch.

## Tests Added

- `tests/tck-build-logic/src/test/java/org/graalvm/internal/tck/harness/tasks/CoordinatesAwareTaskTests.java`
  - Verifies `1/2` and `2/2` include both shared tested versions.

- `tests/tck-build-logic/src/test/java/org/graalvm/internal/tck/harness/tasks/ComputeAndPullAllowedDockerImagesTaskTests.java`
  - Verifies Docker-image task fractional resolution includes shared tested versions.

## Verification

Executed:

```bash
./gradlew -p tests/tck-build-logic test --tests "*CoordinatesAwareTaskTests" --tests "*ComputeAndPullAllowedDockerImagesTaskTests" --console=plain
```

Result: passed.

Note:

- A full `./gradlew -p tests/tck-build-logic test` run currently fails on `ScaffoldTaskTests` in this working tree, unrelated to files changed in this branch.

## PR Framing

Suggested framing:

- Make fractional batching shared-version aware for task execution paths.
- Preserve strict changed-coordinate diff behavior to keep CI scope stable.
