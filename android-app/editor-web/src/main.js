import "./style.css";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  HeroUIProvider,
  Button,
  Input,
  Listbox,
  ListboxItem,
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter
} from "@heroui/react";
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

function getText(view) {
  return view.state.doc.toString();
}

function replaceAll(view, text) {
  view.dispatch({
    changes: { from: 0, to: view.state.doc.length, insert: text ?? "" }
  });
}

function matchQuery(name, query) {
  if (!query) return true;
  return (name || "").toLowerCase().includes(query.toLowerCase());
}

function App() {
  const editorRef = useRef(null);
  const viewRef = useRef(null);
  const themeCompartmentRef = useRef(null);
  const treeCacheRef = useRef(new Map());
  const expandedDirsRef = useRef(new Set());
  const saveTicketRef = useRef(0);
  const ignoreChangeRef = useRef(false);
  const currentFileUriRef = useRef(null);
  const dirtyRef = useRef(false);
  const saveErrorRef = useRef(null);
  const rootUriRef = useRef(null);
  const lastSavedTextRef = useRef("");

  const [currentFileUri, setCurrentFileUri] = useState(null);
  const [currentFileName, setCurrentFileName] = useState(null);
  const [selectedUri, setSelectedUri] = useState(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(null);
  const [treeItems, setTreeItems] = useState([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth <= 768);
  const [isDark, setIsDark] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameValue, setRenameValue] = useState("");
  const [deleteOpen, setDeleteOpen] = useState(false);

  useEffect(() => {
    currentFileUriRef.current = currentFileUri;
  }, [currentFileUri]);

  useEffect(() => {
    dirtyRef.current = dirty;
  }, [dirty]);

  useEffect(() => {
    saveErrorRef.current = saveError;
  }, [saveError]);

  const statusText = saveError ? saveError : saving ? "保存中…" : dirty ? "编辑中…" : "已保存";
  const titleText = currentFileName ? clampName(currentFileName) : "未选择文件";

  const clearTreeCache = useCallback(() => {
    treeCacheRef.current = new Map();
  }, []);

  const ensureDirLoaded = useCallback(async (dirUri) => {
    if (!dirUri) return [];
    if (treeCacheRef.current.has(dirUri)) return treeCacheRef.current.get(dirUri);
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
    treeCacheRef.current.set(dirUri, normalized);
    return normalized;
  }, []);

  const buildTree = useCallback(async () => {
    const rootUri = rootUriRef.current;
    if (!rootUri) {
      setTreeItems([]);
      return;
    }
    const query = (searchQuery || "").trim();
    const stack = [{ uri: rootUri, depth: 0, name: "根目录", isDir: true }];
    expandedDirsRef.current.add(rootUri);
    const rows = [];
    while (stack.length) {
      const node = stack.pop();
      if (!node) continue;
      if (node.depth > 0) rows.push(node);
      if (node.isDir && expandedDirsRef.current.has(node.uri)) {
        const children = await ensureDirLoaded(node.uri);
        const filtered = query ? children.filter((c) => c.isDir || matchQuery(c.name, query)) : children;
        for (let i = filtered.length - 1; i >= 0; i--) {
          const c = filtered[i];
          stack.push({ uri: c.uri, depth: node.depth + 1, name: c.name, isDir: c.isDir });
        }
      }
    }
    setTreeItems(rows);
  }, [ensureDirLoaded, searchQuery]);

  const saveNow = useCallback(async (view) => {
    const uri = currentFileUriRef.current;
    if (!uri) return;
    if (!dirtyRef.current) return;
    const ticket = ++saveTicketRef.current;
    setSaving(true);
    setSaveError(null);
    saveErrorRef.current = null;
    try {
      const text = getText(view);
      rpc("writeText", { uri, text });
      rpc("setLastOpenedFileUri", { uri });
      if (ticket === saveTicketRef.current) {
        dirtyRef.current = false;
        setDirty(false);
        lastSavedTextRef.current = text;
      }
    } catch (e) {
      if (ticket === saveTicketRef.current) {
        const msg = e && e.message ? e.message : "保存失败";
        saveErrorRef.current = msg;
        setSaveError(msg);
      }
    } finally {
      if (ticket === saveTicketRef.current) {
        setSaving(false);
      }
    }
  }, []);

  const saveLater = useMemo(
    () =>
      debounce((view) => {
        void saveNow(view);
      }, 300),
    [saveNow]
  );

  const openFile = useCallback(
    async (uri, name) => {
      if (!uri) return;
      if (uri === currentFileUriRef.current && !saveErrorRef.current) return;
      const view = viewRef.current;
      if (!view) return;
      try {
        await saveNow(view);
      } catch (_) {}
      setSaving(false);
      setSaveError(null);
      saveErrorRef.current = null;
      setDirty(false);
      dirtyRef.current = false;
      const text = rpc("readText", { uri }) || "";
      ignoreChangeRef.current = true;
      replaceAll(view, text);
      ignoreChangeRef.current = false;
      currentFileUriRef.current = uri;
      setCurrentFileUri(uri);
      setCurrentFileName(name || clampName(name));
      setSelectedUri(uri);
      lastSavedTextRef.current = text;
      rpc("setLastOpenedFileUri", { uri });
      await buildTree();
      if (window.innerWidth <= 768) {
        setIsSidebarOpen(false);
      }
      try {
        view.focus();
      } catch (_) {}
    },
    [buildTree, saveNow]
  );

  const createAndOpen = useCallback(async () => {
    const res = rpc("createInRoot", {});
    if (!res || !res.uri) throw new Error("新建失败");
    clearTreeCache();
    const rootUri = rootUriRef.current;
    if (rootUri) expandedDirsRef.current.add(rootUri);
    await openFile(res.uri, res.name);
  }, [clearTreeCache, openFile]);

  const refreshAll = useCallback(async () => {
    clearTreeCache();
    await buildTree();
  }, [buildTree, clearTreeCache]);

  const rePickFolder = useCallback(async () => {
    try {
      rpc("requestReSelectFolder", {});
    } catch (_) {}
  }, []);

  const handleRenameOpen = useCallback(() => {
    if (!currentFileUri) return;
    const base = (currentFileName || "").replace(/\.md$/i, "");
    setRenameValue(base);
    setRenameOpen(true);
  }, [currentFileName, currentFileUri]);

  const confirmRename = useCallback(async () => {
    if (!currentFileUri) return;
    const next = (renameValue || "").trim();
    if (!next) {
      setRenameOpen(false);
      return;
    }
    const newName = normalizeMdName(next);
    const res = rpc("rename", { uri: currentFileUri, name: newName });
    if (!res || !res.uri) throw new Error("重命名失败");
    clearTreeCache();
    currentFileUriRef.current = res.uri;
    setCurrentFileUri(res.uri);
    setCurrentFileName(res.name || newName);
    setSelectedUri(res.uri);
    rpc("setLastOpenedFileUri", { uri: res.uri });
    await buildTree();
    setRenameOpen(false);
  }, [buildTree, clearTreeCache, currentFileUri, renameValue]);

  const handleDeleteOpen = useCallback(() => {
    if (!currentFileUri) return;
    setDeleteOpen(true);
  }, [currentFileUri]);

  const confirmDelete = useCallback(async () => {
    if (!currentFileUri) return;
    rpc("delete", { uri: currentFileUri });
    currentFileUriRef.current = null;
    setCurrentFileUri(null);
    setCurrentFileName(null);
    setSelectedUri(null);
    dirtyRef.current = false;
    setDirty(false);
    setSaveError(null);
    saveErrorRef.current = null;
    clearTreeCache();
    await buildTree();
    try {
      await createAndOpen();
    } catch (_) {}
    setDeleteOpen(false);
  }, [buildTree, clearTreeCache, createAndOpen, currentFileUri]);

  useEffect(() => {
    function handleResize() {
      const mobile = window.innerWidth <= 768;
      setIsMobile(mobile);
      setIsSidebarOpen(mobile ? false : true);
    }
    handleResize();
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    const themeCompartment = themeCompartmentRef.current;
    document.documentElement.dataset.theme = isDark ? "dark" : "light";
    if (view && themeCompartment) {
      view.dispatch({ effects: themeCompartment.reconfigure(isDark ? darkTheme : lightTheme) });
    }
  }, [isDark]);

  useEffect(() => {
    window.App = {
      setTheme: (val) => setIsDark(!!val)
    };
  }, []);

  useEffect(() => {
    if (!editorRef.current) return;
    const themeCompartment = new Compartment();
    themeCompartmentRef.current = themeCompartment;
    const updateListener = EditorView.updateListener.of((update) => {
      if (!update.docChanged) return;
      if (ignoreChangeRef.current) return;
      setDirty(true);
      dirtyRef.current = true;
      setSaveError(null);
      saveErrorRef.current = null;
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
    const view = new EditorView({ state, parent: editorRef.current });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
  }, [saveLater]);

  useEffect(() => {
    let cancelled = false;
    const bootstrap = async () => {
      try {
        const state = rpc("getState", {});
        rootUriRef.current = state.rootUri;
        if (!rootUriRef.current) throw new Error("未选择目录");
        const last = state.lastOpenedFileUri;
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
        if (!cancelled) hideBoot();
      } catch (e) {
        const msg = e && e.message ? e.message : "未知错误";
        if (!cancelled) setBoot(`无法启动：${msg}`);
      }
    };
    void bootstrap();
    return () => {
      cancelled = true;
    };
  }, [buildTree, createAndOpen, openFile]);

  const itemMap = useMemo(() => new Map(treeItems.map((item) => [item.uri, item])), [treeItems]);

  const handleTreeAction = useCallback(
    (key) => {
      const item = itemMap.get(key);
      if (!item) return;
      if (item.isDir) {
        if (expandedDirsRef.current.has(item.uri)) expandedDirsRef.current.delete(item.uri);
        else expandedDirsRef.current.add(item.uri);
        void buildTree();
        return;
      }
      void openFile(item.uri, item.name);
    },
    [buildTree, itemMap, openFile]
  );

  return (
    <div className="app">
      <div className="sidebar" data-open={isSidebarOpen ? "true" : "false"}>
        <div className="sidebarHeader">
          <Input
            size="sm"
            radius="sm"
            placeholder="搜索文件…"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        <div className="tree">
          <Listbox
            aria-label="文件树"
            selectionMode="single"
            selectedKeys={selectedUri ? new Set([selectedUri]) : new Set()}
            onAction={handleTreeAction}
            className="treeList"
          >
            {treeItems.map((item) => (
              <ListboxItem key={item.uri} textValue={item.name}>
                <div className="treeRow" style={{ paddingLeft: `${8 + item.depth * 14}px` }}>
                  <span className="treeMarker">
                    {item.isDir
                      ? expandedDirsRef.current.has(item.uri)
                        ? icon("folderOpen")
                        : icon("folder")
                      : icon("file")}
                  </span>
                  <span className="treeName">{item.name}</span>
                </div>
              </ListboxItem>
            ))}
          </Listbox>
        </div>
      </div>
      <div
        className="sidebarOverlay"
        data-open={isSidebarOpen ? "true" : "false"}
        onClick={() => setIsSidebarOpen(false)}
      />
      <div className="main">
        <div className="topbar">
          <div className="topbarLeft">
            {isMobile ? (
              <Button size="sm" variant="flat" isIconOnly onPress={() => setIsSidebarOpen((v) => !v)}>
                ☰
              </Button>
            ) : null}
            <div className="titleBlock">
              <div className="title">{titleText}</div>
              <div className="status">{statusText}</div>
            </div>
          </div>
          <div className="actions">
            <div className="actionsGroup">
              <Button size="sm" color="primary" onPress={() => void createAndOpen()}>
                新建
              </Button>
            </div>
            <div className="actionsDivider" />
            <div className="actionsGroup">
              <Button size="sm" variant="flat" isDisabled={!currentFileUri} onPress={handleRenameOpen}>
                重命名
              </Button>
              <Button size="sm" variant="flat" isDisabled={!currentFileUri} onPress={handleDeleteOpen}>
                删除
              </Button>
              <Button size="sm" variant="flat" onPress={() => void refreshAll()}>
                刷新
              </Button>
              <Button size="sm" variant="flat" onPress={() => void rePickFolder()}>
                换目录
              </Button>
            </div>
          </div>
        </div>
        <div className="editorShell" ref={editorRef} />
      </div>

      <Modal isOpen={renameOpen} onOpenChange={setRenameOpen}>
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>重命名</ModalHeader>
              <ModalBody>
                <Input
                  autoFocus
                  size="sm"
                  label="文件名（不含 .md）"
                  value={renameValue}
                  onChange={(e) => setRenameValue(e.target.value)}
                />
              </ModalBody>
              <ModalFooter>
                <Button variant="flat" onPress={onClose}>
                  取消
                </Button>
                <Button color="primary" onPress={() => void confirmRename()}>
                  确定
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal isOpen={deleteOpen} onOpenChange={setDeleteOpen}>
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>删除文件</ModalHeader>
              <ModalBody>确定删除 {clampName(currentFileName)}？</ModalBody>
              <ModalFooter>
                <Button variant="flat" onPress={onClose}>
                  取消
                </Button>
                <Button color="danger" onPress={() => void confirmDelete()}>
                  删除
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}

const root = document.getElementById("root");
createRoot(root).render(
  <HeroUIProvider disableAnimation disableRipple>
    <App />
  </HeroUIProvider>
);
