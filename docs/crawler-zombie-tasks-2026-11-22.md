# Crawler tasks that ran but crawled nothing (2026-11-22)

## Symptom

Crawl health showed 104 of 142 boards failing, almost all with "robots.txt is unavailable; RFC
9309 treats that as disallow-all".

- The failures began on Nov 20: 30 that morning, then 900 to 1,000 every 12 hours from Nov 21.
  Before that it was 1 to 3 a day.
- No crawl completed in the 3 hours checked.
- ECS showed every task RUNNING and steady.

## What it was not

- **Not the sites or the network.** A one-off Fargate task in the crawlers' own subnets and
  security group (`jbi-probe`, deregistered afterwards) fetched all 21 failing robots.txt files in
  under 2 s each. None returned a 5xx: 200, 302 (followed by the client), or 401/403/404 (which
  mean "no rules" under RFC 9309, as `RobotsCache` already handles).
- **Not every task.** Tasks started at 12:09 UTC ran 28 minutes with no robots failures. One task
  started on Nov 21 logged 1,743 failures and another 995.

## Cause

1. **The groups change (Nov 19, `7b32760`) put every group of a Deployment into one pipeline**,
   so five sink workers per task became one. But the renderer kept the buffer at 4,096 records
   per group (20,480 for five groups).
2. **Each crawl worker holds a whole board's records before writing them**, and the light
   Deployment has 14 workers. With one sink draining, the buffer stayed full. Each 30 s
   `writeAll` timeout then failed the whole board and queued it to be crawled again, which kept
   the buffer full.
3. **The heap stayed at its cap.** Task memory repeatedly peaked at 78%, which is
   `MaxRAMPercentage=70` plus JVM overhead.
4. **An OutOfMemoryError then landed on the JDK HTTP client's selector thread.** The client
   catches it, logs it only at debug level, and stops. Every later request on that client fails
   with `selector manager closed`.
5. **The task kept running.** The AWS task definition had lost `-XX:+ExitOnOutOfMemoryError`,
   which the local stacks have always set. ECS saw a healthy task, and its boards stayed leased
   to it.
6. **The error was hidden.** robots.txt is the first request of each crawl, and `RobotsCache`
   discarded the exception, so every board reported the generic robots message. Memory fell to
   about 40% after the client died, because the task had stopped fetching.

## Fixes

| Link in the chain | Fix |
| --- | --- |
| Buffer sized per group behind one sink | `buffer_size: 4096` per pipeline (`config/render_groups.py`) |
| A full buffer failed and re-crawled boards | `writePatiently`: wait up to 10 minutes (`BUFFER_WRITE_PATIENCE`, well inside the 1 h acknowledgement timeout), logging while it waits |
| A dead HTTP client is permanent | `JdkHttpTransport` builds clients from a factory. The first request that sees `selector manager closed` replaces the client and retries once |
| An OOM leaves a running zombie | `-XX:+ExitOnOutOfMemoryError` in the crawler task definition. ECS replaces the task and its leases move to the other tasks |
| The cause was invisible | robots.txt failures carry the cause, e.g. `(IOException: selector manager closed)` or `(HTTP 503)` |

Tests: `JdkHttpTransportTest` (a dead client is replaced once; an ordinary network error keeps
the client), `FetchLayerTest` (the reason names the cause) and `JobBoardCrawlerTest` (a full
buffer waits for the sink rather than failing the board).

## Still open

- **No liveness signal.** A task that fails every crawl for a reason other than OOM would still
  look healthy to ECS. Two candidates: a container health check backed by the crawler's recent
  success rate, and a CloudWatch alarm on the failed-to-complete crawl ratio from the logs.
- **Whole-board buffering.** Each worker still holds a full board in memory before writing.
  Streaming page by page would bound memory by page size, not board size. That matters for the
  largest boards: CVS has 18,000 jobs and Workday serves up to 50,000.
- **"Failed to acquire partition".** Data Prepper logs about 3,500 to 4,000 of these every 2
  hours at ERROR. They look like the two tasks racing for the same partition, which is normal
  under optimistic locking. Not yet confirmed.
