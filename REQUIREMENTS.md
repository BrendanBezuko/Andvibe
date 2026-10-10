# Requirements

## Scaffolding
- As a developer, I want an automatic cloud scaffolding system so that new Android app projects can be generated and provisioned in the cloud without manual setup.
- As a developer, I want to scaffold new Android apps from a template (existing idea), complementing the cloud-based automatic scaffolding.

## Cloud Proxy
- As a developer, I want a cloud proxy feature so that app traffic/requests can be routed through a managed cloud proxy.
- As a developer, I want to test the cloud proxy server to verify it correctly routes and handles app traffic/requests.

## Build & Debugging
- As a developer, I want a test bar that appears after a build completes, so I can quickly run checks against the built app.
- As a developer, when debug mode is checked, I want MCP to be injected into the app so the test bar can connect to it and interact with the running app for testing.

## Console & Tabs
- As a user, when I'm on a tab (e.g., Build) and click Console, I want the console to close and return me to that tab if I click it again, instead of getting stuck.
- Bug: clicking Console while already on a tab (e.g., Build) does not toggle back to that tab; the console stays open and the user must switch to another tab (e.g., Files) and back to escape the console.

## Search
- As a user, I want search results limited to Android-related content for now.
- As a user, I want a refresh button on the search/news view to reload results.

## Repository & Collaboration (Advanced)
- As a developer, I want to submit a pull request (PR) directly from the app so I can propose changes to a repository.
- As a developer, I want to work with repositories (browse, edit, manage) within the app, enabling more advanced source-control workflows.
- As a developer, I want to fork a repository directly within the app so I can create my own copy to work from.

## Credentials & Workspaces
- As a developer, I want to store GitHub (and other) credentials at the workspace level so each workspace can have its own associated credentials.
- As a developer, I want to add credentials once (e.g., for a GitHub account) and then select which saved credential to use per workspace, rather than re-entering them each time.

## UI & Usability
- As a user, I want larger buttons throughout the app so they are easier to see and tap.
- Buttons are currently too flashy/distracting (existing known issue, related to overall button styling).
- As a user, I want an option to make all buttons pink so I can customize the app's look.

## Other Known Issues
- Search behaves oddly in some cases.
- Notifications are not working.
- The build button has issues.
- Projects auto-change unexpectedly.
- Buttons are too flashy/distracting.
- Console does not toggle properly when clicked from an active tab.

## Other Ideas
- Permissions editor for managing app permissions.