# GPU-Heater Coder

GPU-Heater Code Assistant & Architect for JetBrains IDEs.

## 🚀 Features

*   **FIM Auto-Complete**: Real-time ghost text code completion (Fill-In-the-Middle) as you type.
*   **AI Editor Actions**: Highlight code, right-click, and choose from the **GPU-Heater** menu to:
    *   **Fix Error / Debug**: Provide error logs and get automated fixes.
    *   **Refactor Code**: Optimize and improve selected code snippets.
    *   **Explain Code**: Get detailed explanations for complex logic directly in the chat window.
    *   **Generate Tests**: Automatically create unit tests for your functions.
*   **Train with Selection**: Send specific code snippets to your local Vector DB to improve future AI suggestions and context.
*   **Inspect Workspace / Folder**: Right-click any folder in the Project View to run an AI analysis on the directory contents.
*   **Dedicated Tool Window**: A built-in JCEF-powered chat interface for interactive coding assistance and context sharing.

## ⚙️ Configuration

To connect the plugin to your backend:
1. Open your IDE Settings/Preferences.
2. Navigate to **Tools > GPU-Heater AI**.
3. Configure your **Backend API Base URL** (Default is `http://127.0.0.1:2004`).
4. (Optional) Toggle Auto-Complete and set your preferred language via the tool window interface.

## 📋 Requirements

*   IntelliJ IDEA or any compatible JetBrains IDE (Platform module dependent).
*   **JCEF (Java Chromium Embedded Framework)** support must be enabled in your IDE to render the tool window UI.
*   An active **GPU-Heater backend server** running at the configured API URL to process LLM requests.


## 📥 Installation
*  Download this ZIP from Releases
*  Open Settings Menu then Plugins Option
*  Click gear (right of Installed) and click "Install Plugin from Disk"
*  Select gpu-heater-jetbrains.zip from Downloads

## 🛠️ Usage

*   **Auto-Complete**: Simply start typing. If enabled, ghost text will appear. Press the designated key (usually Tab) to accept.
*   **Context Menu**: Right-click in the editor or project explorer to access GPU-Heater actions.
*   **Chat & Explanation**: Open the `GPU-Heater` tool window anchored on the right side of your IDE to interact with the AI directly.
