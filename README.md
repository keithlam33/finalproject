# Final-Project

database.py — 了解資料表 schema、get_engine() 的用途與 DB 連線方式。

config_intervals.py — 了解 retention policy 與各 interval 的設定。

time_utils.py 與 holidays_utils.py — 時區、工作時段與 Finnhub 市場狀態判斷（排程判斷依據）。

intraday.py 與 daily.py — 抓資料、做資料檢查、寫入主表的實作細節。

archive_job.py（你問的檔案）— 理解資料搬移邏輯、transaction 與 SQL。

scheduler.py 與 main.py — 把整個流程串起來的排程與啟動流程。