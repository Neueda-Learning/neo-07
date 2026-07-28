# neo-07 contract and data decisions

Status: accepted for the database foundation.

Sources:

1. `AGENTS.md` and the platform contract it summarizes.
2. `module-07-card-account-setup-v5-use-cases.pdf`.
3. `module-07-card-account-setup-v5-student.pdf`.

The platform contract is authoritative for traffic shared with the orchestrator. The v5
module briefs are authoritative for neo-07's internal account-opening behaviour, operator
screens and persistence model. Where the two describe different wire shapes, this document
keeps the platform contract and translates the v5 concept into local behaviour.

## Fixed orchestrator boundary

- Intake remains `POST /api/v1/applications`.
- The immediate response remains HTTP `202` with exactly
  `{status:"in-progress", applicationId, serviceId, command}`.
- The result remains
  `PUT {ORCHESTRATOR_URL}/api/v1/applications/{applicationId}` with exactly
  `{serviceId, status, comment}`.
- Callback `status` remains one of `ACCEPTED`, `REJECTED` or `REFERRED`.
- `serviceId` remains `neo07`.
- Nothing in `integrations/orchestrator` is changed to match the alternative endpoint names
  shown in the module briefs.

The v5 internal outcome maps to the fixed callback as follows:

| Local outcome | Local reason | Fixed callback |
| --- | --- | --- |
| `OPENED` | `ACC_OPENED` | `ACCEPTED` |
| `OPENED` | `ACC_DUPLICATE_PREVENTED` | `ACCEPTED` |
| `FAILED` | `ACC_CORE_UNAVAILABLE` | `REFERRED` |
| successful manual retry or override to `OPENED` | operator action | `ACCEPTED`; describe the manual action in `comment` |
| override to `FAILED` | operator action | `REFERRED`; describe the manual action in `comment` |

The v5 terms `completed`, `application-manual` and `local-manual` are journey effects, not
values sent in the fixed callback `status` field.

## Persistence decisions

- Only the request envelope's `applicationId` is stored from the applicant/application
  payload. Name, date of birth, email, phone, address and the raw request JSON are forbidden
  from this schema.
- One `account_record` row is the durable anchor for an application.
- `IN_PROGRESS` is a real persisted state because the anchor must be committed before the
  asynchronous work begins.
- `core_config` is insert-only. Current means `MAX(version)`; there is no mutable
  `is_current` flag.
- `core_attempt` and `override_log` are append-only audit trails.
- `timeoutTrap` is not stored in `core_config`; it is volatile mock-core dial state.
- `created_at` is present on `account_record` because the failed queue must be ordered oldest
  first and `opened_at` is null for failures.
- `core_config_version` is nullable while a newly acknowledged row is still `IN_PROGRESS`.
  The worker pins it before its first core call.
- The optional UC-09 limit-mismatch fields are not pre-created. If UC-09 is accepted, they
  require a new append-only Liquibase change set.

## Deliberately unresolved

These values are left unset rather than guessed:

- The locked three-product catalogue is not named completely in the supplied PDFs. No
  invalid `core_config` seed is inserted. A new seed change set must use the exact codes and
  limits from the locked platform contract.
- The fixed request record currently has no v5 `outputs` block. Consequently
  `credit_amount`, `agreement_id` and related product-version data remain nullable until an
  authoritative source/mapping exists.
- The format and allocation strategy for the human-facing `reference` (for example,
  `acc-000123`) is not specified. The database requires uniqueness; UC-00 implementation
  must choose and document a collision-safe generator.
- The meaning/source of `product_version` is not defined by the catalogue JSON example. It
  remains nullable until that rule is confirmed.
- The v5 briefs require later `customer_id` and `card_id` write-back, but the fixed platform
  contract supplied to this repository does not define that inbound payload. The columns are
  prepared and nullable; no write-back endpoint is invented yet.
- The v5 mock-core REST service and its admin-dial payload are implementation work for later
  use cases. This database foundation creates only the module-owned persistence model.

## Migration policy

- `001-create-demo-showcase.yaml` is already applied and is never edited.
- `002-create-account-domain.yaml` adds the four real domain tables.
- `003-normalize-account-fallback-boolean.yaml` preserves `002` and normalizes the MySQL
  physical boolean type to the `BIT(1)` expected by Hibernate schema validation.
- `004-normalize-override-reason.yaml` normalizes the operator justification to a portable
  `VARCHAR(1000)` so H2 and MySQL report the same type to Hibernate.
- `005-restore-domain-nullability.yaml` restores the fallback default and the `NOT NULL`
  constraints that MySQL drops during type-only `MODIFY COLUMN` operations.
- `006-enforce-mysql-fallback-definition.yaml` atomically pins the final MySQL fallback
  definition to `BIT(1) NOT NULL DEFAULT 0`; this avoids MySQL losing attributes across
  separate `ALTER COLUMN` operations.
- `demo_showcase` remains temporarily because the current service still uses it. It will be
  dropped in a later change set only after all Java and UI references are removed.
- The initial `core_config` seed will be a separate new change set once the locked catalogue
  is known. Do not edit `002` to add it later.
