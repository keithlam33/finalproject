package com.bootcamp.project_stock_data.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bootcamp.project_stock_data.model.dto.YahooQuoteDTO;
import com.bootcamp.project_stock_data.service.DataProviderService;
import com.bootcamp.project_stock_data.service.OhlcOutputService;
import com.bootcamp.project_stock_data.service.PriceUpdateService;
import com.bootcamp.project_stock_data.service.QuoteCacheService;

@Component
public class AppScheduler {

  private static final ZoneId NY = ZoneId.of("America/New_York");
  private static final LocalTime OPEN = LocalTime.of(9, 30);
  private static final LocalTime CLOSE = LocalTime.of(16, 0);

  // Poll cadence: every 2s; confirm with N samples to avoid network false positives.
  private static final int CONFIRM_N = 5;

  // Base time after open window to start considering "no open today".
  // We will shift this forward by detected delaySec.
  private static final LocalTime BASE_NO_OPEN_CHECK_AFTER = LocalTime.of(10, 0);

  // Extra safety (seconds) added on top of detected delay.
  private static final long EXTRA_GUARD_SEC = 30;

  // Cap delay to avoid crazy values on bad data (e.g. 3 hours).
  private static final long MAX_DELAY_SEC = 60L * 60; // 1 hour

  // How late we still allow finalizeOneMinute/aggregation after 16:00 (delayed feed flush).
  private static final LocalTime FLUSH_UNTIL = LocalTime.of(16, 10);

  @Autowired
  private DataProviderService dataProviderService;

  @Autowired
  private PriceUpdateService priceUpdateService;

  @Autowired
  private OhlcOutputService ohlcOutputService;

  @Autowired
  private QuoteCacheService quoteCacheService;

  // Atomic snapshot (practice + thread-safe without synchronized)
  private final AtomicReference<String> lastState = new AtomicReference<>();
  private final AtomicLong lastMarketTime = new AtomicLong(0L); // epochSec

  // Per-day flags
  private LocalDate nyDate;
  private int regularStreak;
  private int closedStreak;
  private boolean regularConfirmed;
  private boolean noRegularSessionToday;
  private boolean dailyFinalized;

  // Every 2 seconds (fixedDelay): pull provider ONCE, then fan out to Redis + candle builder + session detection
  @Scheduled(fixedDelay = 2000L)
  public void pollQuotesAndDetectSession() {

    // 1) Pull once
    YahooQuoteDTO resp = dataProviderService.getQuotesByActiveSymbols();

    // 2) Update Redis latest quotes (current price / heatmap use-case)
    quoteCacheService.refreshQuotesToRedis(resp);

    // 3) Update 1m in-progress buckets + lastMarketState/Time (candlestick aggregation use-case)
    priceUpdateService.updateQuotes(resp);

    // 4) Session detection logic (weekday-only)
    ZonedDateTime now = ZonedDateTime.now(NY);
    if (!isWeekday(now))
      return;

    // reset per-day state
    if (nyDate == null || !nyDate.equals(now.toLocalDate())) {
      nyDate = now.toLocalDate();
      regularStreak = 0;
      closedStreak = 0;
      regularConfirmed = false;
      noRegularSessionToday = false;
      dailyFinalized = false;
      lastState.set(null);
      lastMarketTime.set(0L);
    }

    String state = priceUpdateService.getLastMarketState();
    Long mt = priceUpdateService.getLastMarketTime();

    if (state != null)
      lastState.set(state);
    if (mt != null)
      lastMarketTime.set(mt);

    // If we still have no state, treat as network issue and do nothing.
    state = lastState.get();
    if (state == null)
      return;

    LocalTime t = now.toLocalTime();
    boolean inRegularWindow = !t.isBefore(OPEN) && t.isBefore(CLOSE);

    // Your rule: only CLOSED contributes to "no open today" / "close confirm".
    // PRE/POST are neutral (ignore) to avoid delayed-feed false negatives.
    if ("REGULAR".equalsIgnoreCase(state)) {
      regularStreak++;
      closedStreak = 0;
    } else if ("CLOSED".equalsIgnoreCase(state)) {
      closedStreak++;
      regularStreak = 0;
    } else {
      return; // PRE/POST/other => ignore
    }

    // Confirm open (only inside regular window)
    if (!regularConfirmed && inRegularWindow && regularStreak >= CONFIRM_N) {
      regularConfirmed = true;
      noRegularSessionToday = false;
    }

    // Dynamic "no open today" check time: BASE + detected delaySec (+ guard)
    LocalTime noOpenAfter = dynamicNoOpenCheckAfter(now);

    if (!regularConfirmed && inRegularWindow && !t.isBefore(noOpenAfter) && closedStreak >= CONFIRM_N) {
      noRegularSessionToday = true;
    }

    // Confirm close (only after we had confirmed open)
    if (regularConfirmed && closedStreak >= CONFIRM_N) {
      regularConfirmed = false;

      if (!dailyFinalized && !noRegularSessionToday) {
        ohlcOutputService.finalizeDaily();
        dailyFinalized = true;
      }
    }
  }

