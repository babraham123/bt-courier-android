# Security

## Supported versions

BT Courier is pre-alpha. Only the latest tagged release receives fixes.

## Reporting a vulnerability

Report privately through GitHub private vulnerability reporting: open the repository's
Security tab and choose "Report a vulnerability". Do not open a public issue.

## Scope

- Credential storage: secrets, OIDC tokens and the SFTP key in the Android Keystore.
- Sinks: https-only URLs, no cleartext traffic, redirects not followed.
- OIDC device authorization flow and token refresh.
- SFTP host-key pinning and trust-on-first-use.
- Log and diagnostics export redaction.
- The BLE link to paired devices (bonding, encrypted connection).

Out of scope: vulnerabilities in the device firmware or in the backends you upload to; report
those to their projects.

## Response

Reports are acknowledged and triaged on a best-effort basis, and fixed in a new release with an
advisory once a fix is available.
