# Contributor and agent guide — AddOn API v1 base

This branch contains the experimental base layer used by the independent Dual
VoT Yandex Add-on. Read `HANDOFF.md`, then the repository README, notices and
contribution instructions before editing.

## Scope

This branch owns AddOnApi v1, AddOnManager, the base `Add-on support` patch,
the official voice-over engine integration, and engine coordination. The
companion Yandex implementation lives in
[`dual-vot-yandex-addon`](https://github.com/sashade8-ship-it/dual-vot-yandex-addon).

The public development contract is pinned by the add-on to commit
`c73d8555dbe0bd8db25a516bc2490331b5341001` on `codex/addon-api-v1`. It is
not a stable upstream contract.

## Invariants

- Keep AddOnApi v1 signatures and `API_VERSION == 1` stable unless both
  repositories, validation, and the compatibility pin are deliberately
  updated together.
- `AddOnManager.registerAddOns()` must be present before add-on registration.
- Reserve the built-in `official` engine through the trusted internal path
  before external add-ons register. Public registration must reject `official`.
- The base coordinator, not an add-on, owns engine exclusivity, lifecycle, and
  legacy button slots.
- Preserve Morphe licenses, notices, credits, and independent-project status.
- Never claim a device result without an observed Manager/APK test.

## Compatibility test

Build the base, then build the pinned add-on against it. For a manual Manager
test, add both `.mpp` bundles as Local sources and select base `Add-on support`
and `Voice over translation` plus add-on `Voice Over Translation (Yandex)` in
one Expert-mode patching session.

After material work, update `HANDOFF.md` with exact commits, verification,
unverified behaviour, and the next step.
