package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * update-info.html branches on isDirector / isAddPatroller / isSetNewID / isEditPatroller, and
 * combines them with "or". SpEL throws "cannot convert from null to boolean" on a null operand
 * rather than treating it as false, so any render path that leaves one of them out of the model
 * takes the page to a 500.
 *
 * Seven of this controller's thirteen render paths omit isDirector, all of them error re-renders:
 * the add-patroller bounces (missing id, bad phone, duplicate id), the Change ID Number clash, and
 * the "member not found" arms. A @ModelAttribute defaults every flag; these cover the paths that
 * were missing one, which the ordinary save paths - already setting them - never exercised.
 *
 * Read-only: every request here is rejected by validation before anything is written.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class UpdateInfoRendersOnEveryPathTest {

  private static final String MEMBER_ID = "200401";
  private static final String DIRECTOR_ID = "123456";
  private static final String BROWSER =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  /**
   * A director opening Edit_Patroller on an id that is not on this resort's roster. The arm that
   * reports it renders the same form template, which is bound with th:object="${memberForm}" -
   * without one, #fields.hasAnyErrors() throws and the page 500s instead of saying what is wrong.
   */
  @Test
  void editingAnIdThatIsNotOnTheRosterSaysSoRatherThanFailing() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    String page = mockMvc.perform(get("/UpdateInfo")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .param("Purpose", "Edit_Patroller")
                                    .param("EditID", "285985")     //no such member here
                                    .header("User-Agent", BROWSER)
                                    .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("Member not found: 285985"), "it should say so: " + page);
    assertFalse(page.contains("Personal Information for: null"),
                "and the heading should name the id rather than reading null");
  }

  /** Add New Patroller with no id: bounces to a re-render that never set isDirector. */
  @Test
  void addPatrollerWithNoIdRedisplaysRatherThanFailing() throws Exception {
    String page = addPatroller("", "Beetle", "Bailey", "801-555-1212");

    assertTrue(page.contains("ID Number is required"), "the bounce message: " + page);
    assertTrue(page.contains("Personal Information"), "and the form should render");
    assertFalse(page.contains("Personal Information for: null"),
                "with a heading, not a null: " + page);
    assertTrue(page.contains("<option value=\"CAN\">Candidate</option>"),
               "and the director-only fields still editable");
  }

  /** Add New Patroller with a malformed phone: a different bounce, same missing flag. */
  @Test
  void addPatrollerWithABadPhoneRedisplaysRatherThanFailing() throws Exception {
    String page = addPatroller("987654", "Beetle", "Bailey", "5551212");

    assertTrue(page.contains("Personal Information"), "the form should render: " + page);
    assertTrue(page.contains("999-999-9999"), "with the phone format message");
    assertFalse(page.contains("Personal Information for: null"), "and no null heading");
  }

  /** Change ID Number onto an id that is taken: another re-render with no isDirector. */
  @Test
  void changeIdNumberOntoATakenIdRedisplaysRatherThanFailing() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    String page = mockMvc.perform(post("/UpdateInfo")
                                    .param("resort", "Sample")
                                    .param("ID", DIRECTOR_ID)
                                    .param("Purpose", "setNewID")
                                    .param("removeOldID", MEMBER_ID)
                                    .param("idNumber", DIRECTOR_ID)   //already taken
                                    .param("firstName", "Beetle")
                                    .param("lastName", "Bailey")
                                    .header("User-Agent", BROWSER)
                                    .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

    assertTrue(page.contains("already owned by"), "the clash message: " + page);
    assertFalse(page.contains("Personal Information for: null"), "and no null heading");
  }

  /** A member saving their own info with a validation error - a path that always set its flags. */
  @Test
  void aValidationErrorRedisplaysTheFormRatherThanFailing() throws Exception {
    String page = save(MEMBER_ID, MEMBER_ID, "", "Bailey");

    assertTrue(page.contains("Personal Information"), "the form should come back: " + page);
    assertTrue(page.contains("First name is required"), "with the validation message on it");
  }

  @Test
  void aBadEmailAlsoRedisplaysTheForm() throws Exception {
    String page = save(MEMBER_ID, MEMBER_ID, "Beetle", "Bailey", "not-an-email");

    assertTrue(page.contains("Personal Information"), "the form should come back");
    assertTrue(page.contains("Invalid email format"), "with the email message on it");
  }

  /** Same path with a director editing somebody else, which renders the director-only rows. */
  @Test
  void aDirectorEditingSomebodyElseAlsoRedisplays() throws Exception {
    String page = save(DIRECTOR_ID, MEMBER_ID, "", "Bailey");

    assertTrue(page.contains("Personal Information"), "the form should come back: " + page);
  }

  /** A member's own My Info page, which is the one this layout is for. */
  private String myInfoPage() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", MEMBER_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(get("/UpdateInfo").param("resort", "Sample").param("ID", MEMBER_ID)
                             .header("User-Agent", BROWSER).session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String addPatroller(String id, String first, String last, String phone) throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", DIRECTOR_ID);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(post("/UpdateInfo")
                             .param("resort", "Sample")
                             .param("ID", DIRECTOR_ID)
                             .param("Purpose", "Add_Patroller")
                             .param("idNumber", id)
                             .param("firstName", first)
                             .param("lastName", last)
                             .param("homePhone", phone)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  }

  private String save(String loggedInId, String targetId, String first, String last)
      throws Exception {
    return save(loggedInId, targetId, first, last, "");
  }

  private String save(String loggedInId, String targetId, String first, String last, String email)
      throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("loggedInUserId", loggedInId);
    session.setAttribute("loggedInResort", "Sample");

    return mockMvc.perform(post("/UpdateInfo")
                             .param("resort", "Sample")
                             .param("ID", targetId)
                             .param("firstName", first)
                             .param("lastName", last)
                             .param("email", email)
                             .header("User-Agent", BROWSER)
                             .session(session))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();
  }

  /**
   * The layout promise: a member's own details fit in a few rows of fields rather than one page-
   * width table row each, so the page can be read without scrolling through it.
   *
   * <p>Checked structurally rather than by eye - the grid is what makes it compact, and a future
   * edit that drops back to one field per line would pass every other assertion here.
   */
  @Test
  void theFieldsAreLaidOutInAGridRatherThanOnePerRow() throws Exception {
    String page = myInfoPage();

    assertTrue(page.contains("class=\"fields\""), "fields should sit in the grid: " + page);
    assertFalse(page.contains("<table border=\"0\" width=\"900\">"),
                "the 900px one-field-per-row table should be gone");
    assertFalse(page.contains("host resort"),
                "the host resort line is the header's job, not this page's");

    //Every input in the grid is width:100%, so without border-box each is 18px wider than its
    //column - the padding and border are added on top. The inner columns hide that in the gap
    //beside them; the right-hand column has no gap, so its boxes ate the card's right padding and
    //the page looked lopsided. Nothing else here would notice.
    assertTrue(page.contains("box-sizing: border-box"),
               "inputs must be sized border-box or the right-hand column overflows its card");
  }

  /** The phone format is the placeholder now, said once per box instead of beside all three. */
  @Test
  void thePhoneFormatIsShownInTheBoxes() throws Exception {
    String page = myInfoPage();

    assertEquals(3, page.split("placeholder=\"999-999-9999\"", -1).length - 1,
                 "home, work and cell should each carry the format as their placeholder");
  }
}
