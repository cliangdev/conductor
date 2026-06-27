package com.conductor.integration.connector.gsc.model;

/** One entry from GET /webmasters/v3/sites. */
public record GscSiteEntry(String siteUrl, String permissionLevel) {}
