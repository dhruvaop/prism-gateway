package com.dhruva.airtribe.prism;

/** Authoritative model metadata supplied by a provider adapter. */
public record ModelIdentity(String creator, String family, String variant) {}
