# Flaky Test Quarantine Policy

## Goal

Keep reliability CI signal trustworthy by quarantining nondeterministic tests quickly and remediating them with explicit ownership.

## Rules

1. A test is considered flaky after 2 non-reproducible failures within 7 days.
2. Flaky tests are quarantined in a dedicated ignore list or annotation with a linked issue.
3. Quarantine is time-boxed to 14 days. After that, either fix and re-enable or remove the test.
4. Reliability gate failures caused by quarantined tests must be clearly labeled in CI output.
5. New reliability tests should avoid external timing assumptions and random data without fixed seeds.

## Required Metadata

Every quarantined test must include:

- Owner
- Date quarantined
- Linked issue
- Expected unquarantine date
