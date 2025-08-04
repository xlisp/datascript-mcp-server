

```
clojure -X:mcp-data-viz
Data Visualization MCP Server running on STDIO transport.
Available tools:
- network_graph: Generate relationship/network graphs
- line_chart: Generate line charts from data points
- bar_chart: Generate bar charts from labeled data
- scatter_plot: Generate scatter plots from coordinate pairs

{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
...
```

```
  "data-viz-server": {
    "command": "/bin/bash",
    "args": [
      "-c",
      "cd /Users/clojure/Desktop/datascript-mcp-server && /usr/local/bin/clojure -M -m datascript-mcp.data-viz-mcp"
    ]
  }

```
