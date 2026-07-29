package com.gledhills.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.gledhills.calendar.dto.EmailFormRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for EmailFormController.
 * Uses the Sample resort, where sending is disabled, so no mail is ever handed to SMTP.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
class EmailFormControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        session = new MockHttpSession();
        session.setAttribute("loggedInUserId", "123456");
        session.setAttribute("loggedInResort", "Sample");
    }

    @Test
    void getEmailForm_withoutSession_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/EmailForm")
                .param("resort", "Sample")
                .param("ID", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String redirectUrl = result.getResponse().getRedirectedUrl();
                    assert redirectUrl != null && redirectUrl.contains("MemberLogin")
                        : "Expected redirect to MemberLogin, got: " + redirectUrl;
                });
    }

    @Test
    void getEmailForm_withUnknownResort_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/EmailForm")
                .param("resort", "NoSuchResort")
                .param("ID", "123456")
                .session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getEmailForm_bindsFilterParametersFromQueryString() throws Exception {
        mockMvc.perform(get("/EmailForm")
                .param("resort", "Sample")
                .param("ID", "123456")
                .param("BAS", "1")
                .param("SR", "1")
                .param("FullTime", "1")
                .param("ALL", "1")
                .param("MIN_DAYS", "1")
                .param("MinDays", "2")
                .param("DAY_CNT", "1")
                .param("StartDay", "1").param("StartMonth", "10").param("StartYear", "2025")
                .param("EndDay", "30").param("EndMonth", "3").param("EndYear", "2026")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("email-form"))
                .andExpect(content().string(containsString("Email Composition Form")))
                .andExpect(result -> {
                    EmailFormRequest bound = (EmailFormRequest)
                        result.getModelAndView().getModel().get("emailFormRequest");
                    assertNotNull(bound, "filter criteria should bind from the query string");
                    assertEquals(List.of("BAS", "SR"), bound.classifications());
                    assertEquals(4, bound.commitmentMask());
                    assertTrue(bound.isAll());
                    assertEquals(2, bound.minShiftsThreshold());
                    assertTrue(bound.hasDateRange());
                    assertEquals(2025, bound.StartYear());
                });
    }

    @Test
    void getEmailForm_withoutFilters_showsFormAndSelectableRecipients() throws Exception {
        mockMvc.perform(get("/EmailForm")
                .param("resort", "Sample")
                .param("ID", "123456")
                .param("EveryBody", "1")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("email-form"))
                .andExpect(model().attributeExists("validRecipients", "invalidRecipients", "compositionForm"))
                // shared head fragment supplies the stylesheet and title
                .andExpect(content().string(containsString("css/styles.css")))
                .andExpect(content().string(containsString("<title>Email Composition - ")))
                // the dead filter hidden fields are gone
                .andExpect(content().string(not(containsString("name=\"MIN_DAYS\""))));
    }

    @Test
    void postEmailForm_forSampleResort_isRejectedAndRedisplaysForm() throws Exception {
        mockMvc.perform(post("/EmailForm")
                .param("resort", "Sample")
                .param("fromPatrollerId", "123456")
                .param("subject", "Test subject")
                .param("message", "Test message body")
                .param("patrollerIds", "123456")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("email-form"))
                .andExpect(model().attributeExists("validRecipients"))
                .andExpect(content().string(containsString("Email sending is disabled in the Sample resort")));
    }

    @Test
    void postEmailForm_withValidationErrors_redisplaysFormWithMessages() throws Exception {
        mockMvc.perform(post("/EmailForm")
                .param("resort", "Sample")
                .param("fromPatrollerId", "123456")
                .param("subject", "")
                .param("message", "ab")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("email-form"))
                .andExpect(content().string(containsString("Subject is required")))
                .andExpect(content().string(containsString("At least one recipient must be selected")));
    }

    @Test
    void getEmailForm_offersAttachmentInputWithTheLimit() throws Exception {
        mockMvc.perform(get("/EmailForm")
                .param("resort", "Sample")
                .param("ID", "123456")
                .param("EveryBody", "1")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("enctype=\"multipart/form-data\"")))
                .andExpect(content().string(containsString("name=\"attachments\"")))
                .andExpect(content().string(containsString("must be 2.0 MB or less")));
    }

    @Test
    void postEmailForm_withAttachmentsOverTwoMegabytes_showsError() throws Exception {
        MockMultipartFile big1 = new MockMultipartFile(
            "attachments", "trail-map.pdf", "application/pdf", new byte[1_200_000]);
        MockMultipartFile big2 = new MockMultipartFile(
            "attachments", "roster.xlsx", "application/vnd.ms-excel", new byte[1_000_000]);

        mockMvc.perform(multipart("/EmailForm")
                .file(big1)
                .file(big2)
                .param("resort", "Sample")
                .param("fromPatrollerId", "123456")
                .param("subject", "Test subject")
                .param("message", "Test message body")
                .param("patrollerIds", "123456")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("email-form"))
                .andExpect(content().string(containsString("Attachments total 2.1 MB")))
                .andExpect(content().string(containsString("the limit is 2.0 MB")))
                .andExpect(content().string(containsString("trail-map.pdf (1.1 MB)")));
    }

    @Test
    void postEmailForm_withAttachmentsUnderTheLimit_isAccepted() throws Exception {
        MockMultipartFile small = new MockMultipartFile(
            "attachments", "notes.txt", "text/plain", "hello".getBytes());

        // Sample rejects the send itself, so reaching that message means the size check passed
        mockMvc.perform(multipart("/EmailForm")
                .file(small)
                .param("resort", "Sample")
                .param("fromPatrollerId", "123456")
                .param("subject", "Test subject")
                .param("message", "Test message body")
                .param("patrollerIds", "123456")
                .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email sending is disabled in the Sample resort")))
                // the phrase only the server-side overage message produces
                .andExpect(content().string(not(containsString("the limit is 2.0 MB"))));
    }

    @Test
    void postEmailForm_withoutSession_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/EmailForm")
                .param("resort", "Sample")
                .param("fromPatrollerId", "123456")
                .param("subject", "Test subject")
                .param("message", "Test message body")
                .param("patrollerIds", "123456"))
                .andExpect(status().is3xxRedirection());
    }
}
