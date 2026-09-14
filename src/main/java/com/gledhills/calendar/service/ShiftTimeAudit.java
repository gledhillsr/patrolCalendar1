package com.gledhills.calendar.service;

import com.gledhills.calendar.store.ShiftDefinitions;
import com.gledhills.calendar.utils.ShiftTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which shift templates still have a start or end time nobody can read.
 *
 * <p>This is the short list left after {@link com.gledhills.calendar.ShiftTimeCleanup} has done
 * everything a computer can. That distinction is the point of the whole thing: of the 884 template
 * rows on the site, 231 were wrong and 201 of those the cleanup fixes by itself - a report listing
 * all 884, or even all 231, would bury the handful a person actually has to answer for.
 *
 * <p><b>What lands here is only what needs a human.</b> {@code Close} and {@code end time} are not
 * hours and never were; a blank end is a shift with no finish. Nothing can guess those, so they are
 * shown to the director who knows, next to the template they belong to.
 *
 * <p>A label-only row ({@code mentee} with no times) is listed too, and is the one entry here that
 * may be perfectly correct as it stands - a resort that has always run an untimed slot should leave
 * it alone. It is reported because it cannot sync to anybody's calendar, which is the question this
 * page is helping answer, and saying so is more use than quietly omitting it.
 */
public final class ShiftTimeAudit {

  private ShiftTimeAudit() {
  }

  /**
   * One row that needs attention.
   *
   * @param templateName the template as a director names it, without the {@code _0} position suffix
   * @param position     which row of that template, 1-based, as the editor numbers them
   * @param blocksSync   true when this shift cannot go to a personal calendar at all
   */
  public record Problem(String templateName, int position, String label, String startTime,
                        String endTime, String problem, boolean blocksSync) {
  }

  /**
   * Every template row with an unusable time, worst first.
   *
   * <p>Reads what the page already loaded rather than going back to the database - the caller has
   * the full list in hand, and a second query is a second chance to disagree with what is on
   * screen.
   */
  public static List<Problem> problems(List<ShiftDefinitions> shifts) {
    List<Problem> found = new ArrayList<>();
    if (shifts == null) {
      return found;
    }

    for (ShiftDefinitions shift : shifts) {
      String name = shift.parsedEventName();
      if (name == null || name.isEmpty()) {
        continue;                          //an unnamed row is a different problem, already reported
      }
      String label = shift.getLabel().trim();
      String start = text(shift.getStartString());
      String end = text(shift.getEndString());

      String problem = describe(label, start, end);
      if (problem != null) {
        found.add(new Problem(name, shift.getEventIndex() + 1, label, start, end, problem, true));
      }
    }

    found.sort(Comparator.comparing(Problem::templateName).thenComparingInt(Problem::position));
    return found;
  }

  /**
   * How ready this resort's templates are, as a director would want it summarised.
   *
   * @param total          named template rows - unnamed artifacts are somebody else's problem
   * @param ready          rows that could become a calendar entry today
   * @param needsAttention rows that could not, which is {@link #problems} counted
   */
  public record Readiness(int total, int ready, int needsAttention) {

    /** Nothing left for a director to do here. */
    public boolean isClean() {
      return needsAttention == 0;
    }
  }

  /**
   * The one-line answer to "is my patrol ready to sync?".
   *
   * <p>Counted over the same rows {@link #problems} walks, so the two can never disagree on screen
   * - a summary saying "3 to fix" above a list of five would be worse than no summary at all.
   */
  public static Readiness readiness(List<ShiftDefinitions> shifts) {
    int total = 0;
    if (shifts != null) {
      for (ShiftDefinitions shift : shifts) {
        String name = shift.parsedEventName();
        if (name != null && !name.isEmpty()) {
          total++;
        }
      }
    }
    int needsAttention = problems(shifts).size();
    return new Readiness(total, total - needsAttention, needsAttention);
  }

  /**
   * What is wrong with one row, or null when it is fine.
   *
   * <p>Ordered so the worst thing about a row is the thing it gets listed for. A row with neither a
   * start nor an end has both faults, and saying so once is clearer than listing it twice.
   */
  private static String describe(String label, String start, String end) {
    boolean startUsable = ShiftTime.isTime(start);
    boolean endUsable = ShiftTime.isTime(end);

    if (!startUsable && !endUsable) {
      if (start.isEmpty() && end.isEmpty()) {
        return label.isEmpty() ? "no start or end time, and no label"
                               : "a label only - no start or end time to sync";
      }
      return "neither the start nor the end can be read as a time";
    }
    if (!startUsable) {
      return start.isEmpty() ? "no start time"
                             : "the start \"" + start + "\" cannot be read as a time";
    }
    if (!endUsable) {
      return end.isEmpty() ? "no end time"
                           : "the end \"" + end + "\" cannot be read as a time";
    }
    return null;
  }

  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }
}
