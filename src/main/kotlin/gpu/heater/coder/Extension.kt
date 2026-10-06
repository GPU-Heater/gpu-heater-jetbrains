package gpu.Heater.coder

import com.intellij.ui.jcef.JBCefBrowserBase
import com.google.gson.Gson
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.collections.get
import java.util.Base64

@State(name = "HeaterSettings", storages = [Storage("HeaterSettings.xml")])
class HeaterSettings : PersistentStateComponent<HeaterSettings.State> {
    class State {
        var apiBaseUrl: String = "http://127.0.0.1:2004"
        var language: String = "en"
        var autoCompleteEnabled: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        val instance: HeaterSettings
            get() = ApplicationManager.getApplication().getService(HeaterSettings::class.java)
    }
}

class HeaterConfigurable : Configurable {
    private var component: JPanel? = null
    private val apiUrlField = JTextField()

    override fun getDisplayName(): String = "GPU-Heater"

    override fun createComponent(): JComponent {
        component = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Backend API Base URL (e.g., http://127.0.0.1:2004):"))
            add(apiUrlField)
        }
        return component!!
    }

    override fun isModified(): Boolean {
        val settings = HeaterSettings.instance.state
        return apiUrlField.text != settings.apiBaseUrl
    }

    override fun apply() {
        val settings = HeaterSettings.instance.state
        settings.apiBaseUrl = apiUrlField.text
    }

    override fun reset() {
        val settings = HeaterSettings.instance.state
        apiUrlField.text = settings.apiBaseUrl
    }

    override fun disposeUIResources() { component = null }
}

class HeaterToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        var currentBrowser: JBCefBrowser? = null
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) return

        val browser = JBCefBrowser()
        currentBrowser = browser
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

        query.addHandler { request ->
            val gson = Gson()
            try {
                val data = gson.fromJson(request, Map::class.java) as Map<*, *>

                when (data["type"]) {
                    "getChatContext" -> {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor
                        val path = project.basePath ?: ""
                        val file = editor?.virtualFile?.path ?: ""
                        val content = editor?.document?.text ?: ""
                        val js =
                            "window.postMessage({ type: 'chatContextResponse', workspacePath: '$path', activeFile: '$file', activeFileContent: `${
                                content.replace(
                                    "`",
                                    "\\`"
                                )
                            }` }, '*');"
                        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
                    }

                    "toggleAutoComplete" -> {
                        val value = data["value"] as? Boolean ?: false
                        HeaterSettings.instance.state.autoCompleteEnabled = value
                    }

                    "changeExtLang" -> {
                        val lang = data["lang"] as? String ?: "en"
                        HeaterSettings.instance.state.language = lang
                    }
                }
            } catch (e: Exception) {
                if (request.contains("getChatContext")) {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val path = project.basePath ?: ""
                    val file = editor?.virtualFile?.path ?: ""
                    val content = editor?.document?.text ?: ""
                    val js =
                        "window.postMessage({ type: 'chatContextResponse', workspacePath: '$path', activeFile: '$file', activeFileContent: `${
                            content.replace(
                                "`",
                                "\\`"
                            )
                        }` }, '*');"
                    browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
                }
            }
            null
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                val injectCode = """
                    window.vscode = {
                        postMessage: function(msg) {
                            window.${query.funcName}(JSON.stringify(msg));
                        }
                    };
                    window.acquireVsCodeApi = function() { return window.vscode; };
                """
                cefBrowser.executeJavaScript(injectCode, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)

        val htmlContent = buildHtmlContent()
        if (htmlContent.isNotEmpty()) {
            browser.loadHTML(htmlContent)
        } else {
            browser.loadHTML("<html><body><h2>Heater Coder: UI dosyaları bulunamadı.</h2></body></html>")
        }

        val content = ContentFactory.getInstance().createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun buildHtmlContent(): String {
        var htmlContent = getResourceFileAsString("/webview/ui.html")
        if (htmlContent.isEmpty()) return ""

        val apiBase = HeaterSettings.instance.state.apiBaseUrl
        val langJson = getResourceFileAsString("/webview/lang.json").ifEmpty { "{}" }
        val markedJs = getResourceFileAsString("/webview/marked.min.js")
        var codiconCss = getResourceFileAsString("/webview/codicon.min.css").ifEmpty {
            getResourceFileAsString("/webview/codicon.css")
        }

        var fontBytes = getResourceFileAsBytes("/webview/codicon.ttf")
        if (fontBytes.isEmpty()) {
            fontBytes = getResourceFileAsBytes("/codicon.ttf")
        }

        if (fontBytes.isNotEmpty()) {
            val fontBase64 = Base64.getEncoder().encodeToString(fontBytes)
            val embeddedFontFace = """
                @font-face {
                    font-family: "codicon";
                    src: url("data:font/truetype;charset=utf-8;base64,$fontBase64") format("truetype");
                    font-weight: normal;
                    font-style: normal;
                }
            """.trimIndent()

            // CSS içindeki eski @font-face bloğunu temizle ve yenisini en başa ekle
            codiconCss = codiconCss.replace(Regex("""@font-face\s*\{[^}]*\}"""), "")
            codiconCss = "$embeddedFontFace\n$codiconCss"
        }

        val injectedHead = buildString {
            append("\n    <script>\n")
            append("        const API_BASE = '$apiBase';\n")
            append("        window.i18nData = $langJson;\n")
            append("    </script>\n")
            if (codiconCss.isNotEmpty()) {
                append("    <style>\n$codiconCss\n    </style>\n")
            }
            if (markedJs.isNotEmpty()) {
                append("    <script>\n$markedJs\n    </script>\n")
            }
        }

        htmlContent = htmlContent.replace(Regex("""<link[^>]*href=["'][^"']*codicon[^"']*["'][^>]*>"""), "")
        htmlContent = htmlContent.replace(Regex("""<script[^>]*src=["'][^"']*marked[^"']*["'][^>]*></script>"""), "")

        return htmlContent.replace("<head>", "<head>$injectedHead")
    }

    private fun getResourceFileAsString(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: javaClass.classLoader?.getResourceAsStream(path.removePrefix("/"))
            ?: return ""
        return InputStreamReader(stream, Charsets.UTF_8).readText()
    }

    private fun getResourceFileAsBytes(path: String): ByteArray {
        val stream = javaClass.getResourceAsStream(path)
            ?: javaClass.classLoader?.getResourceAsStream(path.removePrefix("/"))
            ?: return ByteArray(0)
        return stream.use { it.readBytes() }
    }
}

class GhostTextRenderer(private val text: String) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = inlay.editor.colorsScheme.getFont(EditorFontType.ITALIC)
        val metrics = inlay.editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(text)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        try {
            val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            g2.font = font
            g2.color = JBColor.GRAY

            val baseline = targetRegion.y + editor.ascent
            g2.drawString(text, targetRegion.x, baseline)
        } finally {
            g2.dispose()
        }
    }
}

class HeaterFimListener : DocumentListener {
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    companion object {
        var currentInlay: Inlay<*>? = null
        var currentCompletionText: String = ""
    }

    override fun documentChanged(event: DocumentEvent) {
        if (!HeaterSettings.instance.state.autoCompleteEnabled) return

        val document = event.document
        val editor = EditorFactory.getInstance().getEditors(document).firstOrNull() as? EditorImpl ?: return

        currentInlay?.dispose()
        currentInlay = null
        currentCompletionText = ""

        val offset = event.offset + event.newLength
        val prefix = document.getText(TextRange(maxOf(0, offset - 1000), offset))
        val suffix = document.getText(TextRange(offset, minOf(document.textLength, offset + 1000)))

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiBase = HeaterSettings.instance.state.apiBaseUrl
                val payload = gson.toJson(mapOf("prefix" to prefix, "suffix" to suffix))

                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$apiBase/api/coder/ide/fim-complete"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build()

                val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                val data = gson.fromJson(res.body(), Map::class.java) as Map<*, *>

                if (data["success"] == true) {
                    val completion = data["completion"].toString().trim()
                    if (completion.isNotBlank()) {
                        ApplicationManager.getApplication().invokeLater {
                            currentCompletionText = completion
                            currentInlay = editor.inlayModel.addInlineElement(offset, GhostTextRenderer(completion))
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }
}

class EditorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val actionType = when (e.presentation.text) {
            "Fix Error / Debug" -> "fixError"
            "Explain Code" -> "explain"
            "Generate Tests" -> "tests"
            else -> "refactor"
        }

        val apiBase = HeaterSettings.instance.state.apiBaseUrl
        val endpoint = if (actionType == "fixError") "/api/coder/ide/fix-error" else "/api/coder/ide/inline-action"

        val payload = mutableMapOf(
            "use_knowledge_base" to true,
            "workspace_path" to (project.basePath ?: "")
        )

        if (actionType == "fixError") {
            val errorText = Messages.showInputDialog("Enter Error Log:", "Debug", null)
            payload["error"] = errorText ?: ""
            payload["file_content"] = editor.document.text
            payload["current_file"] = e.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: ""
        } else {
            payload["selected_code"] = selectedText
            payload["action_type"] = actionType
            payload["language"] = e.getData(CommonDataKeys.VIRTUAL_FILE)?.extension ?: "plaintext"
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val req = HttpRequest.newBuilder().uri(URI.create("$apiBase$endpoint"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(payload))).build()

                val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                val data = Gson().fromJson(res.body(), Map::class.java) as Map<*, *>

                ApplicationManager.getApplication().invokeLater {
                    val result = data["fix"]?.toString() ?: data["result"]?.toString() ?: ""

                    if (actionType == "explain") {
                        val escapedContent = Gson().toJson(result)
                        val js = """
                            window.postMessage({ 
                                type: 'addBotResponse', 
                                title: 'Explanation for Selected Code', 
                                content: $escapedContent 
                            }, '*');
                        """.trimIndent()
                        HeaterToolWindowFactory.currentBrowser?.cefBrowser?.executeJavaScript(js, "about:blank", 0)
                    } else {
                        Messages.showInfoMessage(project, result, "Heater AI Result")
                    }
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("Server Unreachable: ${ex.message}", "Error")
                }
            }
        }
    }
}

class InspectFolderAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val folderPath = if (virtualFile != null && virtualFile.isDirectory) {
            virtualFile.path
        } else if (virtualFile != null) {
            virtualFile.parent.path
        } else {
            project.basePath ?: ""
        }

        if (folderPath.isBlank()) {
            Messages.showWarningDialog(project, "No folder found to inspect.", "Warning")
            return
        }

        val apiBase = HeaterSettings.instance.state.apiBaseUrl
        val payload = mapOf("path" to folderPath)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$apiBase/api/coder/inspect"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(payload)))
                    .build()

                val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                val data = Gson().fromJson(res.body(), Map::class.java) as Map<*, *>

                ApplicationManager.getApplication().invokeLater {
                    if (data["success"] == true) {
                        val resultText = "# Folder Analysis ($folderPath)\n\n${data["data"]}"
                        val lightFile = LightVirtualFile("FolderAnalysis.md", resultText)
                        FileEditorManager.getInstance(project).openFile(lightFile, true)
                    } else {
                        Messages.showErrorDialog(project, "Error: ${data["error"]}", "Inspection Failed")
                    }
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Server Unreachable: ${ex.message}", "Error")
                }
            }
        }
    }
}

class TrainSnippetAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        var content = editor.selectionModel.selectedText
        if (content.isNullOrBlank()) {
            content = editor.document.text
        }

        if (content.isNullOrBlank()) {
            Messages.showWarningDialog(project, "No code/text to train.", "Warning")
            return
        }

        val filename = virtualFile?.name ?: "Snippet"
        val apiBase = HeaterSettings.instance.state.apiBaseUrl
        val payload = mapOf("content" to content, "filename" to filename)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$apiBase/api/coder/ide/train-snippet"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(payload)))
                    .build()

                val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                val data = Gson().fromJson(res.body(), Map::class.java) as Map<*, *>

                ApplicationManager.getApplication().invokeLater {
                    if (data["success"] == true) {
                        Messages.showInfoMessage(project, "✅ ${data["message"]}", "Training Successful")
                    } else {
                        Messages.showErrorDialog(project, "Training failed: ${data["error"]}", "Error")
                    }
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Server Unreachable: ${ex.message}", "Error")
                }
            }
        }
    }
}

object ApiClient {
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    fun sendInlineAction(selectedCode: String, actionType: String, language: String): String {
        val apiBase = HeaterSettings.instance.state.apiBaseUrl
        val payload = mapOf(
            "selected_code" to selectedCode,
            "action_type" to actionType,
            "language" to language,
            "use_knowledge_base" to true
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBase/api/coder/ide/inline-action"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val responseData = gson.fromJson(response.body(), Map::class.java) as Map<*, *>

        return if (responseData["success"] == true) {
            responseData["result"].toString()
        } else {
            "Error: ${responseData["error"]}"
        }
    }
}