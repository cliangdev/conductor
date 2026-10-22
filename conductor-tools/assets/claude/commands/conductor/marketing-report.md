---
name: conductor:marketing-report
description: Build the app marketing report. Pulls the whole-funnel numbers from Conductor, refreshes missing or stale industry benchmarks from sources read this session, and renders a ten-section HTML report (summary, health tiles, acquisition, funnel, daily chart, benchmarks, next actions, scorecard, keywords, notes) with prioritised next actions.
allowed-tools: mcp__conductor__*, AskUserQuestion, Skill, WebSearch, WebFetch, Read, Write, Glob, Grep
---

# /conductor:marketing-report

One command for the whole-funnel marketing report on any app or product. The method lives in the
`conductor-marketing-analyst` skill (`Skill(skill: "conductor-marketing-analyst")`); invoke it and
follow it exactly. This command only gathers the inputs.

## Trigger

`/conductor:marketing-report [7d|28d]`, or a request for a marketing, growth or acquisition report,
"how is the app doing", or a review of the funnel.

## Step 1 — Window

Use the argument if given (`7d` or `28d`); otherwise `28d`. Do not ask.

## Step 2 — Hand off

Invoke the `conductor-marketing-analyst` skill with the window. It will:

1. fetch the report with `get_marketing_report`;
2. research and record any missing or stale benchmarks (it asks you once for the product's category,
   store, country and monetisation model if they cannot be found);
3. read due reviews and open changes;
4. write and render the report;
5. offer, never auto-create, Change Work Items for each recommended action.

## Step 3 — Finish

Give the person the path or link to the report and a three-line summary: the headline finding, the
biggest funnel drop, and the first recommended action. Then the offer to record the actions as
Changes. Nothing is written to Conductor beyond benchmarks (which carry their source) unless the
person says yes.
