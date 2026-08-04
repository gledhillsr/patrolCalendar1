package com.gledhills.calendar.dto;

import com.gledhills.calendar.store.Roster;

import java.util.List;
import java.util.Map;

/**
 * One resolved "who is in this list, and which columns does it show" request.
 * Produced by RosterQueryService from the ListPatrollers query parameters, and shared by
 * the ListPatrollers page and the roster download so both always agree on rows and columns.
 *
 * @param members         the members to list, already filtered and in display order
 * @param displayFlags    show* flags naming the columns to display (keys match the template)
 * @param dateRange       startDay/startMonth/startYear/endDay/endMonth/endYear/minDays/useMinDays
 * @param sortOptions     sort1/sort2/sort3 as chosen on the setup page
 * @param classifications classification codes to include (empty means no classification filter)
 * @param commitment      bit flags: 4=full time, 2=part time, 1=inactive
 * @param instructorFlags bit flags: 1=OEC, 2=CPR, 4=Ski, 8=Toboggan
 * @param listAll         list everyone, ignoring the classification/commitment filters
 * @param listDirector    list directors only
 * @param firstNameFirst  render names as "First Last" rather than "Last, First"
 * @param textFontSize    font size the page was asked to render at
 */
public record RosterSelection(
  List<Roster> members,
  Map<String, Boolean> displayFlags,
  Map<String, Integer> dateRange,
  Map<String, String> sortOptions,
  List<String> classifications,
  int commitment,
  int instructorFlags,
  boolean listAll,
  boolean listDirector,
  boolean firstNameFirst,
  int textFontSize) {
}
