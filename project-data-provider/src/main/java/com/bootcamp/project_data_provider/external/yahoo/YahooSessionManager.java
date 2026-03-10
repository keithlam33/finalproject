package com.bootcamp.project_data_provider.external.yahoo;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Auto-refreshes Yahoo cookies + crumb so the app doesn't require manual copying from the browser.
 *
 * How it works:
 * 1) Request finance.yahoo.com to obtain cookies into a CookieManager jar.
 * 2) Request query1.finance.yahoo.com/v1/test/getcrumb using the same jar.
 * 3) Use the jar cookies + crumb for subsequent quote calls.
 */
@Component
public class YahooSessionManager {

  private static final URI FINANCE = URI.create("https://finance.yahoo.com/");
  private static final URI GET_CRUMB = URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb");

  private final String userAgent;
  private final String overrideCrumb;
  private final String overrideCookie;

  private final CookieManager cookieManager;
  private final HttpClient httpClient;

  private volatile String crumb;
  private volatile String cookieHeader;
  private volatile Instant expiresAt;

  public YahooSessionManager(
      @Value("${external-api.yahoo.user-agent}") String userAgent,
      @Value("${external-api.yahoo.crumb:}") String overrideCrumb,
      @Value("${external-api.yahoo.cookie:}") String overrideCookie) {
    this.userAgent = userAgent;
    this.overrideCrumb = overrideCrumb == null ? "" : overrideCrumb.trim();
    this.overrideCookie = overrideCookie == null ? "" : overrideCookie.trim();

    this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    this.httpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public String getCrumb() {
    if (!overrideCrumb.isBlank()) {
      return overrideCrumb;
    }
    ensureFresh();
    return crumb;
  }

  public String getCookieHeader() {
    if (!overrideCookie.isBlank()) {
      return overrideCookie;
    }
    ensureFresh();
    return cookieHeader;
  }

  /**
   * Forces a refresh next time. Useful if a request returns 401/403.
   */
  public void invalidate() {
    expiresAt = Instant.EPOCH;
  }

  private void ensureFresh() {
    Instant now = Instant.now();
    if (expiresAt != null && expiresAt.isAfter(now) && crumb != null && !crumb.isBlank()
        && cookieHeader != null && !cookieHeader.isBlank()) {
      return;
    }
    synchronized (this) {
      now = Instant.now();
      if (expiresAt != null && expiresAt.isAfter(now) && crumb != null && !crumb.isBlank()
          && cookieHeader != null && !cookieHeader.isBlank()) {
        return;
      }
      refresh();
    }
  }

  private void refresh() {
    try {
      // Step 1: Hit finance.yahoo.com to get cookies.
      HttpRequest seed = HttpRequest.newBuilder(FINANCE)
          .timeout(Duration.ofSeconds(20))
          .header("User-Agent", userAgent)
          .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .GET()
          .build();
      httpClient.send(seed, HttpResponse.BodyHandlers.discarding());

      // Step 2: Get crumb with same cookie jar.
      HttpRequest crumbReq = HttpRequest.newBuilder(GET_CRUMB)
          .timeout(Duration.ofSeconds(20))
          .header("User-Agent", userAgent)
          .header("Accept", "*/*")
          .GET()
          .build();
      HttpResponse<String> crumbResp = httpClient.send(crumbReq, HttpResponse.BodyHandlers.ofString());
      String body = crumbResp.body() == null ? "" : crumbResp.body().trim();
      if (crumbResp.statusCode() / 100 != 2 || body.isBlank()) {
        // Still build cookies; some calls may work without crumb.
        this.crumb = "";
      } else {
        this.crumb = body;
      }

      this.cookieHeader = buildCookieHeader(cookieManager.getCookieStore());
      // Refresh every 6 hours by default. If Yahoo expires earlier, the next 401/403 should call invalidate().
      this.expiresAt = Instant.now().plus(Duration.ofHours(6));
    } catch (IOException | InterruptedException e) {
      // Best-effort: keep any existing crumb/cookies, but don't block the app.
      if (this.cookieHeader == null) {
        this.cookieHeader = "";
      }
      if (this.crumb == null) {
        this.crumb = "";
      }
      this.expiresAt = Instant.now().plus(Duration.ofMinutes(10));
    }
  }

  private static String buildCookieHeader(CookieStore store) {
    List<HttpCookie> cookies = store.getCookies();
    if (cookies == null || cookies.isEmpty()) {
      return "";
    }
    // Prefer non-expired cookies first.
    cookies.sort(Comparator.comparing(HttpCookie::hasExpired));
    StringJoiner joiner = new StringJoiner("; ");
    for (HttpCookie c : cookies) {
      if (c == null) {
        continue;
      }
      if (c.hasExpired()) {
        continue;
      }
      // Many Yahoo endpoints accept a broad cookie header; don't over-filter by domain/path here.
      joiner.add(c.getName() + "=" + c.getValue());
    }
    return joiner.toString();
  }
}

