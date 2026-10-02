package com.gledhills.calendar.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * Who this site is: its domain and the addresses that speak for it.
 *
 * These were spread across six files - the from address, the admin address, the backdoor account,
 * the footer's bug-report link - so moving off gledhills.com meant finding all six. They live here
 * instead, and each is separate rather than one shared string, because they do not have to move
 * together: the FROM address is the one that has to be DKIM-signed by whoever sends the mail, while
 * SUPPORT is only ever somewhere a person replies to.
 *
 * <b>Changing the domain is not just this file.</b> The from address has to be SPF/DKIM aligned with
 * whatever account actually sends the batch, or a bulk send lands in spam - and the failure summary
 * that would tell you goes out over the same broken path. Set the DNS up first.
 *
 * Templates read these off the Spring bean: <code>${{@site.supportEmail}}</code>. The constants stay
 * static so DailyReminder, which runs with no Spring context at all, can still use them.
 */
@Component("site")
public class SiteIdentity {

  /** The domain the application itself is served from. */
  public static final String DOMAIN = "gledhills.com";

  /** Where a member is told to report bugs, and the address the backdoor account carries. */
  public static final String SUPPORT_EMAIL = "steve@" + DOMAIN;

  /**
   * The from address for mail a resort has no verified sender of its own for. This one is the
   * deliverability-critical address - see the class note before pointing it at a new domain.
   */
  public static final String FROM_EMAIL = "steve@" + DOMAIN;

  /** Where a batch that did not go out cleanly is reported, alongside the director who sent it. */
  public static final String ADMIN_EMAIL = "steve@" + DOMAIN;

  /**
   * Where resort-specific static pages and PHP apps are hosted - Brighton's training tracker and
   * its donation PDF. Separate from {@link #DOMAIN} on purpose: the application can move to a new
   * name while that content stays where its links have always pointed.
   */
  public static final String HOSTED_CONTENT_BASE = "http://" + DOMAIN;

  /**
   * The second name this site answers on. Both domains point at the same application, and this one
   * is deliberately <b>not</b> an identity anything is sent <i>from</i> - no mail, no links, nothing
   * SPF/DKIM aligned - so it sits beside {@link #DOMAIN} rather than replacing it. It exists only so
   * a visitor who typed it is told they arrived in the right place.
   */
  public static final String PATROL_CALENDAR_DOMAIN = "patrolcalendar.com";

  /** The same name in the branded casing a visitor is shown. */
  public static final String PATROL_CALENDAR_DISPLAY = "PatrolCalendar.com";

  //instance getters so a Thymeleaf template can ask: ${@site.supportEmail}
  public String getDomain() { return DOMAIN; }

  public String getSupportEmail() { return SUPPORT_EMAIL; }

  public String getFromEmail() { return FROM_EMAIL; }

  public String getAdminEmail() { return ADMIN_EMAIL; }

  public String getHostedContentBase() { return HOSTED_CONTENT_BASE; }

  public String getPatrolCalendarDisplay() { return PATROL_CALENDAR_DISPLAY; }

  /**
   * The second domain, for the one link that is deliberately built on it: the "try the Sample
   * resort" link on New Features, which is handed to people who are being shown the site.
   *
   * <p>Everything else still points at {@link #DOMAIN} - this is not a rename, and nothing is sent
   * from this name. It is a getter rather than a literal in the template because
   * SiteIdentityTest scans the templates for the domain written out by hand, which is how a
   * rename leaves half a site pointing at the old name.
   */
  public String getPatrolCalendarDomain() { return PATROL_CALENDAR_DOMAIN; }

  /**
   * Whether the request being served came in on {@link #PATROL_CALENDAR_DOMAIN}.
   *
   * Read off the request in flight rather than passed in, because every page draws the header and
   * threading a flag through twenty controllers means the twenty-first forgets - the same reason
   * the header asks MenuButtonService for its buttons directly.
   *
   * Never throws. Asked with no request in flight - DailyReminder, a unit test - it answers false,
   * which draws no banner.
   */
  public boolean isPatrolCalendarHost() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
      return false;
    }
    return isPatrolCalendarHost(servletAttributes.getRequest().getServerName());
  }

  /** The matching itself, package-private so it can be tested without a request. */
  static boolean isPatrolCalendarHost(String serverName) {
    return matches(serverName, PATROL_CALENDAR_DOMAIN);
  }

  /**
   * Whether the request being served came in on {@link #DOMAIN}, the original name.
   *
   * <p>The header asks so it can nudge that visitor towards the new one. Deliberately <b>not</b>
   * the negation of {@link #isPatrolCalendarHost}: a request on {@code localhost}, or none at all
   * (DailyReminder, a unit test), is neither, and gets neither line. Nudging a developer on
   * localhost to change their URL would be noise, and every render test and golden would have
   * grown a line that no real visitor sees.
   *
   * <p>Never throws, for the same reason its sibling does not - this is drawn on every page.
   */
  public boolean isPrimaryDomainHost() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
      return false;
    }
    return isPrimaryDomainHost(servletAttributes.getRequest().getServerName());
  }

  /** The matching itself, package-private so it can be tested without a request. */
  static boolean isPrimaryDomainHost(String serverName) {
    return matches(serverName, DOMAIN);
  }

  /**
   * The domain itself or one of its subdomains - {@code www.} is the same site.
   *
   * <p>Not an {@code endsWith}: that would greet {@code notpatrolcalendar.com} as if it were us.
   */
  private static boolean matches(String serverName, String domain) {
    if (serverName == null) {
      return false;
    }
    String host = serverName.toLowerCase(Locale.ROOT);
    return host.equals(domain) || host.endsWith("." + domain);
  }
}
