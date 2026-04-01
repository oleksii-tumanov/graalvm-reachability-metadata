# Semantic Index Validation Notes

Branch: `semantic-index-validation`

## Goal

Strengthen validation for `metadata/*/*/index.json` so broken artifact indexes fail early and consistently.

This branch does two things:

1. It makes `validateIndexFiles` inspect artifact-level index files directly instead of discovering them only through resolved coordinates.
2. It adds semantic checks that match the assumptions already used by downstream resolution code.

## Validator Changes

File:
`tests/tck-build-logic/src/main/groovy/org/graalvm/internal/tck/harness/tasks/ValidateIndexFilesTask.java`

Added semantic validation for:

- exactly one `latest: true` entry per artifact index
- unique `metadata-version` values
- existing local metadata directories for normal entries
- existing explicit `test-version` directories
- valid `default-for` regexes
- non-overlapping `default-for` matches across tested versions
- existing `requires` targets
- existing `tested-versions` ordering rule

Important detail:

- `override: true` entries are allowed to omit a local metadata directory.
  This preserves the existing Netty-style pattern where some entries exist only to suppress outdated builtin GraalVM metadata.

## Why Some Files Were Fixed And Others Removed

### Fixed: GraphQL extended validation

Files:

- `metadata/com.graphql-java/graphql-java-extended-validation/index.json`
- `metadata/com.graphql-java/graphql-java-extended-validation/19.2/reachability-metadata.json`

Reason:

- This artifact already had real local metadata (`19.1`) and real local tests.
- The `19.2` entry was a real support entry, but it pointed to a missing local metadata directory.
- The fix was to make test reuse explicit with `test-version: "19.1"` and add the missing local metadata directory for `19.2`.

### Fixed: Netty override-only indexes

Files:

- `metadata/io.netty/netty-buffer/index.json`
- `metadata/io.netty/netty-codec-http/index.json`
- `metadata/io.netty/netty-codec-http2/index.json`
- `metadata/io.netty/netty-handler/index.json`
- `metadata/io.netty/netty-resolver-dns/index.json`

Reason:

- These files were intentionally added to express `override: true`.
- They were incomplete under the current schema because they were missing `allowed-packages`.
- They were not removed because they still serve a real repository purpose.
- They were not converted into normal local-metadata entries because there is no local metadata/test layout for those artifacts today.

### Removed: stale JLine artifact indexes

Files removed:

- `metadata/org.jline/jline-console/index.json`
- `metadata/org.jline/jline-terminal/index.json`

Reason:

- These indexes were introduced mechanically during the old global-index migration.
- They never had local metadata directories or local test directories.
- They were effectively pointing at another artifact (`org.jline:jline`) through `requires`, which is not what `metadata-version` means in this repository.
- Removing them was cleaner than inventing fake local support.

## Repository Assumption Preserved

The current repository model remains:

- `metadata-version` means a local subdirectory under `metadata/<group>/<artifact>/`
- `test-version` is the supported mechanism for reusing tests
- `requires` expresses dependent modules, not cross-artifact metadata storage

## Tests Added

File:
`tests/tck-build-logic/src/test/java/org/graalvm/internal/tck/harness/tasks/ValidateIndexFilesTaskTests.java`

Covers:

- coherent valid index
- missing metadata directory
- missing explicit test directory
- invalid `latest`
- overlapping `default-for`
- missing `requires` target
- allowed override-only entry without local metadata
- end-to-end task failure/success behavior

## Verification

Commands run on this branch:

```bash
./gradlew -p tests/tck-build-logic test --console=plain
./gradlew validateIndexFiles -Pcoordinates=all --console=plain
```

Both passed after the branch fixes.

## PR Framing

Suggested PR framing:

- Strengthen semantic validation for artifact-level metadata indexes
- Prevent broken `index.json` files from being skipped during validation
- Clean up existing invalid index states exposed by the stricter checks

