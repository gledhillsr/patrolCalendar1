package com.gledhills.calendar.store;

import com.gledhills.calendar.utils.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who counts as being on the substitute list: "y" or "yes", either case, and nothing else.
 *
 * It used to be a first-letter test, so any stray value beginning with Y opted a member in. No
 * database needed - this is the rule itself.
 */
class SubstituteListRuleTest {

  @Test
  void yAndYesInAnyCaseAreOnTheList() {
    for (String value : new String[] {"y", "Y", "yes", "YES", "Yes", "yEs", " yes ", "y "}) {
      assertTrue(rosterWithSub(value).isOnSubstituteList(),
                 "[" + value + "] should be on the substitute list");
    }
  }

  @Test
  void anythingElseIsNot() {
    for (String value : new String[] {"n", "N", "no", "", " ", "&nbsp;", "maybe", "0"}) {
      assertFalse(rosterWithSub(value).isOnSubstituteList(),
                  "[" + value + "] should not be on the substitute list");
    }
  }

  /** The point of matching in full: a first-letter test let these through. */
  @Test
  void aStrayValueStartingWithYIsNot() {
    for (String value : new String[] {"yellow", "yup", "yes please", "Y2", "yesterday"}) {
      assertFalse(rosterWithSub(value).isOnSubstituteList(),
                  "[" + value + "] is not an opt-in and must not be treated as one");
    }
  }

  @Test
  void anUnsetFieldIsNot() {
    assertFalse(rosterWithSub(null).isOnSubstituteList(), "null should not be on the list");
  }

  /** A substitute who has gone inactive should not be called for shifts. */
  @Test
  void onlyPartTimeAndFullTimeSubstitutesAreAvailable() {
    assertTrue(substitute("yes", "1").isAvailableSubstitute(false), "part time is available");
    assertTrue(substitute("yes", "2").isAvailableSubstitute(false), "full time is available");
    assertFalse(substitute("yes", "0").isAvailableSubstitute(false), "inactive is not");
    assertFalse(substitute("yes", "3").isAvailableSubstitute(false), "nor an unrecognised commitment");
    assertFalse(substitute("yes", "").isAvailableSubstitute(false), "nor a blank one");
    assertFalse(substitute("no", "2").isAvailableSubstitute(false),
                "and being active is no use without the flag");
  }

  /** Alumni and "Other" do not stand in for shifts, whatever their flag says. */
  @Test
  void alumniAndOtherAreNeverAvailable() {
    for (String code : new String[] {"ALM", "alm", " ALM ", "OTH", "oth"}) {
      assertFalse(substitute("yes", "2", code).isAvailableSubstitute(false),
                  "[" + code + "] should never be an available substitute");
    }
  }

  /** Everything else qualifies, including a code the roster does not recognise. */
  @Test
  void everyOtherClassificationIsAvailable() {
    for (String code : new String[] {"BAS", "SR", "SRA", "AUX", "TRA", "CAN", "INA",
                                     "", "SOMETHING_NEW"}) {
      assertTrue(substitute("yes", "2", code).isAvailableSubstitute(false),
                 "[" + code + "] should be an available substitute");
    }
  }

  /** Pro is the one classification behind a checkbox, and it starts off. */
  @Test
  void proIsAvailableOnlyWhenAskedFor() {
    Roster pro = substitute("yes", "2", "PRO");

    assertFalse(pro.isAvailableSubstitute(false), "Pro is not a substitute by default");
    assertTrue(pro.isAvailableSubstitute(true), "but is when the box is ticked");
  }

  /** The box does not resurrect the classifications that are excluded outright. */
  @Test
  void theProBoxDoesNotPullInAlumniOrOther() {
    for (String code : new String[] {"ALM", "OTH"}) {
      assertFalse(substitute("yes", "2", code).isAvailableSubstitute(true),
                  "[" + code + "] should stay out even with Pro ticked");
    }
  }

  /** okToDisplay's SubList override has to agree, or EmailForm mails a different set. */
  @Test
  void okToDisplayAgreesWithTheRule() {
    for (String sub : new String[] {"yes", "Y", "yellow", "n", ""}) {
      for (String commitment : new String[] {"0", "1", "2", "3", ""}) {
        for (String code : new String[] {"BAS", "ALM", "OTH", "PRO", ""}) {
          for (boolean withPro : new boolean[] {false, true}) {
            Roster member = substitute(sub, commitment, code);
            //PRO=1 reaches okToDisplay as "PRO" in the classification list
            List<String> classes = withPro ? List.of("PRO") : List.of();
            assertTrue(member.isAvailableSubstitute(withPro)
                         == member.okToDisplay(false, true, false, classes, 0, false, 0, 0),
                       "sub=[" + sub + "] commitment=[" + commitment + "] class=[" + code
                         + "] pro=" + withPro + " judged differently by each");
          }
        }
      }
    }
  }

  private Roster rosterWithSub(String value) {
    return substitute(value, "2");
  }

  private Roster substitute(String sub, String commitment) {
    return substitute(sub, commitment, "BAS");
  }

  private Roster substitute(String sub, String commitment, String classification) {
    Roster member = new Roster(new Logger(Roster.class, null, "TEST", "Sample", Logger.ERROR));
    member.setSub(sub);
    member.setCommitment(commitment);
    member.setClassification(classification);
    return member;
  }
}
