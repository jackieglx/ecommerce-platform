## Windows and PowerShell requirements

The primary local development environment is Windows 11 with PowerShell and Docker Desktop.

When creating or modifying `.ps1` scripts:

1. Support Windows PowerShell 5.1 unless the script explicitly requires PowerShell 7.
2. Do not assume Bash, WSL, or Linux command behavior.
3. Validate PowerShell syntax with the AST parser.
4. For external commands, capture stdout and stderr carefully and check `$LASTEXITCODE`.
5. Do not assume external JSON output is a flat object or array. Inspect the real output before implementing parsing.
6. Account for PowerShell automatically unwrapping single-element arrays. Use `@(...)` when array semantics are required.
7. Be careful with string interpolation involving `:`, `{}`, Redis hash tags, paths, and command arguments.
8. Use UTF-8 explicitly where PowerShell 5.1 encoding behavior may differ.
9. Distinguish persistent Compose services from one-shot services:
   - use `docker compose exec` only for running containers;
   - use `docker compose run --rm` for one-shot services such as `spanner-tools`.
10. Resolve Compose files and working directories explicitly. Do not rely on the caller’s current directory.
11. Never consider a PowerShell change verified only because it parses. Execute the affected safe path on Windows when possible.
12. Keep `.ps1` and `.sh` behavior equivalent, but do not mechanically translate Bash syntax into PowerShell.

## Safe validation workflow

For new or changed automation scripts:

1. Inspect the real command output first.
2. Test parsing independently with representative empty, single-row, and multi-row results.
3. Run syntax checks.
4. Run a safe read-only or small-data integration test.
5. Confirm failures do not leave partial result files.
6. Report the verified result and any remaining untested platform behavior.

## Approval boundaries

Before any of the following actions, stop and obtain explicit user approval:

- architecture changes;
- database schema changes;
- Kafka, KRaft, Redis, or Docker infrastructure changes;
- deleting or resetting data, topics, containers, or volumes;
- executing load tests or increasing QPS;
- large refactors spanning multiple modules.

When an unexpected failure occurs:

1. Stop the current workflow.
2. Preserve the failure output.
3. Diagnose with read-only commands first.
4. Report the root cause, proposed files, impact, and minimum fix.
5. Do not implement a materially different solution without approval.