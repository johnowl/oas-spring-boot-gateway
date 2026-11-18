# <NNN> — <Title>

<!--
Copy this file to specs/<NNN>-<slug>.md. Use the next free number.
Write in plain English: short sentences, active voice, present tense, one term for one concept.
Use the RFC 2119 keywords (MUST, SHOULD, MAY) in uppercase only for requirements.
Delete all guidance comments before you open the pull request.
-->

| Field | Value |
|---|---|
| Status | Draft |
| Phase | <n — name> |
| Depends on | [<NNN>](<NNN>-<slug>.md), ... or "None" |
| Modules | `openapi-gateway-core`, ... |

<!--
Status: Draft, Proposed, Conditional, Optional, Accepted, or Implemented. See specs/README.md.
Depends on: list direct dependencies only. Use relative links.
Modules: list each Maven module or repository path that the spec changes.
-->

## Summary

<One paragraph: what and why.>

<!-- Answer two questions in one paragraph: what does this spec add, and why does it matter? -->

## Motivation

<!-- Describe the problem or the risk without this spec. Give a concrete example if possible. -->

## Scope

### In scope

<!-- Bulleted list of what this spec delivers. -->

### Out of scope

<!-- Bulleted list of related work that this spec does not deliver. Link to the spec that delivers it, if one exists. -->

## Design

<Subsections, code/config examples, tables, Mermaid if useful.>

<!--
Describe the classes, data formats, and flows. Use tables for fields and rules.
Give an example for every non-obvious rule or format.
Use code blocks with a language tag. Keep Mermaid diagrams small.
-->

## Behavior and errors

<!--
Omit this section if it does not apply.
Describe the behavior for each input class, including errors: status codes, exit codes, messages, and log output.
A table with "Condition" and "Result" columns works well.
-->

## Configuration

<!--
Omit this section if it does not apply.
List each property or option with its name, type, default value, and meaning. Give one example.
-->

## Acceptance criteria

- [ ] <testable statement>

<!--
Write each criterion so that a reviewer can answer "yes" or "no".
Bad: "Routing is fast." Good: "A request to an undocumented path returns 404."
-->

## Test plan

<!-- List the tests by type (unit, integration, fixture, benchmark) and what each test verifies. Tests are part of the same PR. -->

## Delivery

<PR slices. Usually "One PR." with an estimate of the main files.>

<!--
Target: fewer than 400 changed lines per PR, without test fixtures and generated files.
If the work is larger, list the PR slices in order. Each slice must build and pass its tests.
-->

## Open questions

<!-- List each decision that is not made and each fact that is not verified. Write "None." if there are none. Do not guess. -->

## References

<!-- Links to related specs, external documentation, and standards. Use relative links for files in this repository. -->
