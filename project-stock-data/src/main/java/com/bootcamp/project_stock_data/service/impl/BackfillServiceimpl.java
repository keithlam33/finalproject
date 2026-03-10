package com.bootcamp.project_stock_data.service.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bootcamp.project_stock_data.entity.StockEntity;
import com.bootcamp.project_stock_data.repository.StockOhlcRepository;
import com.bootcamp.project_stock_data.repository.StockRepository;
import com.bootcamp.project_stock_data.service.BackfillService;

@Service
public class BackfillServiceimpl implements BackfillService {

  private static final String PY_WORKDIR = "final-project-python";
  private static final String PYTHON_WIN_REL = "bootcamp-env/Scripts/python.exe";
  private static final String PYTHON_FALLBACK = "python";

  private static final String SCRIPT_GET_SYMBOLS = "get_symbols.py";
  private static final String SCRIPT_DAILY = "backfill_daily.py";
  private static final String SCRIPT_INTRADAY = "backfill_intraday.py";

  // Put this env var in your SpringBoot runtime so python connects to same DB.
  private static final String ENV_DB_URL_KEY = "BACKFILL_DB_URL";

  // Tune these
  private static final String DAILY_MISSING_SESSIONS = "180";
  private static final String DAILY_INITIAL_START = "2015-01-01";
  private static final String INTRADAY_SLEEP_SEC = "0.1";

  @Autowired
  private StockRepository stockRepository;

  @Autowired
  private StockOhlcRepository stockOhlcRepository;

  private final AtomicBoolean running = new AtomicBoolean(false);

  @Override
  public void updateSymbols() {
    // Update `stocks` table via python
    runPython(SCRIPT_GET_SYMBOLS, List.of());
  }

  @Override
  public void backfillDailyMissing() {
    List<String> active = loadActiveSymbols();
    if (active.isEmpty())
      return;
    runPython(SCRIPT_DAILY, List.of("--mode", "missing", "--sessions", DAILY_MISSING_SESSIONS), active);
  }

  @Override
  public void backfillIntradayMissing() {
    List<String> active = loadActiveSymbols();
    if (active.isEmpty())
      return;
    runPython(SCRIPT_INTRADAY, List.of("--mode", "missing", "--intervals", "all", "--sleep", INTRADAY_SLEEP_SEC),
        active);
  }

  @Override
  public void checkAndBackfill() {
    // Guard: avoid overlapping long-running processes.
    if (!running.compareAndSet(false, true))
      return;

    try {
      // 1) Must update symbols first so "active" list is correct
      updateSymbols();

      // 2) Load active after update
      List<String> active = loadActiveSymbols();
      if (active.isEmpty())
        return;

      // 3) Detect "new symbols" (no data yet) -> initial only for those
      List<String> newDaily = new ArrayList<>();
      List<String> newIntraday = new ArrayList<>();

      for (String s : active) {
        if (!stockOhlcRepository.existsBySymbolAndDataType(s, "1d"))
          newDaily.add(s);
        if (!stockOhlcRepository.existsBySymbolAndDataType(s, "1m"))
          newIntraday.add(s);
      }

      if (!newDaily.isEmpty()) {
        runPython(
            SCRIPT_DAILY,
            List.of("--mode", "initial", "--start-date", DAILY_INITIAL_START),
            newDaily);
      }

      if (!newIntraday.isEmpty()) {
        runPython(
            SCRIPT_INTRADAY,
            List.of("--mode", "initial", "--intervals", "all", "--sleep", INTRADAY_SLEEP_SEC),
            newIntraday);
      }

      // 4) Maintenance for all active
      backfillDailyMissing();
      backfillIntradayMissing();

    } finally {
      running.set(false);
    }
  }

  private List<String> loadActiveSymbols() {
    List<StockEntity> list = stockRepository.findByStatusTrue();
    List<String> out = new ArrayList<>();
    for (StockEntity s : list) {
      if (s == null || s.getSymbol() == null)
        continue;
      String sym = s.getSymbol().trim();
      if (!sym.isEmpty())
        out.add(sym);
    }
    return out;
  }

  private String pythonExe() {
    try {
      Path exe = Path.of(PY_WORKDIR).resolve(PYTHON_WIN_REL);
      if (Files.exists(exe))
        return exe.toString();
    } catch (Exception ignore) {
    }
    return PYTHON_FALLBACK;
  }

  private void runPython(String scriptName, List<String> fixedArgs) {
    runPython(scriptName, fixedArgs, null);
  }

  private void runPython(String scriptName, List<String> fixedArgs, List<String> symbols) {
    Path tmp = null;
    try {
      List<String> cmd = new ArrayList<>();
      cmd.add(pythonExe());
      cmd.add(scriptName);
      cmd.addAll(fixedArgs);

      if (symbols != null && !symbols.isEmpty()) {
        tmp = Files.createTempFile("backfill-symbols-", ".txt");
        Files.write(tmp, String.join("\n", symbols).getBytes(StandardCharsets.UTF_8));
        cmd.add("--symbols-file");
        cmd.add(tmp.toAbsolutePath().toString());
      }

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.directory(Path.of(PY_WORKDIR).toFile());
      pb.redirectErrorStream(true);

      // Forward DB URL to python (so it uses same DB as SpringBoot)
      String url = System.getenv(ENV_DB_URL_KEY);
      if (url != null && !url.isBlank()) {
        pb.environment().put(ENV_DB_URL_KEY, url);
      }

      Process p = pb.start();
      try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          System.out.println(line);
        }
      }

      int code = p.waitFor();
      if (code != 0)
        throw new IllegalStateException("Backfill failed: script=" + scriptName + " exitCode=" + code);

    } catch (Exception e) {
      throw new RuntimeException("Failed to run backfill script: " + scriptName, e);
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (Exception ignore) {
        }
      }
    }
  }
}
