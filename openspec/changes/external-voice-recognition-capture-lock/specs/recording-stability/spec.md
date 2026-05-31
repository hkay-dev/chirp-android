## ADDED Requirements

### Requirement: External Recognition Acquires Shared Capture Lock

External Android speech-recognition capture SHALL acquire the shared recording lock before opening the microphone.

#### Scenario: Another origin is recording

- **WHEN** external speech recognition is requested while an app, widget, or keyboard recording is active
- **THEN** recognition reports busy and does not start microphone capture.

#### Scenario: External recognition capture stops

- **WHEN** external speech recognition stops, fails to start after lock acquisition, is cancelled, or its host component is destroyed
- **THEN** the shared recording lock is released for later capture.
