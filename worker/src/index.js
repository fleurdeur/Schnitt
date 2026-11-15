const MAX_BYTES = 8 * 1024 * 1024;
const ADMIN_HEADER = "authorization";
const ALLOWED = new Set(["xml", "absproj", "zip", "json"]);

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type,Authorization",
  "Access-Control-Max-Age": "86400",
};

function json(body, status = 200, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors, ...extra },
  });
}

function ext(name) {
  return (name.split(".").pop() || "").toLowerCase();
}

function clean(value, max = 80) {
  return String(value || "").replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, max);
}

function mime(name) {
  const e = ext(name);
  if (e === "xml") return "application/xml; charset=utf-8";
  if (e === "zip" || e === "absproj") return "application/zip";
  if (e === "json") return "application/json; charset=utf-8";
  return "application/octet-stream";
}

function isAdmin(request, env) {
  const token = request.headers.get(ADMIN_HEADER) || "";
  return Boolean(env.ADMIN_TOKEN) && token === `Bearer ${env.ADMIN_TOKEN}`;
}

async function readIndex(env) {
  const raw = await env.COMMUNITY_KV.get("index");
  if (!raw) return [];
  try { const value = JSON.parse(raw); return Array.isArray(value) ? value : []; } catch { return []; }
}

async function writeIndex(env, index) {
  await env.COMMUNITY_KV.put("index", JSON.stringify(index.slice(0, 1200)));
}

async function rateLimited(env, request) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const hour = Math.floor(Date.now() / 3600000);
  const key = `rl:${ip}:${hour}`;
  const count = Number(await env.COMMUNITY_KV.get(key) || "0");
  if (count >= 12) return true;
  await env.COMMUNITY_KV.put(key, String(count + 1), { expirationTtl: 7200 });
  return false;
}

async function submit(request, env) {
  if (await rateLimited(env, request)) return json({ error: "Too many uploads. Try again later." }, 429);
  let form;
  try { form = await request.formData(); } catch { return json({ error: "Use the upload form." }, 400); }
  const file = form.get("file");
  if (!(file instanceof File)) return json({ error: "Choose a file first." }, 400);
  const kind = clean(form.get("kind"), 12).toLowerCase() === "xml" ? "xml" : "preset";
  const e = ext(file.name);
  if (!ALLOWED.has(e)) return json({ error: "Use XML, .absproj, ZIP or JSON." }, 400);
  if (file.size <= 0 || file.size > MAX_BYTES) return json({ error: "Files must be between 1 byte and 8 MB." }, 400);
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  const textLike = e === "xml" || e === "json";
  const looksText = head[0] === 0x3c || head[0] === 0x7b || head[0] === 0x5b || (head[0] === 0xef && head[1] === 0xbb && head[2] === 0xbf);
  const looksZip = head[0] === 0x50 && head[1] === 0x4b;
  if (textLike && !looksText) return json({ error: "That file does not look like XML or JSON." }, 400);
  if (!textLike && !looksZip) return json({ error: "That file does not look like a ZIP package." }, 400);

  const id = crypto.randomUUID();
  const receipt = [...crypto.getRandomValues(new Uint8Array(18))].map(b => b.toString(16).padStart(2, "0")).join("");
  const title = clean(form.get("title"), 90) || file.name.replace(/\.[^.]+$/, "");
  const author = clean(form.get("author"), 46) || "Anonymous";
  const note = clean(form.get("note"), 240);
  const now = new Date().toISOString();
  const entry = {
    id, title, author, note, type: kind, name: file.name.slice(0, 120), size: file.size,
    mime: mime(file.name), status: "pending", created: now, receipt,
  };
  await env.COMMUNITY_KV.put(`file:${id}`, await file.arrayBuffer(), { metadata: { name: entry.name, type: entry.mime } });
  await env.COMMUNITY_KV.put(`entry:${id}`, JSON.stringify(entry));
  const index = await readIndex(env);
  index.unshift(entry);
  await writeIndex(env, index);
  return json({ ok: true, id, receipt, status: "pending", entry: publicEntry(entry) }, 201);
}

