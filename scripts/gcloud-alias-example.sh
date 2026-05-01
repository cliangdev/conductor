#!/usr/bin/env bash
# Example: add a shell alias for your Conductor GCP deployment so you don't
# have to repeat --configuration and --project on every gcloud command.
#
# 1. Copy the alias line below into your ~/.zshrc or ~/.bashrc, filling in
#    your actual configuration name and GCP project ID.
# 2. Reload your shell: source ~/.zshrc
#
# Usage after setup:
#   gconductor logging read "resource.type=cloud_run_revision ..." --limit=50
#   gconductor run services list

# alias gconductor='gcloud --configuration=<your-config> --project=<your-gcp-project>'
