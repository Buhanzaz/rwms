# RWMS Technical Contracts

Framework-neutral immutable records shared only for cross-cutting wire shapes.
The module intentionally has no runtime dependency and must remain free of
Spring, JPA, broker clients, business DTOs, domain enums, and persistence types.

Canonical domain APIs and events remain handwritten schemas under `contracts/`.
Services map those schemas to service-local domain models; this library does not
create shared domain ownership.

Run its contract tests from the repository root:

```powershell
.\gradlew.bat :platform:technical-contracts:test
```
