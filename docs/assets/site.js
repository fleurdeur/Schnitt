(function () {
  "use strict";

  const API = "https://api.github.com/repos/fleurdeur/Schnitt/releases/latest";
  const COMMUNITY = "https://schnitt-community.darkest.workers.dev";
  const GITHUB_INDEX = "https://raw.githubusercontent.com/fleurdeur/Schnitt/main/docs/community/data/";
  const root = document.body.dataset.root || "./";
  const isCommunity = document.body.dataset.page === "community";
  const isDownload = document.body.dataset.page === "download";

  const $ = (sel, scope) => (scope || document).querySelector(sel);
  const $$ = (sel, scope) => Array.from((scope || document).querySelectorAll(sel));

  function humanSize(bytes) {
    if (!bytes) return "—";
    if (bytes > 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(bytes > 20 * 1024 * 1024 ? 0 : 1) + " MB";
    return Math.max(1, Math.round(bytes / 1024)) + " KB";
  }

  function esc(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function releaseDate(value) {
    if (!value) return "";
    const date = new Date(value);
    return date.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  }

  let releasePromise;

  function latestRelease() {
    if (releasePromise) return releasePromise;
    releasePromise = fetch(API, { headers: { Accept: "application/vnd.github+json" } })
      .then(r => {
        if (!r.ok) throw new Error("GitHub API " + r.status);
        return r.json();
      })
      .then(data => ({
        tag: data.tag_name,
        name: data.name,
        published: data.published_at,
        notes: data.body || "",
        assets: (data.assets || []).filter(a => /\.apk$/i.test(a.name)).map(a => ({
          name: a.name, size: a.size, url: a.browser_download_url,
        })),
      }))
      .catch(() => fetch(root + "latest.json", { cache: "no-store" }).then(r => r.json()))
      .catch(() => null);
    return releasePromise;
  }

  function versionNumber(release) {
    if (!release || !release.tag) return "5.3.8";
    return String(release.tag).replace(/^v/i, "");
  }

  function chooseApk(release, abi) {
    if (!release || !release.assets || !release.assets.length) return null;
    const assets = release.assets;
    if (abi) {
      const exact = assets.find(a => a.name.toLowerCase().includes(abi.toLowerCase()));
      if (exact) return exact;
    }
    return assets.find(a => /universal/i.test(a.name)) || assets.find(a => /arm64/i.test(a.name)) || assets[0];
  }

  function applyRelease(release) {
    if (!release) return;
    const version = versionNumber(release);
    $$("[data-version]").forEach(el => { el.textContent = "v" + version; });
    $$("[data-version-plain]").forEach(el => { el.textContent = version; });
    $$("[data-release-date]").forEach(el => { el.textContent = releaseDate(release.published); });
    const primary = chooseApk(release);
    if (primary) {
      $$("[data-size]").forEach(el => { el.textContent = humanSize(primary.size); });
    }
    const notes = $("[data-release-notes]");
    if (notes && release.notes) {
      const lines = String(release.notes).split(/\r?\n/).filter(Boolean).slice(0, 9);
      notes.innerHTML = lines.map(line => {
        const clean = line.replace(/^[-*]\s*/, "");
        if (/^Schnitt\s/i.test(clean)) return "";
        return "<p>" + esc(clean) + "</p>";
      }).join("");
    }
    $$("[data-abi]").forEach(link => {
      const asset = chooseApk(release, link.dataset.abi);
      if (asset) {
        link.href = asset.url;
        link.dataset.ready = asset.url;
      }
    });
  }

  function startDownload() {
    const button = $("[data-download]");
    if (button) button.setAttribute("aria-busy", "true");
    latestRelease().then(release => {
      const asset = chooseApk(release, "universal");
      if (!asset) throw new Error("No APK found");
      applyRelease(release);
      const a = document.createElement("a");
      a.href = asset.url;
      a.rel = "noopener";
      a.download = asset.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
    }).catch(() => {
      const fallback = $("[data-fallback-download]");
      if (fallback) location.href = fallback.href;
    }).finally(() => {
      if (button) button.removeAttribute("aria-busy");
    });
  }

  $$("[data-download]").forEach(button => {
    button.addEventListener("click", event => {
      event.preventDefault();
      startDownload();
    });
  });

  $$("[data-year]").forEach(el => { el.textContent = new Date().getFullYear(); });

  if (isDownload) latestRelease().then(applyRelease);

  // Community index ---------------------------------------------------------
  function normalizeGithubEntry(item) {
    const url = item.url || "";
    return {
      id: item.id || url,
      title: item.title || "Community preset",
      author: item.author || "Schnitt community",
      note: item.note || "",
      type: (item.type || "preset").toLowerCase(),
      size: item.size || 0,
      created: item.created || "",
      status: "approved",
      url: /^https?:/i.test(url) ? url : GITHUB_INDEX + url.replace(/^\//, ""),
      source: "archive",
    };
  }

  function normalizeWorkerEntry(item) {
    return {
      id: item.id,
      title: item.title,
      author: item.author,
      note: item.note || "",
      type: item.type || "preset",
      size: item.size || 0,
      created: item.created || "",
      status: item.status || "approved",
      url: COMMUNITY + (item.url || "/api/file/" + item.id),
      source: "live",
    };
  }

  let communityCache = null;

  async function communityIndex() {
    if (communityCache) return communityCache;
    const live = fetch(COMMUNITY + "/api/index", { cache: "no-store" })
      .then(r => r.ok ? r.json() : { entries: [] })
      .then(d => (d.entries || []).map(normalizeWorkerEntry))
      .catch(() => []);
    const archive = fetch(root + "community/data/index.json", { cache: "no-store" })
      .then(r => r.ok ? r.json() : [])
      .then(d => (Array.isArray(d) ? d : []).map(normalizeGithubEntry))
      .catch(() => []);
    const [liveEntries, archiveEntries] = await Promise.all([live, archive]);
    const seen = new Set();
    const merged = [];
    for (const item of liveEntries.concat(archiveEntries)) {
      const key = String(item.title).toLowerCase() + "|" + String(item.author).toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      if (item.status === "approved") merged.push(item);
    }
    merged.sort((a, b) => String(b.created).localeCompare(String(a.created)));
    communityCache = merged;
    return merged;
  }

  function communityCard(item) {
    const kind = item.type === "xml" ? "XML" : "Preset";
    const date = item.created ? releaseDate(item.created) : "Library";
    return `<a class="community-card" href="${esc(item.url)}" target="_blank" rel="noopener">
      <div class="top"><span class="kind">${kind}</span><span class="size">${item.size ? humanSize(item.size) : date}</span></div>
      <h3>${esc(item.title)}</h3>
      <p>by ${esc(item.author)}</p>
      <div class="bottom"><span>${esc(date)}</span><span class="get">Open
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 17 17 7M9 7h8v8"/></svg>
      </span></div>
    </a>`;
  }

  async function renderShelf() {
    const target = $("[data-community-shelf]");
    if (!target) return;
    target.innerHTML = '<div class="shelf-empty">Loading the community shelf…</div>';
    const items = await communityIndex();
    if (!items.length) {
      target.innerHTML = '<div class="shelf-empty">The shelf is empty. Be the first to publish a preset or XML.</div>';
      return;
    }
    target.innerHTML = items.slice(0, 3).map(item => `
      <a class="shelf-card" href="${esc(item.url)}" target="_blank" rel="noopener">
        <span class="type">${item.type === "xml" ? "XML project" : "Preset"}</span>
        <h4>${esc(item.title)}</h4>
        <p>by ${esc(item.author)}</p>
        <span class="foot"><span>${item.size ? humanSize(item.size) : "ready"}</span><span>open ↗</span></span>
      </a>`).join("");
  }

  renderShelf();

  if (isCommunity) {
    let filter = "all";
    let allItems = [];
    const grid = $("[data-community-grid]");
    const count = $("[data-count]");

    function paint() {
      if (!grid) return;
      const rows = allItems.filter(item => filter === "all" || item.type === filter);
      if (count) count.textContent = rows.length + (rows.length === 1 ? " entry" : " entries");
      grid.innerHTML = rows.length ? rows.map(communityCard).join("") : '<div class="shelf-empty" style="grid-column:1/-1">Nothing here yet. Publish the first one.</div>';
    }

    communityIndex().then(items => {
      allItems = items;
      paint();
    }).catch(() => {
      if (grid) grid.innerHTML = '<div class="shelf-empty" style="grid-column:1/-1">The shelf is taking a break. Try again in a moment.</div>';
    });

    $$("[data-filter]").forEach(tab => {
      tab.addEventListener("click", () => {
        filter = tab.dataset.filter;
        $$("[data-filter]").forEach(t => t.classList.toggle("active", t === tab));
        paint();
      });
    });

    // Upload ----------------------------------------------------------------
    const form = $("[data-upload-form]");
    if (form) {
      const drop = $(".dropzone", form);
      const input = $("#community-file", form);
      const pill = $("[data-file-pill]", form);
      const pillName = $("[data-file-name]", form);
      const titleInput = $("#community-title", form);
      const kindInput = $("#community-kind", form);
      const progress = $("[data-progress]", form);
      const progressBar = $("[data-progress-bar]", form);
      const progressText = $("[data-progress-text]", form);
      const result = $("[data-upload-result]", form);
      const submit = $("[data-upload-submit]", form);
      let file = null;

      function setFile(next) {
        file = next;
        if (!file) {
          drop.classList.remove("has-file");
          pill.classList.remove("show");
          return;
        }
        drop.classList.add("has-file");
        pill.classList.add("show");
        pillName.textContent = file.name + " · " + humanSize(file.size);
        if (titleInput && !titleInput.value) {
          titleInput.value = file.name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " ").slice(0, 80);
        }
        if (kindInput) {
          kindInput.value = /\.xml$/i.test(file.name) ? "xml" : "preset";
          $$(".segmented button", form).forEach(b => b.classList.toggle("active", b.dataset.kind === kindInput.value));
        }
      }

      drop.addEventListener("click", event => {
        if (event.target !== input) input.click();
      });
      input.addEventListener("change", () => setFile(input.files && input.files[0]));
      ["dragenter", "dragover"].forEach(type => drop.addEventListener(type, event => {
        event.preventDefault();
        drop.classList.add("dragging");
      }));
      ["dragleave", "drop"].forEach(type => drop.addEventListener(type, event => {
        event.preventDefault();
        drop.classList.remove("dragging");
      }));
      drop.addEventListener("drop", event => {
        const dropped = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
        if (dropped) setFile(dropped);
      });

      $$("[data-kind]", form).forEach(button => {
        button.addEventListener("click", () => {
          kindInput.value = button.dataset.kind;
          $$("[data-kind]", form).forEach(b => b.classList.toggle("active", b === button));
        });
      });

      $("[data-clear-file]", form).addEventListener("click", event => {
        event.preventDefault();
        input.value = "";
        setFile(null);
      });

      form.addEventListener("submit", event => {
        event.preventDefault();
        if (!file) {
          result.className = "upload-result show";
          result.innerHTML = "<b>Choose a file first.</b>Drag an XML, .absproj, ZIP or JSON file into the box.";
          return;
        }
        if (file.size > 8 * 1024 * 1024) {
          result.className = "upload-result show";
          result.innerHTML = "<b>That file is too large.</b>The community limit is 8 MB per entry.";
          return;
        }
        const data = new FormData();
        data.append("file", file);
        data.append("kind", kindInput.value);
        data.append("title", titleInput.value);
        data.append("author", $("#community-author", form).value);
        data.append("note", $("#community-note", form).value);
        submit.setAttribute("aria-busy", "true");
        submit.disabled = true;
        progress.classList.add("show");
        progressBar.style.width = "6%";
        progressText.textContent = "Opening the delivery hatch…";
        result.className = "upload-result";

        const xhr = new XMLHttpRequest();
        xhr.open("POST", COMMUNITY + "/api/submit");
        xhr.upload.addEventListener("progress", event => {
          if (!event.lengthComputable) return;
          const pct = Math.max(6, Math.round((event.loaded / event.total) * 82));
          progressBar.style.width = pct + "%";
          progressText.textContent = "Sending " + humanSize(event.loaded) + " of " + humanSize(event.total);
        });
        xhr.addEventListener("load", () => {
          let body = {};
          try { body = JSON.parse(xhr.responseText || "{}"); } catch (_) {}
          progressBar.style.width = "100%";
          if (xhr.status >= 200 && xhr.status < 300 && body.id) {
            progressText.textContent = "Arrived. Waiting for review.";
            const status = root + "community/status.html?id=" + encodeURIComponent(body.id) + "&receipt=" + encodeURIComponent(body.receipt || "");
            result.className = "upload-result show";
            result.innerHTML = "<b>Upload received.</b>Your entry is in the review queue. Keep this status link:<br><a href=\"" + esc(status) + "\">" + esc(status) + "</a>";
            form.reset();
            input.value = "";
            setFile(null);
          } else {
            progressText.textContent = "Upload did not finish.";
            result.className = "upload-result show";
            result.innerHTML = "<b>Could not publish that.</b>" + esc(body.error || "The delivery hatch is closed right now. Try again.");
          }
        });
        xhr.addEventListener("error", () => {
          progressText.textContent = "Upload did not finish.";
          result.className = "upload-result show";
          result.innerHTML = "<b>Network error.</b>Check your connection and try again.";
        });
        xhr.addEventListener("loadend", () => {
          submit.removeAttribute("aria-busy");
          submit.disabled = false;
        });
        xhr.send(data);
      });
    }
  }

  // Status page -------------------------------------------------------------
  const statusBox = $("[data-status-box]");
  if (statusBox && isCommunity) {
    const params = new URLSearchParams(location.search);
    const id = params.get("id");
    const receipt = params.get("receipt");
    if (!id || !receipt) {
      statusBox.innerHTML = "<p>This status link is incomplete.</p>";
    } else {
      fetch(COMMUNITY + "/api/status/" + encodeURIComponent(id) + "?receipt=" + encodeURIComponent(receipt))
        .then(r => r.ok ? r.json() : Promise.reject(new Error("not found")))
        .then(data => {
          const entry = data.entry;
          const approved = entry.status === "approved";
          statusBox.innerHTML = `<span class="tag ${approved ? "" : "orange"}">${approved ? "Published" : "In review"}</span>
            <h2 style="font-family:var(--display);letter-spacing:-.04em;margin:16px 0 6px">${esc(entry.title)}</h2>
            <p style="color:var(--dim);font-size:12px;margin:0 0 18px">by ${esc(entry.author)} · ${entry.type === "xml" ? "XML project" : "Preset"}</p>
            <div class="file-pill show" style="margin:0 0 16px"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6"/></svg><span>${esc(entry.name || entry.title)}</span></div>
            <p style="color:var(--mid);font-size:12px;line-height:1.6">${approved ? "This entry is live in the community shelf." : "A human checks every upload before it reaches the shelf. This page updates when it is approved."}</p>
            <a class="button button-ghost" style="margin-top:16px" href="${esc(COMMUNITY + "/api/file/" + entry.id + "?receipt=" + encodeURIComponent(receipt))}" target="_blank" rel="noopener">Preview file</a>`;
        })
        .catch(() => { statusBox.innerHTML = "<p>That status link does not match an upload. Check the link and try again.</p>"; });
    }
  }

  // Admin -------------------------------------------------------------------
  const adminForm = $("[data-admin-form]");
  if (adminForm) {
    const input = $("#admin-token");
    const list = $("[data-pending-list]");
    let token = sessionStorage.getItem("schnitt-admin") || "";
    if (token) { input.value = token; loadPending(); }

    async function loadPending() {
      if (!token) return;
      list.innerHTML = '<div class="loading">Reading the queue…</div>';
      try {
        const r = await fetch(COMMUNITY + "/api/admin/pending", { headers: { Authorization: "Bearer " + token } });
        if (!r.ok) throw new Error("Unauthorized");
        const data = await r.json();
        const entries = data.entries || [];
        if (!entries.length) { list.innerHTML = '<div class="loading">Queue is clear.</div>'; return; }
        list.innerHTML = entries.map(entry => `<div class="pending" data-id="${esc(entry.id)}">
          <div><b>${esc(entry.title)}</b><small>${esc(entry.author)} · ${entry.type} · ${humanSize(entry.size)}<br><a style="color:var(--accent)" href="${esc(COMMUNITY + "/api/file/" + entry.id + "?receipt=" + encodeURIComponent(entry.receipt))}" target="_blank" rel="noopener">preview file</a></small></div>
          <div class="actions"><button data-approve>Approve</button><button data-reject>Reject</button></div>
        </div>`).join("");
      } catch (_) {
        list.innerHTML = '<div class="loading">That token was not accepted.</div>';
      }
    }

    adminForm.addEventListener("submit", event => {
      event.preventDefault();
      token = input.value.trim();
      sessionStorage.setItem("schnitt-admin", token);
      loadPending();
    });

    list.addEventListener("click", async event => {
      const approve = event.target.closest("[data-approve]");
      const reject = event.target.closest("[data-reject]");
      if (!approve && !reject) return;
      const row = event.target.closest("[data-id]");
      const id = row.dataset.id;
      const endpoint = approve ? "/api/admin/approve" : "/api/admin/reject";
      const button = approve || reject;
      button.disabled = true;
      await fetch(COMMUNITY + endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: "Bearer " + token },
        body: JSON.stringify({ id }),
      });
      row.remove();
      communityCache = null;
    });
  }
})();
