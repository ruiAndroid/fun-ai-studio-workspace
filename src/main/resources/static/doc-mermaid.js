// /doc/** 页面 Mermaid 渲染器：把 ```mermaid``` code fence 变成真正的图
//
// commonmark 输出形态：
//   <pre><code class="language-mermaid">...</code></pre>
//
// 做法：
// 1) 替换为 <div class="mermaid">...</div>
// 2) 动态加载 mermaid（多 CDN 兜底）
// 3) mermaid.run() 渲染

(function () {
  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = src;
      s.defer = true;
      s.onload = function () {
        resolve();
      };
      s.onerror = function () {
        reject(new Error("failed to load script: " + src));
      };
      document.head.appendChild(s);
    });
  }

  function toMermaidDivs() {
    var nodes = document.querySelectorAll(
      "pre > code.language-mermaid, pre > code.lang-mermaid"
    );
    if (!nodes || !nodes.length) return 0;
    var count = 0;
    for (var i = 0; i < nodes.length; i++) {
      var code = nodes[i];
      var pre = code.parentElement;
      if (!pre || !pre.parentElement) continue;
      var txt = code.textContent || "";
      var div = document.createElement("div");
      div.className = "mermaid";
      div.textContent = txt;
      pre.parentElement.replaceChild(div, pre);
      count++;
    }
    return count;
  }

  function render() {
    if (!window.mermaid) return;
    window.mermaid.initialize({ startOnLoad: false });
    window.mermaid.run({ querySelector: ".mermaid" });
  }

  function ensureMermaid() {
    if (window.mermaid) return Promise.resolve();

    // 多 CDN 兜底：你环境里 jsdelivr 可能不可用，所以加 cdnjs/unpkg
    var candidates = [
      "https://cdnjs.cloudflare.com/ajax/libs/mermaid/10.9.0/mermaid.min.js",
      "https://unpkg.com/mermaid@10/dist/mermaid.min.js",
      "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js",
    ];

    var p = Promise.reject(new Error("no mermaid source tried"));
    for (var i = 0; i < candidates.length; i++) {
      (function (src) {
        p = p.catch(function () {
          return loadScript(src);
        });
      })(candidates[i]);
    }
    return p;
  }

  function boot() {
    var n = toMermaidDivs();
    if (!n) return;
    ensureMermaid()
      .then(function () {
        render();
      })
      .catch(function (e) {
        if (console && console.warn) console.warn("mermaid init failed", e);
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();


