package com.gledhills.calendar.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable filter criteria for the EmailForm GET request.
 *
 * Component names deliberately match the query parameter names produced by
 * ListPatrollers / MemberList / SubList, so Spring can bind the whole record
 * with a single {@code @ModelAttribute}. Every component is optional; an absent
 * parameter binds to null.
 */
public record EmailFormRequest(
    Boolean EveryBody, Boolean SubList,
    Boolean BAS, Boolean INA, Boolean SR, Boolean SRA, Boolean ALM,
    Boolean PRO, Boolean AUX, Boolean TRA, Boolean CAN, Boolean OTH,
    Boolean FullTime, Boolean PartTime, Boolean Inactive,
    Boolean ALL, Boolean ListDirector,
    Boolean OEC, Boolean CPR, Boolean Ski, Boolean Toboggan,
    Boolean DAY_CNT, Boolean SWING_CNT, Boolean NIGHT_CNT,
    Boolean TRAINING_CNT, Boolean OTHER_CNT, Boolean HOLIDAY_CNT,
    Integer StartDay, Integer StartMonth, Integer StartYear,
    Integer EndDay, Integer EndMonth, Integer EndYear,
    Integer MinDays, Boolean MIN_DAYS) {

  /** Criteria matching every member; used when redisplaying the form after a validation error. */
  public static EmailFormRequest everybody() {
    return new EmailFormRequest(
        true, null,                                                  // EveryBody, SubList
        null, null, null, null, null, null, null, null, null, null,  // classifications
        null, null, null,                                            // commitment
        null, null,                                                  // ALL, ListDirector
        null, null, null, null,                                      // instructor
        null, null, null, null, null, null,                          // shift type counters
        null, null, null, null, null, null,                          // date range
        null, null);                                                 // MinDays, MIN_DAYS
  }

  public boolean isEveryBody() { return isSet(EveryBody); }

  public boolean isSubList() { return isSet(SubList); }

  public boolean isAll() { return isSet(ALL); }

  public boolean isListDirector() { return isSet(ListDirector); }

  /** Classification codes the caller asked for, in Roster's expected order. */
  public List<String> classifications() {
    List<String> classifications = new ArrayList<>();
    if (isSet(BAS)) classifications.add("BAS");
    if (isSet(INA)) classifications.add("INA");
    if (isSet(SR)) classifications.add("SR");
    if (isSet(SRA)) classifications.add("SRA");
    if (isSet(ALM)) classifications.add("ALM");
    if (isSet(PRO)) classifications.add("PRO");
    if (isSet(AUX)) classifications.add("AUX");
    if (isSet(TRA)) classifications.add("TRA");
    if (isSet(CAN)) classifications.add("CAN");
    if (isSet(OTH)) classifications.add("OTH");
    return classifications;
  }

  /** Bit mask expected by {@code Roster.okToDisplay}: 4=full time, 2=part time, 1=inactive. */
  public int commitmentMask() {
    int mask = 0;
    if (isSet(FullTime)) mask += 4;
    if (isSet(PartTime)) mask += 2;
    if (isSet(Inactive)) mask += 1;
    return mask;
  }

  /** Bit mask expected by {@code Roster.okToDisplay}: 1=OEC, 2=CPR, 4=ski, 8=toboggan. */
  public int instructorMask() {
    int mask = 0;
    if (isSet(OEC)) mask += 1;
    if (isSet(CPR)) mask += 2;
    if (isSet(Ski)) mask += 4;
    if (isSet(Toboggan)) mask += 8;
    return mask;
  }

  public boolean hasDateRange() {
    return StartYear != null;
  }

  /** Minimum shifts a member must have worked in the date range, or 0 when not filtering. */
  public int minShiftsThreshold() {
    return (isSet(MIN_DAYS) && MinDays != null) ? MinDays : 0;
  }

  private static boolean isSet(Boolean flag) {
    return Boolean.TRUE.equals(flag);
  }
}
