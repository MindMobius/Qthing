import "./style.css";
import { EditorState, Compartment } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { markdown } from "@codemirror/lang-markdown";
import { basicSetup } from "codemirror";

function debounce(fn, waitMs) {
  let t = null;
  return function (...args) {
    if (t) clearTimeout(t);
    t = setTimeout(() => fn.apply(this, args), waitMs);
  };
}

function icon(kind) {
  if (kind === "folder") return "▸";
  if (kind === "folderOpen") return "▾";
  if (kind === "file") return "·";
  return "·";
}

function clampName(name) {
  return (name || "").trim() || "未命名.md";
}

function normalizeMdName(raw) {
  const t = (raw || "").trim();
  if (!t) return "未命名.md";
  return t.toLowerCase().endsWith(".md") ? t : `${t}.md`;
}

function rpc(method, params) {
  const bridge = window.AndroidFs;
  if (!bridge || typeof bridge.call !== "function") {
    throw new Error("AndroidFs 不可用");
  }
  const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const payload = JSON.stringify({ id, method, params: params || {} });
  const raw = bridge.call(payload);
  const res = JSON.parse(raw || "{}");
  if (!res || res.id !== id) throw new Error("桥接响应异常");
  if (!res.ok) throw new Error(res.message || "操作失败");
  return res.result;
}

const root = document.getElementById("root");
const bootEl = document.getElementById("boot");
function setBoot(message) {
  if (!bootEl) return;
  bootEl.style.display = "grid";
  bootEl.textContent = message || "正在加载…";
}

function hideBoot() {
  if (!bootEl) return;
  bootEl.style.display = "none";
}

window.addEventListener("error", (e) => {
  setBoot(e && e.message ? `启动失败：${e.message}` : "启动失败");
});

window.addEventListener("unhandledrejection", (e) => {
  const reason = e && e.reason;
  const msg = reason && reason.message ? reason.message : String(reason || "");
  setBoot(msg ? `启动失败：${msg}` : "启动失败");
});
const appEl = document.createElement("div");
appEl.className = "app";
root.appendChild(appEl);

const sidebarEl = document.createElement("div");
sidebarEl.className = "sidebar";
appEl.appendChild(sidebarEl);

const sidebarOverlay = document.createElement("div");
sidebarOverlay.className = "sidebarOverlay";
appEl.appendChild(sidebarOverlay);

function toggleSidebar(force) {
  const isOpen = sidebarEl.dataset.open === "true";
  const next = force !== undefined ? force : !isOpen;
  sidebarEl.dataset.open = String(next);
  sidebarOverlay.dataset.open = String(next);
}

sidebarOverlay.addEventListener("click", () => toggleSidebar(false));

const sidebarHeaderEl = document.createElement("div");
sidebarHeaderEl.className = "sidebarHeader";
sidebarEl.appendChild(sidebarHeaderEl);

const searchInput = document.createElement("input");
searchInput.className = "search";
searchInput.placeholder = "搜索文件…";
sidebarHeaderEl.appendChild(searchInput);

const treeEl = document.createElement("div");
treeEl.className = "tree";
sidebarEl.appendChild(treeEl);

const mainEl = document.createElement("div");
mainEl.className = "main";
appEl.appendChild(mainEl);

const topbarEl = document.createElement("div");
topbarEl.className = "topbar";
mainEl.appendChild(topbarEl);

const btnSidebar = document.createElement("button");
btnSidebar.className = "btn";
btnSidebar.textContent = "☰";
btnSidebar.style.marginRight = "10px";
// 只在移动端显示，PC 端隐藏（通过 CSS 控制，或者 JS 判断宽度）
// 这里为了简单，JS 动态控制显示：
function checkSidebarBtn() {
  if (window.innerWidth <= 768) {
    btnSidebar.style.display = "block";
  } else {
    btnSidebar.style.display = "none";
    toggleSidebar(false); // PC 模式重置
  }
}
window.addEventListener("resize", checkSidebarBtn);
checkSidebarBtn();

btnSidebar.addEventListener("click", () => toggleSidebar());
topbarEl.appendChild(btnSidebar);

const titleBlockEl = document.createElement("div");
titleBlockEl.className = "titleBlock";
topbarEl.appendChild(titleBlockEl);

const titleEl = document.createElement("div");
titleEl.className = "title";
titleEl.textContent = "加载中…";
titleBlockEl.appendChild(titleEl);

const statusEl = document.createElement("div");
statusEl.className = "status";
statusEl.textContent = "";
titleBlockEl.appendChild(statusEl);

const actionsEl = document.createElement("div");
actionsEl.className = "actions";
topbarEl.appendChild(actionsEl);

function makeBtn(label, kind) {
  const b = document.createElement("button");
  b.className = `btn${kind === "primary" ? " btnPrimary" : ""}`;
  b.type = "button";
  b.textContent = label;
  actionsEl.appendChild(b);
  return b;
}

const btnNew = makeBtn("新建", "primary");
const btnRename = makeBtn("重命名");
const btnDelete = makeBtn("删除");
const btnRefresh = makeBtn("刷新");
const btnRePick = makeBtn("换目录");

