package com.gledhills.calendar.dto;

import java.util.List;

/**
 * Immutable DTO for EmailForm query parameters (GET request).
 * Binds filter criteria from calling pages.
 */
public class EmailFormRequest {
    private final String resort;
    private final String id;
    private final Boolean EveryBody;
    private final Boolean SubList;
    private final Boolean BAS;
    private final Boolean INA;
    private final Boolean SR;
    private final Boolean SRA;
    private final Boolean ALM;
    private final Boolean PRO;
    private final Boolean AUX;
    private final Boolean TRA;
    private final Boolean CAN;
    private final Boolean OTH;
    private final Boolean FullTime;
    private final Boolean PartTime;
    private final Boolean Inactive;
    private final Boolean ALL;
    private final Boolean ListDirector;
    private final Boolean OEC;
    private final Boolean CPR;
    private final Boolean Ski;
    private final Boolean Toboggan;
    private final Boolean DAY_CNT;
    private final Boolean SWING_CNT;
    private final Boolean NIGHT_CNT;
    private final Boolean TRAINING_CNT;
    private final Boolean OTHER_CNT;
    private final Boolean HOLIDAY_CNT;
    private final Integer StartDay;
    private final Integer StartMonth;
    private final Integer StartYear;
    private final Integer EndDay;
    private final Integer EndMonth;
    private final Integer EndYear;
    private final Integer MinDays;
    private final Boolean MIN_DAYS;

    public EmailFormRequest(String resort, String id, Boolean everyBody, Boolean subList,
                           Boolean BAS, Boolean INA, Boolean SR, Boolean SRA, Boolean ALM,
                           Boolean PRO, Boolean AUX, Boolean TRA, Boolean CAN, Boolean OTH,
                           Boolean fullTime, Boolean partTime, Boolean inactive,
                           Boolean ALL, Boolean listDirector,
                           Boolean OEC, Boolean CPR, Boolean ski, Boolean toboggan,
                           Boolean DAY_CNT, Boolean SWING_CNT, Boolean NIGHT_CNT,
                           Boolean TRAINING_CNT, Boolean OTHER_CNT, Boolean HOLIDAY_CNT,
                           Integer startDay, Integer startMonth, Integer startYear,
                           Integer endDay, Integer endMonth, Integer endYear,
                           Integer minDays, Boolean MIN_DAYS) {
        this.resort = resort;
        this.id = id;
        this.EveryBody = everyBody;
        this.SubList = subList;
        this.BAS = BAS;
        this.INA = INA;
        this.SR = SR;
        this.SRA = SRA;
        this.ALM = ALM;
        this.PRO = PRO;
        this.AUX = AUX;
        this.TRA = TRA;
        this.CAN = CAN;
        this.OTH = OTH;
        this.FullTime = fullTime;
        this.PartTime = partTime;
        this.Inactive = inactive;
        this.ALL = ALL;
        this.ListDirector = listDirector;
        this.OEC = OEC;
        this.CPR = CPR;
        this.Ski = ski;
        this.Toboggan = toboggan;
        this.DAY_CNT = DAY_CNT;
        this.SWING_CNT = SWING_CNT;
        this.NIGHT_CNT = NIGHT_CNT;
        this.TRAINING_CNT = TRAINING_CNT;
        this.OTHER_CNT = OTHER_CNT;
        this.HOLIDAY_CNT = HOLIDAY_CNT;
        this.StartDay = startDay;
        this.StartMonth = startMonth;
        this.StartYear = startYear;
        this.EndDay = endDay;
        this.EndMonth = endMonth;
        this.EndYear = endYear;
        this.MinDays = minDays;
        this.MIN_DAYS = MIN_DAYS;
    }

    // Getters
    public String getResort() { return resort; }
    public String getId() { return id; }
    public Boolean getEveryBody() { return EveryBody; }
    public Boolean getSubList() { return SubList; }
    public Boolean getBAS() { return BAS; }
    public Boolean getINA() { return INA; }
    public Boolean getSR() { return SR; }
    public Boolean getSRA() { return SRA; }
    public Boolean getALM() { return ALM; }
    public Boolean getPRO() { return PRO; }
    public Boolean getAUX() { return AUX; }
    public Boolean getTRA() { return TRA; }
    public Boolean getCAN() { return CAN; }
    public Boolean getOTH() { return OTH; }
    public Boolean getFullTime() { return FullTime; }
    public Boolean getPartTime() { return PartTime; }
    public Boolean getInactive() { return Inactive; }
    public Boolean getALL() { return ALL; }
    public Boolean getListDirector() { return ListDirector; }
    public Boolean getOEC() { return OEC; }
    public Boolean getCPR() { return CPR; }
    public Boolean getSki() { return Ski; }
    public Boolean getToboggan() { return Toboggan; }
    public Boolean getDAY_CNT() { return DAY_CNT; }
    public Boolean getSWING_CNT() { return SWING_CNT; }
    public Boolean getNIGHT_CNT() { return NIGHT_CNT; }
    public Boolean getTRAINING_CNT() { return TRAINING_CNT; }
    public Boolean getOTHER_CNT() { return OTHER_CNT; }
    public Boolean getHOLIDAY_CNT() { return HOLIDAY_CNT; }
    public Integer getStartDay() { return StartDay; }
    public Integer getStartMonth() { return StartMonth; }
    public Integer getStartYear() { return StartYear; }
    public Integer getEndDay() { return EndDay; }
    public Integer getEndMonth() { return EndMonth; }
    public Integer getEndYear() { return EndYear; }
    public Integer getMinDays() { return MinDays; }
    public Boolean getMIN_DAYS() { return MIN_DAYS; }
}
