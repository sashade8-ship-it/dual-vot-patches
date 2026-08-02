# AddOn API v1 base handoff

**Updated:** 2026-08-02

## Status

This is an experimental development branch, `codex/addon-api-v1`, at
`c73d8555dbe0bd8db25a516bc2490331b5341001` before this documentation update.
It supplies the base compatibility layer for the independent
[`dual-vot-yandex-addon`](https://github.com/sashade8-ship-it/dual-vot-yandex-addon).
It is not a stable upstream or end-user release.

## Implemented base contract

- AddOnApi v1 and AddOnManager registration hook.
- Base `Add-on support` patch and official voice-over integration.
- Centralized engine ownership, lifecycle and legacy-control coordination.
- Trusted reservation of the `official` engine before public add-on
  registration. External callers cannot claim that reserved ID.
- Unit coverage for the API/coordinator integration.

The Yandex engine, runtime, controls, settings and validation belong to the
separate add-on repository. Both bundles are required for an integration test.

## Verification and limits

Base compilation and the combined base/add-on GitHub Actions build have passed
for the commit pin above. This does not establish Manager compatibility or
device behaviour. No stable tag/release is created from this branch.

## Next step

Use the exact pinned base and add-on `.mpp` bundles in one Morphe Manager
Expert-mode patching session. Select `Add-on support`, `Voice over translation`
and `Voice Over Translation (Yandex)`, then preserve Manager/APK evidence for
any button or runtime diagnosis.

Do not change public API signatures, registration order, engine reservation,
or lifecycle ownership independently of the companion add-on and its
compatibility metadata.
