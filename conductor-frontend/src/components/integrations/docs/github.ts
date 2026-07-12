const doc = `## What it does

Installs the **Conductor GitHub App** on your account or organization, scoped to the repositories you pick. Once installed, merging a pull request whose body contains \`closes conductor/KEY-123\` automatically transitions the matching work item to **Done** — no manual status update needed.

## How authentication works

Authorization happens entirely through the **GitHub App installation flow** — click "Install on GitHub", choose an account and repositories, and GitHub redirects back once the installation completes. There are no secrets, tokens, or webhook URLs to paste into Conductor.

Use **"Add or remove repositories"** at any time to change which repositories the installation can see, or **Disconnect** to remove it from this workspace.

## How Conductor uses it

GitHub is event-driven, not fetch-driven: it doesn't expose data-pulling operations for the \`integration\` workflow step type (no entries on this page's **Tools** tab), and there's no Overview dashboard to poll. Instead, GitHub pushes a webhook event on each merged pull request; Conductor parses the PR body for a \`closes conductor/KEY-123\` reference and transitions that work item's status automatically. Recent webhook events and their processing status are shown on this integration's **Overview** tab.
`

export default doc
