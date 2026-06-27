package com.conductor.integration.connector.gsc.model;

import java.util.List;

/** Response from GET /webmasters/v3/sites. */
public record GscSitesResponse(List<GscSiteEntry> siteEntry) {
    public List<GscSiteEntry> siteEntryOrEmpty() {
        return siteEntry != null ? siteEntry : List.of();
    }
}