const editorShell = document.createElement("div");
editorShell.className = "editorShell";
mainEl.appendChild(editorShell);

const themeCompartment = new Compartment();

const lightTheme = EditorView.theme(
  {
    "&": {
      height: "100%",
      backgroundColor: "var(--bg)",
      color: "var(--fg)"
    },
    ".cm-scroller": {
      backgroundColor: "var(--bg)"
    },
    ".cm-content": {
      padding: "12px 12px 24px",
      color: "var(--fg)",
      fontSize: "16px",
      lineHeight: "1.55",
      fontFamily:
        'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace'
    },
    ".cm-gutters": {
      backgroundColor: "var(--bg)",
      color: "var(--fg)",
      borderRight: "1px solid var(--gutter)"
    },
    ".cm-cursor": {
      borderLeftColor: "var(--caret) !important"
    },
    ".cm-selectionBackground, .cm-content ::selection": {
      backgroundColor: "var(--selection) !important"
    }
  },
  { dark: false }
);

const darkTheme = EditorView.theme(
  {
    "&": {
      height: "100%",
      backgroundColor: "var(--bg)",
      color: "var(--fg)"
    },
    ".cm-scroller": {
      backgroundColor: "var(--bg)"
    },
    ".cm-content": {
      padding: "12px 12px 24px",
      color: "var(--fg)",
      fontSize: "16px",
      lineHeight: "1.55",
      fontFamily:
        'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace'
    },
    ".cm-gutters": {
      backgroundColor: "var(--bg)",
      color: "var(--fg)",
      borderRight: "1px solid var(--gutter)"
    },
    ".cm-cursor": {
      borderLeftColor: "var(--caret) !important"
    },
    ".cm-selectionBackground, .cm-content ::selection": {
      backgroundColor: "var(--selection) !important"
    }
  },
  { dark: true }
);

let ignoreChange = false;
let dirty = false;
let saving = false;
let saveError = null;
let rootUri = null;
let currentFileUri = null;
let currentFileName = null;
let treeCache = new Map();
let expandedDirs = new Set();
let selectedUri = null;
let lastSavedText = "";
let saveTicket = 0;

function getText(view) {
  return view.state.doc.toString();
}

function replaceAll(view, text) {
  view.dispatch({
    changes: { from: 0, to: view.state.doc.length, insert: text ?? "" }
  });
}

function setStatus() {
  if (saveError) {
    statusEl.textContent = saveError;
    return;
  }
  if (saving) {
    statusEl.textContent = "保存中…";
    return;
  }
  if (dirty) {
    statusEl.textContent = "编辑中…";
    return;
  }
  statusEl.textContent = "已保存";
}

function setTitle() {
  titleEl.textContent = currentFileName ? clampName(currentFileName) : "未选择文件";
}

async function ensureDirLoaded(dirUri) {
  if (!dirUri) return [];
  if (treeCache.has(dirUri)) return treeCache.get(dirUri);
  const items = rpc("listChildren", { uri: dirUri }) || [];
  const normalized =
    items
      .map((it) => ({
        uri: it.uri,
        name: it.name,
        isDir: !!it.isDir
      }))
      .sort((a, b) => {
        if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
        return (a.name || "").localeCompare(b.name || "", "zh-CN", { sensitivity: "base" });
      }) || [];
  treeCache.set(dirUri, normalized);
  return normalized;
}

function clearTreeCache() {
  treeCache = new Map();
}

function matchQuery(name, query) {
  if (!query) return true;
  return (name || "").toLowerCase().includes(query.toLowerCase());
}

async function renderTree() {
  treeEl.innerHTML = "";
  if (!rootUri) return;

  const query = (searchInput.value || "").trim();
  const stack = [{ uri: rootUri, depth: 0, name: "根目录", isDir: true }];
  expandedDirs.add(rootUri);

  while (stack.length) {
    const node = stack.pop();
    if (!node) continue;
    if (node.depth > 0) {
      const row = document.createElement("div");
      row.className = "row";
      if (selectedUri === node.uri) row.classList.add("rowActive");
      row.style.paddingLeft = `${10 + node.depth * 14}px`;

      const marker = document.createElement("div");
      marker.textContent = node.isDir
        ? expandedDirs.has(node.uri)
          ? icon("folderOpen")
          : icon("folder")
        : icon("file");
      row.appendChild(marker);

      const nameEl = document.createElement("div");
      nameEl.className = "rowName";
      nameEl.textContent = node.name;
      row.appendChild(nameEl);

      treeEl.appendChild(row);

      row.addEventListener("click", async () => {
        if (node.isDir) {
          if (expandedDirs.has(node.uri)) expandedDirs.delete(node.uri);
          else expandedDirs.add(node.uri);
          await renderTree();
          return;
        }
        await openFile(node.uri, node.name);
      });
    }

    if (node.isDir && expandedDirs.has(node.uri)) {
      const children = await ensureDirLoaded(node.uri);
      const filtered = query ? children.filter((c) => c.isDir || matchQuery(c.name, query)) : children;
      for (let i = filtered.length - 1; i >= 0; i--) {
        const c = filtered[i];
        stack.push({ uri: c.uri, depth: node.depth + 1, name: c.name, isDir: c.isDir });
      }
    }
  }
}

