package org.nsponline.calendar.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nsponline.calendar.store.DirectorSettings;
import org.nsponline.calendar.store.Roster;
import org.nsponline.calendar.utils.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot controller for member list.
 * Migrated from MemberList servlet.
 *
 * Handles GET /MemberList - displays all active patrol members
 */
@Controller
public class MemberListController {

    /**
     * Model class for member information
     */
    public static class MemberInfo {
        private String name;
        private String homePhone;
        private String workPhone;
        private String cellPhone;
        private String email;

        public MemberInfo(String name, String homePhone, String workPhone,
                         String cellPhone, String email) {
            this.name = name;
            this.homePhone = homePhone;
            this.workPhone = workPhone;
            this.cellPhone = cellPhone;
            this.email = email;
        }

        // Getters
        public String getName() { return name; }
        public String getHomePhone() { return homePhone; }
        public String getWorkPhone() { return workPhone; }
        public String getCellPhone() { return cellPhone; }
        public String getEmail() { return email; }
    }

    /**
     * Clean field value by removing null, empty, or &nbsp; values
     */
    private String cleanField(String value) {
        if (value == null || value.trim().isEmpty() || "&nbsp;".equals(value.trim())) {
            return "";
        }
        return value.trim();
    }

    @GetMapping("/MemberList")
    public String showMemberList(HttpServletRequest request, HttpServletResponse response,
                                 Model model) throws IOException {
        // Implementation in next steps
        return "member-list";
    }
}
