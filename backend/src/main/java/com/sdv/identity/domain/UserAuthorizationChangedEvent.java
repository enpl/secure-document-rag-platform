package com.sdv.identity.domain;

public record UserAuthorizationChangedEvent(String issuer, String subject, long authorizationRevision) { }