  // Every minute: settle 1m and aggregate other intervals
  // IMPORTANT: aggregation trigger should be based on "data time" (marketTime), not server now.
  @Scheduled(cron = "5 * * * * *", zone = "America/New_York")
  public void settleAndAggregate() {
    ZonedDateTime now = ZonedDateTime.now(NY);
    if (!isWeekday(now))
      return;
    if (noRegularSessionToday)
      return;

    LocalTime t = now.toLocalTime();
    boolean allowedTime = (!t.isBefore(OPEN) && t.isBefore(CLOSE))
        || (!t.isBefore(CLOSE) && t.isBefore(FLUSH_UNTIL));
    if (!allowedTime)
      return;

    if (!regularConfirmed)
      return;

    // Persist any finalized 1m candles
    ohlcOutputService.finalizeOneMinute();

    // Use provider "data time" to decide which interval boundary is reached.
    long dataNow = lastMarketTime.get(); // epochSec
    if (dataNow <= 0)
      return;

    long dataMinuteStart = dataNow - (dataNow % 60);

    if (dataMinuteStart % (5 * 60) == 0)
      ohlcOutputService.aggregateFrom1mAndPersist("5m", 5);
    if (dataMinuteStart % (15 * 60) == 0)
      ohlcOutputService.aggregateFrom1mAndPersist("15m", 15);
    if (dataMinuteStart % (30 * 60) == 0)
      ohlcOutputService.aggregateFrom1mAndPersist("30m", 30);
    if (dataMinuteStart % (60 * 60) == 0)
      ohlcOutputService.aggregateFrom1mAndPersist("1h", 60);
    if (dataMinuteStart % (240 * 60) == 0)
      ohlcOutputService.aggregateFrom1mAndPersist("4h", 240);
  }

  // Hard fallback daily finalize (in case marketState never becomes CLOSED). 16:30 NY.
  @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "America/New_York")
  public void finalizeDailyFallback() {
    ZonedDateTime now = ZonedDateTime.now(NY);
    if (!isWeekday(now))
      return;
    if (noRegularSessionToday)
      return;
    if (dailyFinalized)
      return;

    ohlcOutputService.finalizeDaily();
    dailyFinalized = true;
  }

  private LocalTime dynamicNoOpenCheckAfter(ZonedDateTime nowNy) {
    long nowEpoch = nowNy.toEpochSecond();
    long mt = lastMarketTime.get();
    if (mt <= 0)
      return BASE_NO_OPEN_CHECK_AFTER;

    long delaySec = nowEpoch - mt;
    if (delaySec < 0)
      delaySec = 0;
    if (delaySec > MAX_DELAY_SEC)
      delaySec = MAX_DELAY_SEC;

    return BASE_NO_OPEN_CHECK_AFTER.plusSeconds(delaySec + EXTRA_GUARD_SEC);
  }

  private boolean isWeekday(ZonedDateTime t) {
    DayOfWeek d = t.getDayOfWeek();
    return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
  }
  
}
