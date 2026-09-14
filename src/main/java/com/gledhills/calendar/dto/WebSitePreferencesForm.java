package com.gledhills.calendar.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

/**
 * Form DTO for website preferences configuration.
 * Used by WebSitePreferencesController.
 */
public class WebSitePreferencesForm {

    // Email Settings
    private Boolean emailPatrollers = false;
    private Boolean emailReminder = false;

    @Min(value = 0, message = "Reminder days must be 0 or greater")
    @Max(value = 30, message = "Reminder days must be 30 or less")
    private Integer reminderDays = 0;

    private Boolean emailAll = false;
    /** The resort's own time zone, an IANA id. Blank means "use the built-in default". */
    private String timeZone = "";

    /** Whether patrollers here may subscribe to their shifts. Off unless a director says otherwise. */
    private boolean syncEnabled = false;

    // Calendar Permissions
    private Boolean onlyDirectors = false;

    @NotNull(message = "Remove access value is required")
    private Integer removeAccess = 0; // 0, 1, 2, 7, 14, 127 days

    // Season Dates
    @Min(value = 1, message = "Start day must be between 1 and 31")
    @Max(value = 31, message = "Start day must be between 1 and 31")
    private Integer startDay = 1;

    @Min(value = 1, message = "Start month must be between 1 and 12")
    @Max(value = 12, message = "Start month must be between 1 and 12")
    private Integer startMonth = 1;

    @Min(value = 1, message = "End day must be between 1 and 31")
    @Max(value = 31, message = "End day must be between 1 and 31")
    private Integer endDay = 31;

    @Min(value = 1, message = "End month must be between 1 and 12")
    @Max(value = 12, message = "End month must be between 1 and 12")
    private Integer endMonth = 12;

    // Blackout Dates
    private Boolean useBlackOut = false;

    @Min(value = 1, message = "Blackout start day must be between 1 and 31")
    @Max(value = 31, message = "Blackout start day must be between 1 and 31")
    private Integer blackOutStartDay = 1;

    @Min(value = 1, message = "Blackout start month must be between 1 and 12")
    @Max(value = 12, message = "Blackout start month must be between 1 and 12")
    private Integer blackOutStartMonth = 1;

    @Min(value = 2000, message = "Blackout start year must be 2000 or later")
    @Max(value = 2100, message = "Blackout start year must be 2100 or earlier")
    private Integer blackOutStartYear = 2026;

    @Min(value = 1, message = "Blackout end day must be between 1 and 31")
    @Max(value = 31, message = "Blackout end day must be between 1 and 31")
    private Integer blackOutEndDay = 31;

    @Min(value = 1, message = "Blackout end month must be between 1 and 12")
    @Max(value = 12, message = "Blackout end month must be between 1 and 12")
    private Integer blackOutEndMonth = 12;

    @Min(value = 2000, message = "Blackout end year must be 2000 or later")
    @Max(value = 2100, message = "Blackout end year must be 2100 or earlier")
    private Integer blackOutEndYear = 2026;

    // Resort context (not part of preferences, used for form submission)
    private String resort;

    // Getters and Setters

    public Boolean getEmailPatrollers() {
        return emailPatrollers;
    }

    public void setEmailPatrollers(Boolean emailPatrollers) {
        this.emailPatrollers = emailPatrollers;
    }

    public Boolean getEmailReminder() {
        return emailReminder;
    }

    public void setEmailReminder(Boolean emailReminder) {
        this.emailReminder = emailReminder;
    }

    public Integer getReminderDays() {
        return reminderDays;
    }

    public void setReminderDays(Integer reminderDays) {
        this.reminderDays = reminderDays;
    }

    public Boolean getEmailAll() {
        return emailAll;
    }

    public void setEmailAll(Boolean emailAll) {
        this.emailAll = emailAll;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone == null ? "" : timeZone.trim();
    }

    public Boolean getOnlyDirectors() {
        return onlyDirectors;
    }

    public void setOnlyDirectors(Boolean onlyDirectors) {
        this.onlyDirectors = onlyDirectors;
    }

    public Integer getRemoveAccess() {
        return removeAccess;
    }

    public void setRemoveAccess(Integer removeAccess) {
        this.removeAccess = removeAccess;
    }

    public Integer getStartDay() {
        return startDay;
    }

    public void setStartDay(Integer startDay) {
        this.startDay = startDay;
    }

    public Integer getStartMonth() {
        return startMonth;
    }

    public void setStartMonth(Integer startMonth) {
        this.startMonth = startMonth;
    }

    public Integer getEndDay() {
        return endDay;
    }

    public void setEndDay(Integer endDay) {
        this.endDay = endDay;
    }

    public Integer getEndMonth() {
        return endMonth;
    }

    public void setEndMonth(Integer endMonth) {
        this.endMonth = endMonth;
    }

    public Boolean getUseBlackOut() {
        return useBlackOut;
    }

    public void setUseBlackOut(Boolean useBlackOut) {
        this.useBlackOut = useBlackOut;
    }

    public Integer getBlackOutStartDay() {
        return blackOutStartDay;
    }

    public void setBlackOutStartDay(Integer blackOutStartDay) {
        this.blackOutStartDay = blackOutStartDay;
    }

    public Integer getBlackOutStartMonth() {
        return blackOutStartMonth;
    }

    public void setBlackOutStartMonth(Integer blackOutStartMonth) {
        this.blackOutStartMonth = blackOutStartMonth;
    }

    public Integer getBlackOutStartYear() {
        return blackOutStartYear;
    }

    public void setBlackOutStartYear(Integer blackOutStartYear) {
        this.blackOutStartYear = blackOutStartYear;
    }

    public Integer getBlackOutEndDay() {
        return blackOutEndDay;
    }

    public void setBlackOutEndDay(Integer blackOutEndDay) {
        this.blackOutEndDay = blackOutEndDay;
    }

    public Integer getBlackOutEndMonth() {
        return blackOutEndMonth;
    }

    public void setBlackOutEndMonth(Integer blackOutEndMonth) {
        this.blackOutEndMonth = blackOutEndMonth;
    }

    public Integer getBlackOutEndYear() {
        return blackOutEndYear;
    }

    public void setBlackOutEndYear(Integer blackOutEndYear) {
        this.blackOutEndYear = blackOutEndYear;
    }

    public String getResort() {
        return resort;
    }

    public void setResort(String resort) {
        this.resort = resort;
    }
}
