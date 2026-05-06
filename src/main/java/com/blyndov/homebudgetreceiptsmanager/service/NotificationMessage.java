package com.blyndov.homebudgetreceiptsmanager.service;

public record NotificationMessage(String subject, String body, NotificationAttachment attachment) {

    public NotificationMessage(String subject, String body) {
        this(subject, body, null);
    }
}
