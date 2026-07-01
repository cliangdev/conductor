package com.conductor.service.view;

/** Partial-update fields for an Asset; each null means "leave unchanged" (PATCH semantics). */
public record AssetPatch(String label, String ref, Boolean done) {
}
