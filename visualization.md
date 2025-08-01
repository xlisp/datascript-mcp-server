
## visualization 

```
$ clojure -X:mcp-visual

{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
...

```

## config

```

  "datascript-viz-server": {
    "command": "/bin/bash",
    "args": [
      "-c",
      "cd /Users/clojure/Desktop/datascript-mcp-server-ok111 && /usr/local/bin/clojure -M -m datascript-mcp.visualization"
    ]
  }


```
