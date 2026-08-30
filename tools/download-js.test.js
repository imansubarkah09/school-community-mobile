// Sanity check for BLOB_DOWNLOAD_JS in MainActivity.kt — the interception logic
// is the fragile part. Run: node tools/download-js.test.js
const fs = require("fs");
const path = require("path");
const src = fs.readFileSync(
  path.join(__dirname, "../app/src/main/java/space/schoolcommunity/app/MainActivity.kt"),
  "utf8",
);
const js = src.match(/BLOB_DOWNLOAD_JS = """([\s\S]*?)"""/)[1];

function env() {
  const saved = [];
  const L = {};
  class Blob { constructor(p) { this._s = (p || []).join(""); } }
  class FileReader {
    readAsDataURL(b) {
      this.result = "data:application/pdf;base64,X" + (b && b._s);
      setTimeout(() => this.onloadend && this.onloadend());
    }
  }
  class El extends EventTarget {
    constructor(t) { super(); this.tagName = t.toUpperCase(); this.attrs = {}; }
    setAttribute(k, v) { this.attrs[k] = v; }
    getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; }
    get href() { return this.attrs.href; }
    set href(v) { this.attrs.href = v; }
    closest() { return this.tagName === "A" ? this : null; }
  }
  El.prototype.click = function () {
    (L.click || []).forEach((fn) => fn({ target: this, preventDefault() {}, stopPropagation() {} }));
  };
  const window = {
    open: () => { throw new Error("real window.open"); },
    URL: { createObjectURL: () => "blob:x/" + Math.random(), revokeObjectURL() {} },
  };
  window.window = window;
  const document = {
    addEventListener: (t, fn) => { (L[t] = L[t] || []).push(fn); },
    createElement: (t) => new El(t),
  };
  const AndroidDownloader = { save: (d, n) => saved.push({ d, n }) };
  function XMLHttpRequest() {
    this.open = () => {};
    this.send = () => setTimeout(() => this.onload && this.onload());
    Object.defineProperty(this, "response", { get: () => new Blob(["xhrbytes"]) });
  }
  new Function(
    "window", "document", "HTMLElement", "EventTarget", "Blob", "FileReader", "XMLHttpRequest", "AndroidDownloader", "setTimeout", js,
  )(window, document, El, EventTarget, Blob, FileReader, XMLHttpRequest, AndroidDownloader, setTimeout);
  return { saved, El, window };
}

const wait = () => new Promise((r) => setTimeout(r, 60));
function assert(c, m) { if (!c) { console.error("FAIL:", m); process.exit(1); } console.log("ok -", m); }

(async () => {
  // 1. jsPDF-style: detached anchor + blob href + programmatic .click()
  let e = env();
  let a = new e.El("a");
  a.href = e.window.URL.createObjectURL({});
  a.setAttribute("download", "laporan.pdf");
  a.click();
  await wait();
  assert(e.saved.length === 1 && e.saved[0].n === "laporan.pdf", "detached anchor blob .click() -> bridge");

  // 2. in-DOM anchor tapped by the user (document capture listener)
  e = env();
  a = new e.El("a");
  a.href = e.window.URL.createObjectURL({});
  a.setAttribute("download", "data.xlsx");
  (e.window, a).click();
  await wait();
  assert(e.saved.some((s) => s.n === "data.xlsx"), "dom anchor blob tap -> bridge");

  // 3. FileSaver.js-style: detached anchor + dispatchEvent(MouseEvent('click'))
  e = env();
  a = new e.El("a");
  a.href = e.window.URL.createObjectURL({});
  a.setAttribute("download", "filesaver.pdf");
  a.dispatchEvent({ type: "click" });
  await wait();
  assert(e.saved.length === 1 && e.saved[0].n === "filesaver.pdf", "detached anchor dispatchEvent('click') -> bridge");

  // 4. data: URI passed through verbatim
  e = env();
  a = new e.El("a");
  a.href = "data:application/pdf;base64,Zm9v";
  a.setAttribute("download", "d.pdf");
  a.click();
  await wait();
  assert(e.saved.length === 1 && e.saved[0].d.startsWith("data:application/pdf"), "data: uri -> bridge verbatim");

  // 4. plain http href left alone (native DownloadListener handles it)
  e = env();
  a = new e.El("a");
  a.href = "https://schoolcommunity.space/api/export.pdf";
  a.click();
  await wait();
  assert(e.saved.length === 0, "http href untouched");

  console.log("all download-js checks passed");
})();
