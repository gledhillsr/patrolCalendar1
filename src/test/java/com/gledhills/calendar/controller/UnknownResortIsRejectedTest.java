package com.gledhills.calendar.controller;

import com.gledhills.calendar.utils.PatrolData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An unknown resort is a bad URL parameter, and gets a 400 like any other.
 *
 * Every page validates the resort before building PatrolData, because PatrolData cannot make a
 * JDBC url for a resort that is not in the map and calls Thread.dumpStack() when asked to - fifty
 * lines of stack trace in the log for a mistyped query string. Directors and DayShifts were the
 * two that skipped the check; a visitor with an old link to DevilsHead, a resort commented out of
 * resortMap, is what found it.
 *
 * The decommissioned resorts are the reason this is not hypothetical: their links still exist out
 * in the world, on other people's pages and in people's bookmarks.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class UnknownResortIsRejectedTest {

    /** Commented out of resortMap, so its links 404 rather than resolving. */
    private static final String DECOMMISSIONED = "DevilsHead";
    private static final String BROWSER =
        "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 Chrome/139.0.0.0 Safari/537.36";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /** The premise: if somebody re-enables it, this test is telling the truth about the wrong thing. */
    @Test
    void theTestResortIsGenuinelyUnknown() {
        assertFalse(PatrolData.isValidResort(DECOMMISSIONED),
                    DECOMMISSIONED + " is in resortMap again - pick another decommissioned resort");
    }

    /** The request that was actually logged. */
    @Test
    void directorsRejectsAnUnknownResort() throws Exception {
        assertEquals(400, mockMvc.perform(get("/Directors")
                                              .param("resort", DECOMMISSIONED)
                                              .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getStatus(),
                     "an unknown resort should be a plain 400, not a stack trace");
    }

    @Test
    void dayShiftsRejectsAnUnknownResortOnBothVerbs() throws Exception {
        assertEquals(400, mockMvc.perform(get("/DayShifts")
                                              .param("resort", DECOMMISSIONED)
                                              .param("dayOfWeek", "1").param("date", "5")
                                              .param("month", "1").param("year", "2026")
                                              .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getStatus(),
                     "GET should reject it");

        assertEquals(400, mockMvc.perform(post("/DayShifts")
                                              .param("resort", DECOMMISSIONED)
                                              .param("dayOfWeek", "1").param("date", "5")
                                              .param("month", "1").param("year", "2026")
                                              .header("User-Agent", BROWSER))
                             .andReturn().getResponse().getStatus(),
                     "and so should POST - it writes, so it matters more");
    }

    /** A real resort still gets past the check (a 400 for everything would also pass the above). */
    @Test
    void aRealResortIsNotRejected() throws Exception {
        int status = mockMvc.perform(get("/Directors")
                                         .param("resort", "Sample")
                                         .header("User-Agent", BROWSER))
            .andReturn().getResponse().getStatus();

        assertFalse(status == 400, "Sample is a real resort; expected a redirect to login, got 400");
    }
}
