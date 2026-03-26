# McG - MCP Server for Ghidra

McG is a [Ghidra](https://ghidra-sre.org/) extension that embeds a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server, exposing reverse engineering capabilities to LLM agents over SSE transport.

This project is a fork of [suidpit/ghidra-mcp](https://github.com/suidpit/ghidra-mcp) with additional features including multi-instance support, robust port management, and automated CI/CD for new Ghidra releases.

## Tools

| Tool | Description |
|------|-------------|
| `listOpenPrograms` | List all open programs across CodeBrowser windows, showing which is active |
| `selectProgram` | Switch which open binary to operate on |
| `listFunctions` | List all functions in the current program |
| `getFunctionAddressByName` | Get function entry point address |
| `decompileFunctionByName` | Decompile a function to C pseudocode |
| `renameFunction` | Rename a function |
| `addCommentToFunction` | Add a comment to a function |
| `renameLocalVariableInFunction` | Rename a local variable within a function |
| `getReferencesToAddress` | Get cross-references to an address |
| `getReferencesFromAddress` | Get cross-references from an address |
| `getFunctionCallers` | Get functions that call a given function |
| `searchForStrings` | Search for strings in program memory (min 5 chars) |

Multiple CodeBrowser windows are supported. The server automatically selects the first window with an open binary, or you can use `selectProgram` to target a specific one.

## Installation

Download the extension ZIP from the [releases](https://github.com/brianredbeard/ghidra-mcp/releases) page matching your Ghidra version.

In Ghidra: **File > Install Extensions > "+"** and select the ZIP. Then open the Code Browser, go to **File > Configure > Developer** and enable **GhidraMCPPlugin**.

The MCP server starts automatically. It defaults to port 8888 and auto-selects the next available port if occupied. Check the Ghidra console for the actual SSE URL.

## Build

Requires `GHIDRA_INSTALL_DIR` pointing to your Ghidra installation. The Java release target and Gradle version are read from Ghidra automatically.

```bash
export GHIDRA_INSTALL_DIR=/path/to/ghidra
./gradlew clean distributeExtension
```

The extension ZIP will be in `dist/`.

To run unit tests (no Ghidra installation required):

```bash
./gradlew test
```

## Usage

Start Ghidra and open a binary in the Code Browser. The server generates a ready-to-use MCP client config at `~/.ghidra-mcp/mcp.json` with the correct port.

Symlink it into your project once — it updates automatically on every server start:

```bash
ln -sf ~/.ghidra-mcp/mcp.json .mcp.json
```

Or add the config manually:

```json
{
  "mcpServers": {
    "McG": {
      "type": "sse",
      "url": "http://localhost:8888/sse"
    }
  }
}
```

When running multiple Ghidra instances, per-instance configs are at `~/.ghidra-mcp/mcp.<port>.json`.

## Configuration

### Port

The server defaults to port 8888 and scans up to 10 ports if occupied. To override, set the `GHIDRA_MCP_PORT` environment variable before launching Ghidra:

```bash
export GHIDRA_MCP_PORT=9999
ghidraRun
```

Explicit overrides fail if the port is unavailable (no scanning), ensuring your client config matches.

### Discovery

The server writes to `~/.ghidra-mcp/` on startup:

| File | Purpose |
|------|---------|
| `mcp.json` | MCP client config for the latest instance (symlink target) |
| `mcp.<port>.json` | Per-instance config keyed by port (e.g., `mcp.8888.json`) |

Stale config files are cleaned automatically by checking if the port is still in use.

## CI/CD

A Forgejo Actions workflow monitors [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra) releases daily. When a new version is detected, it:

1. Downloads and extracts the Ghidra release
2. Builds the extension against it
3. Runs the test suite
4. Creates a tagged release with the extension ZIP attached
5. Commits updated version tracking files

Manual builds can be triggered via `workflow_dispatch` with an optional Ghidra version and force-rebuild flag.

## Attribution

This project is based on [ghidra-mcp](https://github.com/suidpit/ghidra-mcp) by [suidpit](https://github.com/suidpit), originally created in March 2025. The original work demonstrated embedding a Spring Boot MCP server inside a Ghidra plugin — a creative approach to bridging LLM agents with reverse engineering tools.

## License

[Apache License 2.0](LICENSE)
