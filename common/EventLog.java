package common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Thread-safe recorder of synchronization events.
 *
 * <p>Simulation threads call {@link #log} whenever they change state. Every event
 * captures when it happened, who did it, what state they entered, and a snapshot
 * of the shared resource at that moment. After the simulation finishes,
 * {@link #writeHtml} turns the log into one self-contained HTML page that replays
 * the run with Play/Pause/Step/Reset controls and a speed slider.</p>
 *
 * <p>Thread safety: all access to the internal list happens inside methods that
 * synchronize on {@code this}, so concurrent threads can log without data races,
 * and the order of events in the list is the order in which they were logged.</p>
 */
public final class EventLog {

    /**
     * Snapshot of the shared state at the moment an event was logged.
     *
     * @param activeReaders number of readers currently reading (Readers-Writers)
     * @param writerActive  whether a writer currently holds the resource (Readers-Writers)
     * @param forkOwners    for each fork, the name of the philosopher holding it,
     *                      or {@code null} if it is free (Dining Philosophers);
     *                      use an empty array when not applicable
     */
    public record Snapshot(int activeReaders, boolean writerActive, String[] forkOwners) {

        /** Defensive copy so later changes by the caller cannot alter a logged snapshot. */
        public Snapshot {
            forkOwners = forkOwners == null ? new String[0] : forkOwners.clone();
        }

        /** Convenience factory for Readers-Writers snapshots (no forks). */
        public static Snapshot readersWriters(int activeReaders, boolean writerActive) {
            return new Snapshot(activeReaders, writerActive, new String[0]);
        }

        /** Convenience factory for Dining Philosophers snapshots (only forks). */
        public static Snapshot forks(String[] forkOwners) {
            return new Snapshot(0, false, forkOwners);
        }
    }

    /**
     * A single recorded event.
     *
     * @param timeMs      milliseconds since this log was created
     * @param threadName  name of the thread that produced the event
     * @param actorType   kind of actor, e.g. "Reader", "Writer", "Philosopher"
     * @param state       state entered, e.g. WAITING, READING, WRITING, THINKING, HUNGRY, EATING
     * @param description human-readable explanation of what happened
     * @param snapshot    shared state right after the event
     */
    public record Event(long timeMs, String threadName, String actorType, String state,
                        String description, Snapshot snapshot) { }

    /** Wall-clock start time (ms); timestamps are relative to this. */
    private final long startMs = System.currentTimeMillis();

    /** All events in logging order. Guarded by {@code this}. */
    private final List<Event> events = new ArrayList<>();

    /**
     * Records an event for the calling thread.
     *
     * <p>Callers should invoke this while still holding their own lock/semaphore
     * when the snapshot must be consistent with the state change.</p>
     */
    public synchronized void log(String actorType, String state, String description, Snapshot snapshot) {
        long t = System.currentTimeMillis() - startMs;
        events.add(new Event(t, Thread.currentThread().getName(), actorType, state, description, snapshot));
    }

    /** Returns an immutable copy of all events recorded so far. */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * Writes a single self-contained HTML file replaying the logged events.
     *
     * @param path  destination file (parent directories are created if missing)
     * @param title page heading and browser-tab title
     * @throws IOException if the file cannot be written
     */
    public void writeHtml(String path, String title) throws IOException {
        Path out = Path.of(path);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        // Replace the placeholders in the template with the title and the event JSON.
        String html = HTML_TEMPLATE
                .replace("__TITLE__", escapeHtml(title))
                .replace("__EVENTS_JSON__", toJson());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }

    /** Serialises all events as a JSON array (hand-written: no external libraries). */
    private String toJson() {
        List<Event> snapshotOfEvents = events();
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < snapshotOfEvents.size(); i++) {
            Event e = snapshotOfEvents.get(i);
            Snapshot s = e.snapshot() == null ? Snapshot.readersWriters(0, false) : e.snapshot();
            sb.append("{\"t\":").append(e.timeMs())
              .append(",\"thread\":").append(jsonString(e.threadName()))
              .append(",\"actor\":").append(jsonString(e.actorType()))
              .append(",\"state\":").append(jsonString(e.state()))
              .append(",\"desc\":").append(jsonString(e.description()))
              .append(",\"readers\":").append(s.activeReaders())
              .append(",\"writer\":").append(s.writerActive())
              .append(",\"forks\":[");
            String[] forks = s.forkOwners();
            for (int f = 0; f < forks.length; f++) {
                if (f > 0) sb.append(',');
                sb.append(forks[f] == null ? "null" : jsonString(forks[f]));
            }
            sb.append("]}");
            if (i < snapshotOfEvents.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append(']').toString();
    }

    /** Quotes and escapes a string for JSON; also escapes '<' so "</script>" cannot end the script block. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Escapes text for safe insertion into HTML. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The HTML page template. Placeholders __TITLE__ and __EVENTS_JSON__ are filled in by
     * {@link #writeHtml}. Everything (CSS + JS) is inline so the file works offline.
     */
    private static final String HTML_TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
  /* ---- Colour tokens: one colour per state ---- */
  :root {
    --bg:#f6f7f9; --panel:#fff; --text:#1d2330; --muted:#6b7280; --border:#dde1e7;
    --WAITING:#f59e0b; --HUNGRY:#ef4444; --READING:#3b82f6; --WRITING:#8b5cf6;
    --EATING:#10b981; --THINKING:#64748b; --DONE:#9ca3af; --IDLE:#cbd5e1;
    --ACQUIRING_FORKS:#f97316; --RELEASING_FORKS:#06b6d4;
  }
  /* ---- Round table (only shown when the log contains forks) ---- */
  #table-svg { width:100%; max-width:360px; display:block; margin:0 auto 8px; }
  body { margin:0; font-family:system-ui,sans-serif; background:var(--bg); color:var(--text); }
  main { max-width:1100px; margin:0 auto; padding:16px; }
  h1 { font-size:1.4rem; margin:0 0 12px; }
  h2 { font-size:1rem; margin:0 0 8px; }
  section { background:var(--panel); border:1px solid var(--border); border-radius:8px; padding:12px; margin-bottom:12px; }
  /* ---- Controls ---- */
  .controls { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
  button { padding:6px 14px; border:1px solid var(--border); border-radius:6px; background:#fff; cursor:pointer; }
  button:hover { background:#eef2f7; }
  #status { color:var(--muted); font-variant-numeric:tabular-nums; }
  /* ---- Actor cards ---- */
  #actors { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:8px; }
  .card { border-radius:8px; padding:10px; color:#fff; background:var(--IDLE); transition:background .25s; }
  .card .name { font-weight:600; }
  .card .type, .card .desc { font-size:.8rem; opacity:.9; }
  .card.current { outline:3px solid var(--text); }
  /* ---- Shared resource / forks ---- */
  #resource { display:flex; flex-wrap:wrap; gap:12px; }
  .fork, .stat { border:1px solid var(--border); border-radius:6px; padding:8px 12px; min-width:110px; }
  .fork.held { background:#fde68a; }
  .stat b { font-size:1.4rem; display:block; }
  /* ---- Timeline table ---- */
  .table-wrap { max-height:340px; overflow:auto; }
  table { width:100%; border-collapse:collapse; font-size:.85rem; }
  th, td { text-align:left; padding:4px 8px; border-bottom:1px solid var(--border); }
  th { position:sticky; top:0; background:var(--panel); }
  tr.future { opacity:.35; }
  tr.current { background:#fef3c7; }
  .pill { padding:1px 8px; border-radius:10px; color:#fff; font-size:.75rem; }
  /* ---- Legend ---- */
  #legend { display:flex; flex-wrap:wrap; gap:6px; margin-top:8px; }
</style>
</head>
<body>
<main>
  <h1>__TITLE__</h1>

  <!-- Playback controls -->
  <section class="controls">
    <button id="play">Play</button>
    <button id="pause">Pause</button>
    <button id="step">Step</button>
    <button id="reset">Reset</button>
    <label>Speed <input id="speed" type="range" min="1" max="20" value="5"></label>
    <span id="status"></span>
  </section>

  <!-- One card per thread, coloured by current state -->
  <section><h2>Actors</h2><div id="actors"></div><div id="legend"></div></section>

  <!-- Shared resource (readers/writer counters) or fork ownership -->
  <section><h2 id="resource-title">Shared resource</h2>
    <!-- Round table drawn by drawTable() when forks are present -->
    <svg id="table-svg" viewBox="0 0 300 300" hidden></svg>
    <div id="resource"></div></section>

  <!-- Full event timeline; rows not yet replayed are faded -->
  <section><h2>Timeline</h2>
    <div class="table-wrap"><table>
      <thead><tr><th>#</th><th>Time (ms)</th><th>Thread</th><th>Actor</th><th>State</th><th>Description</th></tr></thead>
      <tbody id="timeline"></tbody>
    </table></div>
  </section>
</main>

<script>
// Events recorded by the Java EventLog, embedded at generation time.
const EVENTS = __EVENTS_JSON__;

// Current replay position: number of events applied so far (0 = nothing yet).
let pos = 0;
// Handle for the playback timer, or null when paused.
let timer = null;

// Returns the CSS colour for a state, falling back to a neutral colour.
const colourOf = s => `var(--${s}, var(--IDLE))`;

// Unique threads in order of first appearance, used to build the actor cards.
const actors = [];
EVENTS.forEach(e => { if (!actors.some(a => a.thread === e.thread)) actors.push({ thread: e.thread, actor: e.actor }); });

// Build actor cards once; render() updates their colour and text.
const actorsEl = document.getElementById('actors');
actors.forEach(a => {
  const div = document.createElement('div');
  div.className = 'card';
  div.id = 'card-' + a.thread;
  div.innerHTML = `<div class="name"></div><div class="type"></div><div class="state"></div><div class="desc"></div>`;
  div.querySelector('.name').textContent = a.thread;
  div.querySelector('.type').textContent = a.actor;
  actorsEl.appendChild(div);
});

// Legend of every state that appears in the log.
[...new Set(EVENTS.map(e => e.state))].forEach(s => {
  const span = document.createElement('span');
  span.className = 'pill'; span.style.background = colourOf(s); span.textContent = s;
  document.getElementById('legend').appendChild(span);
});

// Build all timeline rows once; render() only toggles classes.
const tbody = document.getElementById('timeline');
EVENTS.forEach((e, i) => {
  const tr = document.createElement('tr');
  [i + 1, e.t, e.thread, e.actor, null, e.desc].forEach((v, col) => {
    const td = document.createElement('td');
    if (col === 4) {
      const pill = document.createElement('span');
      pill.className = 'pill'; pill.style.background = colourOf(e.state); pill.textContent = e.state;
      td.appendChild(pill);
    } else td.textContent = v;
    tr.appendChild(td);
  });
  tbody.appendChild(tr);
});

// Redraws everything to reflect the first `pos` events.
function render() {
  // Latest event for each thread up to the current position.
  const latest = {};
  for (let i = 0; i < pos; i++) latest[EVENTS[i].thread] = EVENTS[i];
  const cur = pos > 0 ? EVENTS[pos - 1] : null;

  // Actor cards.
  actors.forEach(a => {
    const card = document.getElementById('card-' + a.thread);
    const e = latest[a.thread];
    card.style.background = e ? colourOf(e.state) : 'var(--IDLE)';
    card.querySelector('.state').textContent = e ? e.state : '—';
    card.querySelector('.desc').textContent = e ? e.desc : 'not started';
    card.classList.toggle('current', cur !== null && cur.thread === a.thread);
  });

  // Shared resource panel: forks if the log has any, otherwise readers/writer.
  const res = document.getElementById('resource');
  res.innerHTML = '';
  const hasForks = EVENTS.some(e => e.forks.length > 0);
  document.getElementById('resource-title').textContent = hasForks ? 'Forks' : 'Shared resource';
  if (hasForks) {
    const n = Math.max(...EVENTS.map(e => e.forks.length));
    for (let f = 0; f < n; f++) {
      const owner = cur ? cur.forks[f] : null;
      const div = document.createElement('div');
      div.className = 'fork' + (owner ? ' held' : '');
      div.textContent = `Fork ${f}: ${owner ?? 'free'}`;
      res.appendChild(div);
    }
    drawTable(n, latest, cur);
  } else {
    [['Active readers', cur ? cur.readers : 0], ['Writer active', cur ? (cur.writer ? 'YES' : 'no') : 'no']]
      .forEach(([label, val]) => {
        const div = document.createElement('div');
        div.className = 'stat';
        div.innerHTML = '<b></b><span></span>';
        div.querySelector('b').textContent = val;
        div.querySelector('span').textContent = label;
        res.appendChild(div);
      });
  }

  // Timeline: fade future rows, highlight and scroll to the current one.
  [...tbody.rows].forEach((tr, i) => {
    tr.classList.toggle('future', i >= pos);
    tr.classList.toggle('current', i === pos - 1);
  });
  if (pos > 0) tbody.rows[pos - 1].scrollIntoView({ block: 'nearest' });

  document.getElementById('status').textContent =
    `Event ${pos} / ${EVENTS.length}` + (cur ? ` — t = ${cur.t} ms` : '');
}

// Draws a round table: philosopher i sits at angle i, fork i lies between philosopher i and i+1.
// A held fork is drawn next to its owner and coloured; a free fork sits on the table, grey.
function drawTable(n, latest, cur) {
  const svg = document.getElementById('table-svg');
  svg.hidden = false;
  const NS = 'http://www.w3.org/2000/svg';
  const C = 150;                                   // centre of the 300x300 viewBox
  const el = (tag, attrs, text) => {               // small helper to create SVG elements
    const e = document.createElementNS(NS, tag);
    Object.entries(attrs).forEach(([k, v]) => e.setAttribute(k, v));
    if (text !== undefined) e.textContent = text;
    svg.appendChild(e);
    return e;
  };
  const at = (angle, r) => [C + r * Math.cos(angle), C + r * Math.sin(angle)];
  const seatAngle = i => -Math.PI / 2 + (2 * Math.PI * i) / n;
  // Philosophers are the actors sorted by name, so seat order matches their numbering.
  const seats = actors.map(a => a.thread).sort();
  svg.innerHTML = '';
  el('circle', { cx: C, cy: C, r: 80, fill: '#e7d8c3', stroke: '#b59b78', 'stroke-width': 3 });

  // Forks.
  for (let f = 0; f < n; f++) {
    const owner = cur ? cur.forks[f] : null;
    let angle = seatAngle(f) + Math.PI / n;        // halfway between seat f and seat f+1
    if (owner) {                                   // nudge a held fork towards its owner
      const s = seats.indexOf(owner);
      if (s >= 0) angle = seatAngle(s) + (s === f ? 0.35 : -0.35);
    }
    const [x1, y1] = at(angle, owner ? 70 : 45);
    const [x2, y2] = at(angle, owner ? 98 : 72);
    el('line', { x1, y1, x2, y2, stroke: owner ? '#b45309' : '#9ca3af', 'stroke-width': 6, 'stroke-linecap': 'round' });
    const [tx, ty] = at(seatAngle(f) + Math.PI / n, 30);
    el('text', { x: tx, y: ty, 'text-anchor': 'middle', 'dominant-baseline': 'middle', 'font-size': 10, fill: '#6b7280' }, 'F' + f);
  }

  // Philosophers.
  seats.forEach((name, i) => {
    const e = latest[name];
    const [x, y] = at(seatAngle(i), 125);
    const c = el('circle', { cx: x, cy: y, r: 22, stroke: cur && cur.thread === name ? '#1d2330' : '#fff', 'stroke-width': 3 });
    c.style.fill = e ? colourOf(e.state) : 'var(--IDLE)';
    el('text', { x, y, 'text-anchor': 'middle', 'dominant-baseline': 'middle', 'font-size': 10, fill: '#fff', 'font-weight': 600 },
       name.replace(/^\\D*/, 'P'));
  });
}

// Advances by one event; stops playback at the end.
function step() {
  if (pos < EVENTS.length) { pos++; render(); }
  if (pos >= EVENTS.length) pause();
}

// Starts the playback timer; interval shrinks as the speed slider increases.
function play() {
  pause();
  if (pos >= EVENTS.length) pos = 0;
  const speed = Number(document.getElementById('speed').value);
  timer = setInterval(step, 1000 / speed);
}

// Stops the playback timer.
function pause() { if (timer) { clearInterval(timer); timer = null; } }

// Rewinds to the beginning.
function reset() { pause(); pos = 0; render(); }

// Wire up the controls. Changing speed while playing restarts the timer at the new rate.
document.getElementById('play').onclick = play;
document.getElementById('pause').onclick = pause;
document.getElementById('step').onclick = () => { pause(); step(); };
document.getElementById('reset').onclick = reset;
document.getElementById('speed').oninput = () => { if (timer) play(); };

render();
</script>
</body>
</html>
""";
}
