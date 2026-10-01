# gatling-demo

Proof of concept: Gatling (Java DSL) performance tests for a REST API.

## Stack

Java 25, Maven, Gatling 3.15.1, TestNG 7.12.0, Apache HttpClient 4.5.14,
Jackson 2.22.3, Log4j 2.26.1.

## Layout

- `src/test/java/com/example/tests/api/performance/` - simulations and support code
- `src/test/resources/` - default settings, payload templates, test suites

## Configuration

All settings are read from system properties first, then from an optional
local file `performance-local.properties` (never committed), then from
`performance-defaults.properties`. Credentials are never stored in this repo.

## Status

Work in progress, built in stages.
