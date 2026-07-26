# Automatic upstream updates

Dual VoT Patches tracks both Morphe release channels:

| Dual VoT branch | Morphe branch | Manager channel | Release type |
| --- | --- | --- | --- |
| `main` | `main` | Stable | GitHub stable release |
| `dev` | `dev` | Pre-release patches enabled | GitHub pre-release |

The scheduled workflow checks both channels every six hours. It synchronizes
exact Morphe release tags, not arbitrary unreleased branch commits.

## Safety model

Preparation and publication run as separate jobs:

1. A read-only job fetches the upstream tag, performs the merge, builds the
   Android patch bundle, regenerates metadata, and validates Dual VoT.
2. The job exports an incremental Git bundle, release plan, and `.mpp`.
3. A write-enabled job verifies their commit and SHA-256 values, publishes the
   release, and advances the channel branch. It does not execute upstream code.

An update is rejected when:

- an unknown source file has a merge conflict;
- Gradle compilation fails;
- a required Dual VoT source file is missing;
- either translation patch is absent from generated metadata;
- the `.mpp` manifest, version, size, or checksum is inconsistent;
- the remote branch changes while the candidate is building.

On failure, the previous published bundle remains available and the workflow
creates or updates a deduplicated `automation` issue with a link to its logs.

## Versioning

Automated versions keep the exact Morphe base and add a Dual VoT revision:

```text
Morphe 1.38.0       -> 1.38.0-dualvot.1
Morphe 1.39.0-dev.2 -> 1.39.0-dev.2-dualvot.1
```

Users receive `main` by default. Morphe Manager checks `dev` as well when
**Pre-release patches** is enabled for this source.

## Manual operation

Open **Actions → Sync Morphe upstream → Run workflow** and select:

- `all` to check stable and pre-release sequentially;
- `stable` to check only Morphe `main`;
- `dev` to check only Morphe `dev`.

The workflow uses the repository-scoped `GITHUB_TOKEN`; no personal access
token or external bot account is required.
