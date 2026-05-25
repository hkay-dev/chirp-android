## ADDED Requirements

### Requirement: Widget localized status strings

All user-visible widget status text SHALL be defined in string resources, not hardcoded in Kotlin or layout XML.

#### Scenario: Idle status uses string resource

- **WHEN** widget is in idle state
- **THEN** status text SHALL come from `@string/widget_status_idle`
- **AND** SHALL NOT use hardcoded English literals in `RecordingWidgetProvider`

#### Scenario: Transitional states use string resources

- **WHEN** widget displays Starting, Saving, or Error states
- **THEN** each status label SHALL reference a dedicated string resource
- **AND** strings SHALL be suitable for future localization

#### Scenario: Layout text uses string resources

- **WHEN** widget layout XML defines default text for title or content description
- **THEN** it SHALL reference `@string/` resources
- **AND** SHALL NOT embed literal user-facing English in XML attributes

---

### Requirement: Widget color tokens

Widget visual styling SHALL use centralized color resources instead of inline hexadecimal values in provider code or layout XML where RemoteViews supports them.

#### Scenario: Recording accent color from resource

- **WHEN** widget button shows recording or idle record state with red accent
- **THEN** color filter SHALL be applied using `@color/widget_accent_recording`
- **AND** hex literals SHALL NOT appear in `RecordingWidgetProvider`

#### Scenario: Inactive accent color from resource

- **WHEN** widget is in stopping or inactive processing state
- **THEN** grey/inactive tint SHALL use `@color/widget_accent_inactive`

#### Scenario: Surface and text colors from resources

- **WHEN** widget background and status text are rendered
- **THEN** colors SHALL reference `@color/widget_surface`, `@color/widget_on_surface`, and `@color/widget_on_surface_variant`
- **AND** layout XML SHALL NOT hardcode `#RRGGBB` values for themeable elements

---

### Requirement: Widget branded notification icon

System notifications triggered by widget-initiated recording failures SHALL use the application's branded small icon drawable.

#### Scenario: Error notification icon

- **WHEN** widget-initiated recording fails and a system notification is posted
- **THEN** notification `smallIcon` SHALL use the branded app notification drawable (not a framework generic icon)
- **AND** icon SHALL meet Android notification monochrome guidelines where applicable

#### Scenario: Notification content uses string resources

- **WHEN** widget error path surfaces a notification message
- **THEN** title and body text SHALL use string resources
- **AND** SHALL NOT use hardcoded English error strings in notification builder code

## MODIFIED Requirements

### Requirement: Widget States

The system SHALL show recording state in the widget.

#### Scenario: Idle state

- **WHEN** no recording is in progress
- **THEN** widget shows record button
- **AND** status text displays localized idle prompt from string resources
- **AND** tapping starts a new recording

#### Scenario: Recording state

- **WHEN** recording is in progress
- **THEN** widget shows stop button
- **AND** shows recording duration via chronometer
- **AND** button accent uses `@color/widget_accent_recording`
- **AND** tapping stops the recording

#### Scenario: Starting state

- **WHEN** recording is starting
- **THEN** widget shows stop button with recording accent
- **AND** status displays localized "starting" string resource

#### Scenario: Stopping state

- **WHEN** recording is stopping or saving
- **THEN** widget shows stop button with inactive accent color
- **AND** status displays localized "saving" string resource

#### Scenario: Error state

- **WHEN** widget reflects an error state
- **THEN** widget shows record button with recording accent
- **AND** status displays localized error string resource

---

### Requirement: Widget appearance

The widget SHALL present consistent branding and readable status information on the home screen.

#### Scenario: Widget title localized

- **WHEN** widget is displayed
- **THEN** title text SHALL use `@string/widget_title` (or app name string resource)
- **AND** SHALL NOT hardcode product name in layout XML

#### Scenario: Content descriptions localized

- **WHEN** widget record/stop button is present
- **THEN** `contentDescription` SHALL reference `@string/widget_desc_toggle_recording`
- **AND** SHALL support accessibility services in the user's locale

#### Scenario: Theme-aware palette via resources

- **WHEN** widget is displayed in light or dark system theme
- **THEN** widget colors SHALL be defined via resource qualifiers where feasible (`values/colors.xml`, `values-night/colors.xml`)
- **AND** visual contrast between surface, button, and status text SHALL remain readable
