# Notifications Onboarding Copy

## Scope

Simplify Android onboarding notification step. Keep existing layout, icon, card, and navigation behavior.

## Approved Copy

- Title: `Notifications`
- Subtitle: `Get connection status and wrist alerts.`
- Card title: `Stay connected`
- Card message: `A quiet notification keeps NOOP connected. Your data stays current.`
- First checkline: `Strain nudges and smart alarms appear here.`
- Second checkline: `When asked, allow notifications.`

## Constraints

- Avoid Android-specific wording.
- Keep copy direct and concise.
- Change no notification behavior.

## Verification

- Structure test asserts exact approved copy.
- Structure test rejects previous copy and `When Android asks`.
- Android unit tests and demo release build pass.
