# GitHub Actions files (reference copy)

These files are a **reference copy**. They are kept in `ci/github/` on purpose: in this public
repository they must not be active workflows. The real, active versions live in the private
framework repository under `.github/workflows/` and `.github/scripts/`.

## What the framework version does differently

The framework's `performance-run.yaml` is adapted to its CI environment. When applying a change
from this copy, carry it over by hand and keep these differences:

| Topic | This copy | Framework version |
| --- | --- | --- |
| File extension | `.yml` | `.yaml` |
| Runner | `runs-on: ubuntu-latest` | Dedicated workers (the framework's own runner labels) |
| Maven | `bash ./mvnw -B ...` | The same, plus the framework's `--settings <file>` in **both** Maven calls |
| Step names | As written here | May follow the framework's naming style |
| Network | Direct internet access | Through the worker's proxy; the workflow passes `HTTPS_PROXY` / `NO_PROXY` on as `httpsProxy` / `noProxyHosts` (this part is identical in both) |

`perf-args.sh`, `perf-summary.sh` and the five per-type workflows are used unchanged.

## Secrets (framework repository)

`AUTOMATION_PERFORMANCE_TEST_TOKEN_SUBDOMAIN`, `..._CLIENT_ID`, `..._CLIENT_SECRET`, `..._API_SUBDOMAIN`,
`..._PRINCIPAL_ID`, `..._PRINCIPAL_NAME`, `..._PRINCIPAL_TYPE`, `..._PRINCIPAL_SOURCE_DIRECTORY_NAME`,
`..._PRINCIPAL_SOURCE_DIRECTORY_ID`.