function publicEntry(e, includeReceipt = false) {
  const out = {
    id: e.id, title: e.title, author: e.author, note: e.note || "", type: e.type,
    size: e.size, status: e.status, created: e.created,
    url: `/api/file/${e.id}`,
  };
  if (includeReceipt) out.receipt = e.receipt;
  return out;
}

async function api(request, env, url) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  const path = url.pathname;

  if (path === "/api/index" && request.method === "GET") {
    const type = url.searchParams.get("type");
    const index = await readIndex(env);
    const entries = index.filter(e => e.status === "approved" && (!type || e.type === type)).map(e => publicEntry(e));
    return json({ entries, updated: new Date().toISOString() }, 200, { "Cache-Control": "public, max-age=30" });
  }

  if (path === "/api/submit" && request.method === "POST") return submit(request, env);

  const file = path.match(/^\/api\/file\/([0-9a-f-]{36})$/i);
  if (file && request.method === "GET") {
    const entry = JSON.parse(await env.COMMUNITY_KV.get(`entry:${file[1]}`) || "null");
    if (!entry) return json({ error: "Not found" }, 404);
    const receipt = url.searchParams.get("receipt") || "";
    if (entry.status !== "approved" && receipt !== entry.receipt) return json({ error: "Not found" }, 404);
    const body = await env.COMMUNITY_KV.get(`file:${file[1]}`, "arrayBuffer");
    if (!body) return json({ error: "Not found" }, 404);
    return new Response(body, {
      headers: {
        "Content-Type": entry.mime || "application/octet-stream",
        "Content-Disposition": `inline; filename="${entry.name.replace(/"/g, "")}"`,
        "Cache-Control": entry.status === "approved" ? "public, max-age=86400" : "no-store",
        ...cors,
      },
    });
  }

  const status = path.match(/^\/api\/status\/([0-9a-f-]{36})$/i);
  if (status && request.method === "GET") {
    const entry = JSON.parse(await env.COMMUNITY_KV.get(`entry:${status[1]}`) || "null");
    if (!entry || (url.searchParams.get("receipt") || "") !== entry.receipt) return json({ error: "Not found" }, 404);
    return json({ entry: publicEntry(entry, true) });
  }

  if (path.startsWith("/api/admin/")) {
    if (!isAdmin(request, env)) return json({ error: "Unauthorized" }, 401);
    if (path === "/api/admin/pending" && request.method === "GET") {
      const index = await readIndex(env);
      return json({ entries: index.filter(e => e.status === "pending").map(e => publicEntry(e, true)) });
    }
    if (path === "/api/admin/approve" && request.method === "POST") {
      const { id } = await request.json().catch(() => ({}));
      const entry = JSON.parse(await env.COMMUNITY_KV.get(`entry:${id}`) || "null");
      if (!entry) return json({ error: "Not found" }, 404);
      entry.status = "approved"; entry.approved = new Date().toISOString();
      await env.COMMUNITY_KV.put(`entry:${id}`, JSON.stringify(entry));
      const index = await readIndex(env);
      const at = index.findIndex(e => e.id === id);
      if (at >= 0) index[at] = entry; else index.unshift(entry);
      await writeIndex(env, index);
      return json({ ok: true, entry: publicEntry(entry) });
    }
    if (path === "/api/admin/reject" && request.method === "POST") {
      const { id } = await request.json().catch(() => ({}));
      const index = (await readIndex(env)).filter(e => e.id !== id);
      await writeIndex(env, index);
      await env.COMMUNITY_KV.delete(`entry:${id}`);
      await env.COMMUNITY_KV.delete(`file:${id}`);
      return json({ ok: true });
    }
    return json({ error: "Not found" }, 404);
  }

  return json({ error: "Not found" }, 404);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) return api(request, env, url);
    return json({ name: "Schnitt community", ok: true });
  },
};
