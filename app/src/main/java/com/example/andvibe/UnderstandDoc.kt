package com.example.andvibe

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object UnderstandDoc {
    const val HOST = "andvibe.understand"

    data class Palette(
        val bg: String,
        val panel: String,
        val raised: String,
        val ink: String,
        val muted: String,
        val accent: String,
        val line: String,
        val code: String,
        val ask: String,
        val quote: String,
        val bid: String,
    )

    fun page(markdown: String, palette: Palette): String {
        val body = toHtml(markdown)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=3"/>
              <style>
                :root {
                  --bg: ${palette.bg};
                  --panel: ${palette.panel};
                  --raised: ${palette.raised};
                  --ink: ${palette.ink};
                  --muted: ${palette.muted};
                  --accent: ${palette.accent};
                  --line: ${palette.line};
                  --code: ${palette.code};
                }
                html, body {
                  margin: 0;
                  padding: 0;
                  background: var(--bg);
                  color: var(--ink);
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  font-size: 15px;
                  line-height: 1.55;
                  -webkit-text-size-adjust: 100%;
                }
                .wrap { padding: 4px 2px 28px; }
                h1, h2, h3 {
                  color: var(--ink);
                  font-weight: 600;
                  line-height: 1.25;
                  margin: 1.2em 0 0.45em;
                }
                h1 { font-size: 1.45rem; border-bottom: 1px solid var(--line); padding-bottom: 0.35em; }
                h2 { font-size: 1.2rem; color: var(--accent); }
                h3 { font-size: 1.05rem; }
                p { margin: 0.55em 0; }
                ul, ol { margin: 0.4em 0 0.7em; padding-left: 1.35em; }
                li { margin: 0.25em 0; }
                a { color: var(--accent); }
                code {
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                  font-size: 0.88em;
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 4px;
                  padding: 0.1em 0.35em;
                }
                pre {
                  background: var(--code);
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  padding: 12px;
                  overflow-x: auto;
                  margin: 0.7em 0 1em;
                }
                pre code {
                  background: transparent;
                  border: none;
                  padding: 0;
                  font-size: 0.82em;
                  line-height: 1.45;
                  color: var(--ink);
                }
                .mermaid {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 10px;
                  padding: 14px 8px;
                  margin: 0.8em 0 1.2em;
                  overflow-x: auto;
                  text-align: center;
                }
                .mermaid svg { max-width: 100%; height: auto; }
                hr {
                  border: none;
                  border-top: 1px solid var(--line);
                  margin: 1.4em 0;
                }
                strong { font-weight: 600; }
                .err {
                  color: ${palette.ask};
                  font-family: ui-monospace, monospace;
                  font-size: 0.85em;
                  white-space: pre-wrap;
                }
              </style>
            </head>
            <body>
              <div class="wrap">$body</div>
              <script src="https://$HOST/mermaid.min.js"></script>
              <script>
                mermaid.initialize({
                  startOnLoad: false,
                  securityLevel: 'strict',
                  theme: 'dark',
                  flowchart: { htmlLabels: false, curve: 'basis' },
                  sequence: { mirrorActors: false, messageAlign: 'left' },
                  xyChart: {
                    width: 520,
                    height: 280,
                    titleFontSize: 14,
                    xAxis: { labelFontSize: 11 },
                    yAxis: { labelFontSize: 11 }
                  },
                  themeVariables: {
                    darkMode: true,
                    background: '${palette.bg}',
                    primaryColor: '${palette.panel}',
                    primaryTextColor: '${palette.ink}',
                    primaryBorderColor: '${palette.accent}',
                    secondaryColor: '${palette.raised}',
                    tertiaryColor: '${palette.bg}',
                    lineColor: '${palette.muted}',
                    textColor: '${palette.ink}',
                    mainBkg: '${palette.panel}',
                    nodeBorder: '${palette.accent}',
                    clusterBkg: '${palette.panel}',
                    clusterBorder: '${palette.line}',
                    titleColor: '${palette.ink}',
                    edgeLabelBackground: '${palette.bg}',
                    actorBkg: '${palette.panel}',
                    actorBorder: '${palette.accent}',
                    actorTextColor: '${palette.ink}',
                    actorLineColor: '${palette.line}',
                    signalColor: '${palette.ink}',
                    signalTextColor: '${palette.ink}',
                    labelBoxBkgColor: '${palette.panel}',
                    labelBoxBorderColor: '${palette.line}',
                    labelTextColor: '${palette.ink}',
                    loopTextColor: '${palette.ink}',
                    noteBkgColor: '${palette.raised}',
                    noteTextColor: '${palette.ink}',
                    noteBorderColor: '${palette.line}',
                    activationBkgColor: '${palette.raised}',
                    sequenceNumberColor: '${palette.bg}',
                    pie1: '${palette.accent}',
                    pie2: '${palette.ask}',
                    pie3: '${palette.quote}',
                    pie4: '${palette.bid}',
                    pie5: '${palette.muted}',
                    pieTitleTextColor: '${palette.ink}',
                    pieSectionTextColor: '${palette.ink}',
                    pieLegendTextColor: '${palette.ink}',
                    xyChart: {
                      backgroundColor: '${palette.panel}',
                      titleColor: '${palette.ink}',
                      xAxisLabelColor: '${palette.muted}',
                      xAxisTitleColor: '${palette.ink}',
                      xAxisTickColor: '${palette.line}',
                      xAxisLineColor: '${palette.line}',
                      yAxisLabelColor: '${palette.muted}',
                      yAxisTitleColor: '${palette.ink}',
                      yAxisTickColor: '${palette.line}',
                      yAxisLineColor: '${palette.line}',
                      plotColorPalette: '${palette.accent}'
                    }
                  }
                });
                mermaid.run({ querySelector: '.mermaid' }).catch(function (e) {
                  document.querySelectorAll('.mermaid').forEach(function (el) {
                    el.innerHTML = '<div class="err">Diagram error: ' +
                      String(e && e.message ? e.message : e) + '</div>';
                  });
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    fun asset(path: String, bytes: ByteArray, mime: String): WebResourceResponse {
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
    }

    fun missing(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var i = 0
        var inUl = false
        var inOl = false
        var para = StringBuilder()

        fun closeLists() {
            if (inUl) {
                out.append("</ul>")
                inUl = false
            }
            if (inOl) {
                out.append("</ol>")
                inOl = false
            }
        }

        fun flushPara() {
            if (para.isEmpty()) return
            closeLists()
            out.append("<p>").append(inline(para.toString().trim())).append("</p>")
            para = StringBuilder()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushPara()
                    closeLists()
                    val lang = trimmed.removePrefix("```").trim().lowercase()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    if (lang == "mermaid") {
                        out.append("<pre class=\"mermaid\">").append(escape(code.toString())).append("</pre>")
                    } else {
                        out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>")
                    }
                }
                trimmed.matches(Regex("^#{1,3}\\s+.+")) -> {
                    flushPara()
                    closeLists()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val text = trimmed.drop(level).trim()
                    out.append("<h$level>").append(inline(text)).append("</h$level>")
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushPara()
                    closeLists()
                    out.append("<hr/>")
                }
                trimmed.matches(Regex("^[-*]\\s+.+")) -> {
                    flushPara()
                    if (inOl) {
                        out.append("</ol>")
                        inOl = false
                    }
                    if (!inUl) {
                        out.append("<ul>")
                        inUl = true
                    }
                    out.append("<li>").append(inline(trimmed.replace(Regex("^[-*]\\s+"), ""))).append("</li>")
                }
                trimmed.matches(Regex("^\\d+\\.\\s+.+")) -> {
                    flushPara()
                    if (inUl) {
                        out.append("</ul>")
                        inUl = false
                    }
                    if (!inOl) {
                        out.append("<ol>")
                        inOl = true
                    }
                    out.append("<li>").append(inline(trimmed.replace(Regex("^\\d+\\.\\s+"), ""))).append("</li>")
                }
                trimmed.isEmpty() -> flushPara()
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(trimmed)
                }
            }
            i++
        }
        flushPara()
        closeLists()
        return out.toString()
    }

    private fun inline(text: String): String {
        var s = escape(text)
        s = Regex("""`([^`]+)`""").replace(s) { m ->
            "<code>${m.groupValues[1]}</code>"
        }
        s = Regex("""\*\*([^*]+)\*\*""").replace(s) { m ->
            "<strong>${m.groupValues[1]}</strong>"
        }
        s = Regex("""(?<!\*)\*([^*]+)\*(?!\*)""").replace(s) { m ->
            "<em>${m.groupValues[1]}</em>"
        }
        return s
    }

    private fun escape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
