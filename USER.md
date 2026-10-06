# AndVibe — User Stories

Distilled from the project's development chat history (50 Cursor sessions, Oct 1–5, 2026).
This is the product's source of truth for *what the user wants and why*; `DESIGN.md` is how
the code should be shaped to deliver it. Stories keep the original specifics (icons, colors,
layouts) where they were stated — those are requirements, not decoration.

## Vision

A full Android IDE on an Android phone cenhanced with Ai features and controllable by an agent. The core loop:
find or clone a FOSS repo, modify it with an on-device coding agent using your own model API
keys (OpenAI, Anthropic, Gemini, Grok, OpenRouter, or custom), then compile, test, and install
the result — good enough that the apps it builds become daily drivers. The feel: "classic
GitHub vibe but also pure Android app… a serious app, console like a trading platform."
Reference point for the agent: OpenHands, on the phone.

## Non-negotiable principles

These were stated as corrections or emphatic decisions — treat them as constraints:

1. **API keys never leave the device.** A cloud-side agent holding keys was explicitly
   rejected ("terrible idea — the API key stays on the device"). Cloud builds stream logs
   back; the Revise call goes from the phone straight to the provider.
2. **The agent runs locally on the phone.** Not a cloud agent, not a Termux fork — a proper
   on-device agent loop.
3. **The agent modifies the existing project by default.** It creates a new project only when
   explicitly told to, via a dedicated tool.
4. **Busy UI = blocked buttons + the bottom status bar. Nothing flashy.** ("I love the bar
   down by the menu, it's perfect — but don't flash so much.")
5. **Built APKs are precious.** Builds take a long time; save every APK.
6. **No secrets in the repo**, ever (including example adb identifiers in docs).
7. **The debug MCP server is off outside debug builds** (Play Store requirement).
8. **Long work keeps running in the background** — "no one wants to wait foreground."

## Stories by area

### Workspaces
- As a user, I can group repos into a workspace, and every tab (Files, Git, Vibe, Build, APKs,
  Kanban, chat history) only sees what's in the current workspace.
- I can always see the active workspace *and* the active project side by side, so I always
  know what will build and what the agent will modify; I can switch projects from that list.
- The workspace tracks tokens used and a price estimate, shown at the very bottom just above
  the nav bar in sharp red/green text.
- I can reopen previously downloaded/cloned project folders; the last one opens on next launch.

### Vibe (the agent)
- As a user, I get a full chat-turn experience: history, a compact model/provider selector on
  the tab (keys and explanations live in Settings only).
- The agent runs in two phases: a plan phase with read-only tools, then an execute phase —
  with each step visible in the chat.
- The Vibe tab explicitly shows which repo the agent is modifying.
- I can @mention another project in the prompt, Cursor-style, rendered as a tag in the text area.
- I can review and load old chats per workspace.
- I can view and edit every system prompt the app uses in settings
- Send becomes Stop while the agent works.

### Build
- As a user, I press Build and get a signed, installable APK: web projects pack on the phone;
  `gradlew` projects build on Cloud Run (or the hosted Pro builder).
- Build logs stream back live; there is a build log history per workspace.
- The button row is: Build, then Revise (wand icon), then Install.
- Revise sends the failing build log to my model (from the phone) and applies fixes.
- Saved APKs are listed and installable later.
- A security scan step checks built APKs (VirusTotal API now; MobSF-in-docker on Cloud Run is
  on the todo list).

### Git
- As a user, I get VS Code-grade version control: commit list for the current branch, changed
  and staged file lists, per-file + to stage, Stage all, Commit, Pull, Push, Fetch, Branch,
  Log, Init; tap a file for diff/stage/unstage/discard/open.
- An AI button drafts the commit message but never commits by itself.
- Git settings (name, email, HTTPS token, SSH, remote for the active project) live with the
  other settings.
- The tab should look like VS Code's source control, not a toy.

### Search / Find
- As a user, I describe what I want and a web-searching agent finds public repos, prioritizing
  GitHub and other FOSS channels; tapping a result clones it immediately (or opens it if
  already downloaded).
- The same tab is a daily-cached news feed of notable FOSS/tech projects (GitHub
  Trending/Explore/Topics-style sources).

### Understand
- As a user, I open the brain-icon tab, optionally type a focus, and get: code ratings as
  graphs (bar + pie) at the top, a concrete issues list, an overview, a flow diagram and a
  sequence diagram (Mermaid, rendered in-app), plus scanned function definitions.
- It uses the already-selected project — it must never ask me to pick a repo again.
- I can save the result as `UNDERSTAND.md` in the repo and it reloads when I return.

### Board (Kanban)
- As a user, I track ideas, bugs, and solutions on a kanban scoped to the workspace.
- I can tap a card and send it to the agent to build.
- Completed cards move to a completed table and are kept.

### Console & Files
- As a user, I have a console for the basics: help, ls/cd/cat/open, projects, git clone and
  core git commands, compile (JS syntax check), test, run. (Stated: "pretty basic console —
  will get better in the future.")
- Files shows VS Code-style per-filetype icons with compact rows.

### Background work & notifications
- As a user, I get a notification/ping when long work (build, agent, clone) finishes while
  I'm away; work runs as a foreground service so leaving the app doesn't kill it (with the
  battery/energy permissions that requires).

### Debugging (developer-facing)
- As the developer, the app runs a local MCP server (over adb) so my desktop agent can read
  verbose, step-isolated logs, check MCP status in Settings, toggle it on/off, and take
  screenshots of the app for the website.

### Distribution & Pro
- As the maintainer, I ship to the Play Store: an `app.andvibe` application id, a Play flavor
  without the install-packages permission, MCP stripped outside debug, privacy policy + terms
  linked from the website.
- As a Pro subscriber ($10/month via RevenueCat + Supabase, managed at andvibe.org), I paste a
  hosted builder URL + build key and get cloud builds without deploying my own Cloud Run
  (rate-limited: 6/hour, 15/day, 100/30 days, 20 min each). The subscription code stays in the
  separate non-FOSS repo (`Andvibe-subscription`); this repo stays FOSS.

## Stated but not yet built (backlog)

- MobSF scan container on Cloud Run as a second build-scan step.
- A better console.
- Agent improvements beyond the current local loop (explicitly called "just a start").
- Open questions the user raised, undecided: RAG-indexing the codebase for the agent.
