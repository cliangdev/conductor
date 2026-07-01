package com.conductor.service.view;

/** Fields needed to create a produced-output Asset, decoupled from any generated request DTO version. */
public record AssetInput(String type, String label, String kind, String ref, Boolean done) {
}
