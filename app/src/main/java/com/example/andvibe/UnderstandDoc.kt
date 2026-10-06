package com.example.andvibe

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object UnderstandDoc {
    const val HOST = "andvibe.understand"

    fun page(markdown: String): String {
        val body = toHtml(markdown)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=3"/>
              <style>
                :root {
                  --bg: #0D1117;
                  --panel: #161B22;
                  --ink: #E6EDF3;
                  --muted: #8B949E;
                  --accent: #58A6FF;
                  --line: #30363D;
                  --code: #010409;
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
                  color: #F85149;
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
                    background: '#0D1117',
                    primaryColor: '#161B22',
                    primaryTextColor: '#E6EDF3',
                    primaryBorderColor: '#58A6FF',
                    secondaryColor: '#21262D',
                    tertiaryColor: '#0D1117',
                    lineColor: '#8B949E',
                    textColor: '#E6EDF3',
                    mainBkg: '#161B22',
                    nodeBorder: '#58A6FF',
                    clusterBkg: '#161B22',
                    clusterBorder: '#30363D',
                    titleColor: '#E6EDF3',
                    edgeLabelBackground: '#0D1117',
                    actorBkg: '#161B22',
                    actorBorder: '#58A6FF',
                    actorTextColor: '#E6EDF3',
                    actorLineColor: '#30363D',
                    signalColor: '#E6EDF3',
                    signalTextColor: '#E6EDF3',
                    labelBoxBkgColor: '#161B22',
                    labelBoxBorderColor: '#30363D',
                    labelTextColor: '#E6EDF3',
                    loopTextColor: '#E6EDF3',
                    noteBkgColor: '#21262D',
                    noteTextColor: '#E6EDF3',
                    noteBorderColor: '#30363D',
                    activationBkgColor: '#21262D',
                    sequenceNumberColor: '#0D1117',
                    pie1: '#58A6FF',
                    pie2: '#F85149',
                    pie3: '#D29922',
                    pie4: '#3FB950',
                    pie5: '#8B949E',
                    pieTitleTextColor: '#E6EDF3',
                    pieSectionTextColor: '#E6EDF3',
                    pieLegendTextColor: '#E6EDF3',
                    xyChart: {
                      backgroundColor: '#161B22',
                      titleColor: '#E6EDF3',
                      xAxisLabelColor: '#8B949E',
                      xAxisTitleColor: '#E6EDF3',
                      xAxisTickColor: '#30363D',
                      xAxisLineColor: '#30363D',
                      yAxisLabelColor: '#8B949E',
                      yAxisTitleColor: '#E6EDF3',
                      yAxisTickColor: '#30363D',
                      yAxisLineColor: '#30363D',
                      plotColorPalette: '#58A6FF'
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
