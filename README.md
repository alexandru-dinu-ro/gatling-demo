# gatling-demo

Gatling performance tests for the CyberArk (Idira) **Access Control Policies API**:
list (with paging), get, create, update and delete policies. Built for a **shared tenant**,
so safety and self-cleaning come first.

## Stack

Java 25, Maven, Gatling 3.16.0 (Java DSL), TestNG 7.12.0, Apache HttpClient 4.5.14,
Jackson 2.22.3, Log4j 2.26.1.

## What it runs

| Type | Simulations | Purpose |
| --- | --- | --- |
| Smoke | `SmokeSimulation` | Every request once; run first, always |
| Load | `LoadListSimulation`, `LoadCrudSimulation`, `LoadMixedSimulation` | Baselines at normal traffic (default 2 req/s) |
| Stress | `StressList`, `StressGet`, `StressCreate`, `StressUpdate`, `StressDelete`, `StressMixed` (each `...Simulation`) | Step up to the cap (default 10 req/s) to find limits |
| Spike | `SpikeMixedSimulation` | Sudden burst, then a recovery check |
| Soak | `SoakMixedSimulation` | One hour at normal traffic, p95 per 10 minutes |

## Layout

| Path | Contents |
| --- | --- |
| `src/test/java/com/example/tests/api/performance/` | Simulations and support code, with offline unit tests |
| `src/test/resources/performance-defaults.properties` | Every setting with its default and a comment |
| `src/test/resources/performance/payloads/` | Policy payload templates (valid JSON) |
| `src/test/resources/test_suites/performance-unit.xml` | Unit test suite (offline) |
| `ci/github/` | Reference copy of the GitHub Actions workflows and scripts (inactive here; see its README) |

## Configuration

Each setting is read from `-D<name>=<value>` first, then from
`src/test/resources/performance-local.properties` (git-ignored; create it yourself), then from
`performance-defaults.properties`. Missing or invalid settings stop a run before any traffic,
listing every problem at once. Secrets are never logged.

The local file needs the connection and principal values:

```properties
tokenSubdomain=...
clientId=...
clientSecret=...
apiSubdomain=...
principalId=...
principalName=...
principalType=USER
principalSourceDirectoryName=...
principalSourceDirectoryId=...
```

If outgoing traffic must go through a proxy, set `httpsProxy` (and optionally `noProxyHosts`):
Java ignores the `HTTPS_PROXY` environment variable.

## Running

```bash
# Offline unit tests (no traffic)
mvn test -DtestSuite=performance-unit.xml

# A simulation: name the class twice (the Gatling plugin does not pass the first to the simulation)
mvn gatling:test \
  -Dgatling.simulationClass=com.example.tests.api.performance.simulations.SmokeSimulation \
  -DperfSimulation=SmokeSimulation
```

Override any setting for one run with `-D`, e.g. `-DseedCount=20`. Reports are written to
`target/gatling/<simulation>-<timestamp>/index.html`.

## Safety

- Hard cap on requests per second (`maxRps`), counting every request.
- A run stops itself when more than 5% of requests fail or the API rate-limits (HTTP 429).
- Test policies are named `automation_performance_test_<runId>_<n>`, grant no real access, and are
  deleted at the end of each run; older leftovers are swept automatically.
- Only one run at a time; a run refuses to start while another run's policies are recent.

## Documentation

A full user and code-review guide (settings, reading results, troubleshooting, architecture)
and the scenario specification are kept in the team's documentation.