async function saveNow(view, reason) {
  if (!currentFileUri) return;
  if (!dirty) return;

  const ticket = ++saveTicket;
  saving = true;
  saveError = null;
  setStatus();
  try {
    const text = getText(view);
    rpc("writeText", { uri: currentFileUri, text });
    rpc("setLastOpenedFileUri", { uri: currentFileUri });
    if (ticket === saveTicket) {
      dirty = false;
      lastSavedText = text;
    }
  } catch (e) {
    if (ticket === saveTicket) {
      saveError = e && e.message ? e.message : "保存失败";
    }
  } finally {
    if (ticket === saveTicket) {
      saving = false;
      setStatus();
    }
  }
}

const saveLater = debounce((view) => {
  void saveNow(view, "debounce");
}, 300);

const updateListener = EditorView.updateListener.of((update) => {
  if (!update.docChanged) return;
  if (ignoreChange) return;
  dirty = true;
  saveError = null;
  setStatus();
  saveLater(update.view);
});

const state = EditorState.create({
  doc: "",
  extensions: [
    basicSetup,
    markdown(),
    EditorView.lineWrapping,
    updateListener,
    themeCompartment.of(lightTheme)
  ]
});

const view = new EditorView({ state, parent: editorShell });

function setTheme(isDark) {
  document.documentElement.dataset.theme = isDark ? "dark" : "light";
  view.dispatch({ effects: themeCompartment.reconfigure(isDark ? darkTheme : lightTheme) });
}

async function openFile(uri, name) {
  if (!uri) return;
  if (uri === currentFileUri && !saveError) return;
  try {
    await saveNow(view, "switch");
  } catch (_) {}

  saving = false;
  saveError = null;
  dirty = false;
  setStatus();

  const text = rpc("readText", { uri }) || "";
  ignoreChange = true;
  replaceAll(view, text);
  ignoreChange = false;

  currentFileUri = uri;
  currentFileName = name || clampName(name);
  selectedUri = uri;
  lastSavedText = text;
  rpc("setLastOpenedFileUri", { uri });
  setTitle();
  setStatus();
  await renderTree();

  if (window.innerWidth <= 768) {
    toggleSidebar(false);
  }

  try {
    view.focus();
  } catch (_) {}
}

async function createAndOpen() {
  const res = rpc("createInRoot", {});
  if (!res || !res.uri) throw new Error("新建失败");
  clearTreeCache();
  expandedDirs.add(rootUri);
  await openFile(res.uri, res.name);
}

async function renameCurrent() {
  if (!currentFileUri) return;
  const next = window.prompt("重命名（不含 .md）", (currentFileName || "").replace(/\.md$/i, ""));
  if (next == null) return;
  const newName = normalizeMdName(next);
  const res = rpc("rename", { uri: currentFileUri, name: newName });
  if (!res || !res.uri) throw new Error("重命名失败");
  clearTreeCache();
  currentFileUri = res.uri;
  currentFileName = res.name || newName;
  selectedUri = currentFileUri;
  rpc("setLastOpenedFileUri", { uri: currentFileUri });
  setTitle();
  await renderTree();
}

async function deleteCurrent() {
  if (!currentFileUri) return;
  const ok = window.confirm(`删除 ${clampName(currentFileName)}？`);
  if (!ok) return;
  rpc("delete", { uri: currentFileUri });
  currentFileUri = null;
  currentFileName = null;
  selectedUri = null;
  clearTreeCache();
  await renderTree();
  try {
    await createAndOpen();
  } catch (_) {}
}

async function refreshAll() {
  clearTreeCache();
  await renderTree();
}

async function rePickFolder() {
  try {
    rpc("requestReSelectFolder", {});
  } catch (_) {}
}

btnNew.addEventListener("click", () => void createAndOpen());
btnRename.addEventListener("click", () => void renameCurrent());
btnDelete.addEventListener("click", () => void deleteCurrent());
btnRefresh.addEventListener("click", () => void refreshAll());
btnRePick.addEventListener("click", () => void rePickFolder());

searchInput.addEventListener("input", () => void renderTree());

window.App = {
  setTheme
};

async function bootstrap() {
  try {
    const state = rpc("getState", {});
    rootUri = state.rootUri;
    if (!rootUri) throw new Error("未选择目录");
    const last = state.lastOpenedFileUri;
    await renderTree();
    if (last) {
      try {
        const doc = rpc("stat", { uri: last });
        if (doc && doc.exists && doc.isFile) await openFile(last, doc.name || "未命名.md");
        else await createAndOpen();
      } catch (_) {
        await createAndOpen();
      }
    } else {
      await createAndOpen();
    }
    hideBoot();
  } catch (e) {
    titleEl.textContent = "无法启动";
    statusEl.textContent = e && e.message ? e.message : "未知错误";
    setBoot(e && e.message ? `无法启动：${e.message}` : "无法启动");
  }
}

bootstrap();
