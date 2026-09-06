package com.gledhills.calendar.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

/**
 * Makes a changed stylesheet or script reach the browser that is already holding the old one.
 *
 * <p>{@code spring.web.resources.chain.strategy.content} serves {@code /js} and {@code /css} under
 * a URL containing a hash of the file's contents, so an edited file gets a new URL and no cache
 * can answer for it. That only happens if something rewrites the links, and this filter is what
 * does it - <b>Spring Boot does not register it on its own</b>, so without this bean the property
 * is set, the hashed URLs are served, and every page goes on asking for the unhashed one.
 *
 * <p>This is not housekeeping. {@code shiftTime.js} gained the End Time check while browsers were
 * already holding the copy from the Start Time work; {@code onblur} then called a function that
 * was not there, the failure was silent, the submit check passed the value through, and the only
 * thing that noticed the bad value was the server - which is precisely the "why did I get this at
 * save time?" that this prevents.
 */
@Configuration
public class StaticAssetConfig {

  /**
   * Rewrites {@code @{/js/shiftTime.js}} to {@code /js/shiftTime-<hash>.js} as a page is rendered.
   * Registering it as a plain Filter bean is enough; Boot maps it to every request.
   */
  @Bean
  public ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
    return new ResourceUrlEncodingFilter();
  }
}
