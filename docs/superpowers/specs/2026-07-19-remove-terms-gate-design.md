# Remove Terms Gate Design

## Goal

Remove the mandatory first-run terms screen completely. New installs must open directly into the lean Bluetooth onboarding flow.

## Scope

- Remove `TermsGateScreen` and its `Terms` model.
- Remove all Android terms-screen resources.
- Remove accepted-terms preference keys and root-gate state.
- Remove stale comments describing the terms gate.
- Keep repository-level `TERMS.md` unchanged.
- Preserve onboarding, changelog, profile, and application data behavior.

## Runtime Flow

`NoopRoot` initializes shared lifecycle handling and onboarding state. It then renders onboarding immediately when `noop.onboarded` is false. No terms version is read or persisted.

The existing Bluetooth step becomes the welcome surface instead of adding another page. It shows:

- `Welcome to NOOP`
- `Your wearables. Your data.`
- A Bluetooth card explaining device discovery and connection.
- One `Begin setup` button.

The first step has no Back button. Every later step keeps the existing Back and forward controls.

## Dead-Code Standard

Removal includes every production and test reference to `Terms`, `TermsGateScreen`, `terms_*` resources, `KEY_ACCEPTED_TERMS_VERSION`, and `KEY_ACCEPTED_TERMS_AT`. Historic repository documentation remains outside runtime scope.

## Verification

- A structural test fails while any terms-gate runtime artifact remains.
- Focused unit tests pass after removal.
- Full unit suite passes.
- Demo release assembles.
- Fresh demo data opens Bluetooth onboarding directly.
- The first screen contains the approved welcome copy and only one setup action.
