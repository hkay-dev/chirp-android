## ADDED Requirements

### Requirement: Widget Keyboard Stop Is Durable Before Acknowledgement

The system SHALL persist a widget-requested pending stop for an unbound keyboard recording before reporting that the stop was queued.

#### Scenario: Widget stops keyboard recording while IME is unbound

- **WHEN** the widget requests stop and the keyboard stop bridge has no active handler
- **THEN** the pending stop is durably enqueued before the widget receiver finishes the broadcast or reports queued stop.
