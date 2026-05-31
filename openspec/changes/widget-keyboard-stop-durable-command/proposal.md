## Why

Widget stop for an active keyboard recording falls back to a durable pending-stop store when the IME is unbound, but the enqueue currently runs in an unowned coroutine and the widget reports success before persistence completes. If the receiver process dies immediately after `onReceive`, the stop request can be lost while keyboard capture keeps running.

## What Changes

- Make keyboard pending-stop enqueue awaitable from the origin-aware stop command.
- Use `BroadcastReceiver.goAsync()` in the widget receiver so fallback stop persistence completes before the broadcast is finished.
- Add tests for the command contract and widget receiver fallback.

## Capabilities

### Modified Capabilities

- `keyboard-recording`: widget-origin stops for keyboard recordings are durable before the widget reports queued stop.

## Impact

- Modules: `core-contracts`, `feature-widget`
- Depends on: none
- Verification: `./gradlew :core-contracts:testDebugUnitTest :feature-widget:testDebugUnitTest`, `./gradlew :app:assembleDebug`
