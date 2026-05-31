## 1. Implementation

- [x] 1.1 Add an awaitable origin-aware active stop command.
- [x] 1.2 Make widget stop use `goAsync()` and complete pending-stop persistence before reporting queued stop.

## 2. Tests

- [x] 2.1 Cover pending keyboard stop enqueue completing before queued callback.
- [x] 2.2 Cover widget receiver dispatching the durable stop path.

## 3. Verification

- [x] 3.1 `./gradlew :core-contracts:testDebugUnitTest :feature-widget:testDebugUnitTest`
- [x] 3.2 `./gradlew :app:assembleDebug`
