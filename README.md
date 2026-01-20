# mkpro - AI Coding & Research Assistant

`mkpro` is a sophisticated CLI-based AI assistant built using the Google Agent Development Kit (ADK). It features a multi-agent architecture and supports local models (via Ollama) and cloud models (via Gemini API and AWS Bedrock).

## Features

- 🤖 **Multi-Agent Architecture**:
    - **Coordinator**: Orchestrates the workflow, performs research, and manages long-term memory.
    - **Coder**: Specialized in reading/writing files and analyzing project structure.
    - **SysAdmin**: Handles shell command execution and system-level tasks.
- 🏢 **Central Memory**: Persist project summaries across sessions in a central database located in your home directory (`~/.mkpro/central_memory.db`).
- 🌐 **Multi-Provider Support**: Seamlessly switch between **Ollama** (local), **Gemini** (Google Cloud), and **Bedrock** (AWS) providers.
- 📂 **Local File Access**: Full capability to read and modify your codebase safely.
- 💻 **Shell Execution**: Run shell commands directly with automatic state saving via Git.
- 🖼️ **Image Analysis**: Analyze local image files by referencing them in your prompts.
- 📅 **Context Aware**: Agents are automatically aware of the current date and working directory.
- 🔄 **Context Management**: Reset or compact sessions to manage the LLM's context window effectively.

## Prerequisites

- **Java 17+**: Required for building and running.
- **Maven**: Required for building.
- **Ollama**: (Optional) For running local models. Ensure it is running on `http://localhost:11434`.
- **Google API Key**: (Optional) Required for Gemini models. Set the `GOOGLE_API_KEY` environment variable.
- **AWS Credentials**: (Optional) Required for Bedrock models. Ensure your environment is configured with AWS credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`) and a default region (`AWS_REGION`).

## Setup

1. **Configure Providers**:
   - **Gemini**: `set GOOGLE_API_KEY=your_api_key`
   - **Bedrock**: Use `aws configure` or set `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_REGION` environment variables.
   - **Ollama**: Ensure the local daemon is running.

## Building

To build the fat JAR and the native `.exe`:

```bash
mvn clean package
```

The output will be generated in the `target/` directory:
- `target/mkpro-1.4-SNAPSHOT-shaded.jar` (Fat JAR)
- `target/mkpro.exe` (Native Windows Executable)

## Running

### Using the Executable (Recommended)
```bash
./target/mkpro.exe
```

### Using Java
```bash
java -jar target/mkpro-1.4-SNAPSHOT-shaded.jar
```

## Commands

Inside the CLI, you can use the following commands:

- **/help** (or **/h**): Display available commands.
- **/provider**: Interactively switch between **OLLAMA**, **GEMINI**, and **BEDROCK** providers.
- **/models**: List models available for the active provider.
- **/model**: Select a model numerically (current model is marked as default).
- **/init**: Initialize project memory in the central database (if not already present).
- **/re-init**: Refresh/Update the project summary in the central database.
- **/remember**: Manually trigger a project analysis and save to central memory.
- **/compact**: Summarize current history and start a fresh session with that summary (saves tokens).
- **/reset**: Clear the current session memory entirely.
- **/summarize**: Export a session summary to `session_summary.txt`.
- **exit**: Quit the application.

## Usage Examples

Once the `> ` prompt appears, you can try:

- **Initialize Project**: `/init` (Let the agents learn your project structure).
- **Coding Task**: "Add a logger to the main method in MkPro.java."
- **System Task**: "Run a maven build and tell me if it passes."
- **Multi-Agent delegation**: The Coordinator will automatically ask the Coder to read files and the SysAdmin to run tests.
- **Recall Memory**: "What do you know about other projects in my central store?"
- **Switch Models**: `/provider` followed by `/model` to try different LLMs across providers.

## Maintenance

The agent configuration and system prompts are located in `src/main/java/com/mkpro/MkPro.java`. Interaction logs are stored in `mkpro_logs.db`.