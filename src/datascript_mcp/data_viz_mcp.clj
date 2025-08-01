(ns datascript-mcp.data-viz-mcp
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:gen-class)
  (:import [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.server McpServer McpServerFeatures
            McpServerFeatures$AsyncToolSpecification]
           [io.modelcontextprotocol.spec
            McpSchema$ServerCapabilities
            McpSchema$Tool
            McpSchema$CallToolResult
            McpSchema$TextContent]
           [reactor.core.publisher Mono]
           [com.fasterxml.jackson.databind ObjectMapper]))

;; SVG helper functions
(defn svg-header [width height]
  (format "<svg width=\"%d\" height=\"%d\" xmlns=\"http://www.w3.org/2000/svg\">" width height))

(defn svg-footer []
  "</svg>")

(defn svg-circle [x y r fill stroke]
  (format "<circle cx=\"%d\" cy=\"%d\" r=\"%d\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"/>"
          x y r fill stroke))

(defn svg-line [x1 y1 x2 y2 stroke]
  (format "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2\"/>"
          x1 y1 x2 y2 stroke))

(defn svg-text [x y text color size]
  (format "<text x=\"%d\" y=\"%d\" font-family=\"Arial\" font-size=\"%d\" fill=\"%s\">%s</text>"
          x y size color text))

(defn svg-path [d fill stroke]
  (format "<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"2\"/>"
          d fill stroke))

;; Network/Relationship Graph Generator
(defn generate-network-graph [nodes edges]
  (let [width 800
        height 600
        node-radius 20
        center-x (/ width 2)
        center-y (/ height 2)
        angle-step (/ (* 2 Math/PI) (count nodes))
        radius 200
        node-positions (into {} (map-indexed (fn [i node] [node i]) nodes))]
    
    (str
     (svg-header width height)
     
     ;; Background
     "<rect width=\"100%\" height=\"100%\" fill=\"#f8f9fa\"/>"
     
     ;; Draw edges first (so they appear behind nodes)
     (str/join
      (for [edge edges
            :let [from-idx (get node-positions (:from edge))
                  to-idx (get node-positions (:to edge))]
            :when (and from-idx to-idx)
            :let [from-x (+ center-x (* radius (Math/cos (* (double from-idx) angle-step))))
                  from-y (+ center-y (* radius (Math/sin (* (double from-idx) angle-step))))
                  to-x (+ center-x (* radius (Math/cos (* (double to-idx) angle-step))))
                  to-y (+ center-y (* radius (Math/sin (* (double to-idx) angle-step))))]]
        (svg-line (int from-x) (int from-y) (int to-x) (int to-y) "#666")))
     
     ;; Draw nodes
     (str/join
      (map-indexed
       (fn [i node]
         (let [x (+ center-x (* radius (Math/cos (* (double i) angle-step))))
               y (+ center-y (* radius (Math/sin (* (double i) angle-step))))]
           (str
            (svg-circle (int x) (int y) node-radius "#4285f4" "#333")
            (svg-text (int (- x 10)) (int (+ y 5)) (str node) "white" 12))))
       nodes))
     
     (svg-footer))))

;; Line Chart Generator
(defn generate-line-chart [data]
  (let [width 800
        height 400
        margin 50
        chart-width (- width (* 2 margin))
        chart-height (- height (* 2 margin))
        x-values (map first data)
        y-values (map second data)
        x-min (double (apply min x-values))
        x-max (double (apply max x-values))
        y-min (double (apply min y-values))
        y-max (double (apply max y-values))
        x-range (- x-max x-min)
        y-range (- y-max y-min)]
    
    (str
     (svg-header width height)
     
     ;; Background
     "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
     
     ;; Grid lines
     (str/join
      (for [i (range 0 (inc chart-width) (/ chart-width 10))]
        (svg-line (int (+ margin i)) margin (int (+ margin i)) (+ margin chart-height) "#e0e0e0")))
     
     (str/join
      (for [i (range 0 (inc chart-height) (/ chart-height 10))]
        (svg-line margin (int (+ margin i)) (+ margin chart-width) (int (+ margin i)) "#e0e0e0")))
     
     ;; Axes
     (svg-line margin margin margin (+ margin chart-height) "#333")
     (svg-line margin (+ margin chart-height) (+ margin chart-width) (+ margin chart-height) "#333")
     
     ;; Data line
     (let [path-data
           (str "M "
                (str/join " L "
                          (map (fn [[x y]]
                                 (let [scaled-x (+ margin (* (/ (- (double x) x-min) x-range) chart-width))
                                       scaled-y (+ margin (- chart-height (* (/ (- (double y) y-min) y-range) chart-height)))]
                                   (format "%.1f,%.1f" scaled-x scaled-y)))
                               data)))]
       (svg-path path-data "none" "#4285f4"))
     
     ;; Data points
     (str/join
      (map (fn [[x y]]
             (let [scaled-x (+ margin (* (/ (- (double x) x-min) x-range) chart-width))
                   scaled-y (+ margin (- chart-height (* (/ (- (double y) y-min) y-range) chart-height)))]
               (svg-circle (int scaled-x) (int scaled-y) 4 "#4285f4" "#333")))
           data))
     
     ;; Labels
     (svg-text (/ width 2) (- height 10) "X Axis" "#333" 14)
     (svg-text 15 (/ height 2) "Y" "#333" 14)
     
     (svg-footer))))

;; Bar Chart Generator
(defn generate-bar-chart [data]
  (let [width 800
        height 400
        margin 50
        chart-width (- width (* 2 margin))
        chart-height (- height (* 2 margin))
        bar-width (/ chart-width (count data))
        y-max (double (apply max (map second data)))
        y-min 0.0
        y-range (- y-max y-min)]
    
    (str
     (svg-header width height)
     
     ;; Background
     "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
     
     ;; Axes
     (svg-line margin margin margin (+ margin chart-height) "#333")
     (svg-line margin (+ margin chart-height) (+ margin chart-width) (+ margin chart-height) "#333")
     
     ;; Bars
     (str/join
      (map-indexed
       (fn [i [label value]]
         (let [x (+ margin (* i bar-width))
               bar-height (* (/ (double value) y-range) chart-height)
               y (+ margin (- chart-height bar-height))]
           (str
            (format "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#4285f4\" stroke=\"#333\"/>"
                    (+ x 5) y (- bar-width 10) bar-height)
            (svg-text (int (+ x (/ bar-width 2) -5)) (+ margin chart-height 20) (str label) "#333" 12))))
       data))
     
     (svg-footer))))

;; Scatter Plot Generator
(defn generate-scatter-plot [data]
  (let [width 800
        height 400
        margin 50
        chart-width (- width (* 2 margin))
        chart-height (- height (* 2 margin))
        x-values (map first data)
        y-values (map second data)
        x-min (double (apply min x-values))
        x-max (double (apply max x-values))
        y-min (double (apply min y-values))
        y-max (double (apply max y-values))
        x-range (- x-max x-min)
        y-range (- y-max y-min)]
    
    (str
     (svg-header width height)
     
     ;; Background
     "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
     
     ;; Axes
     (svg-line margin margin margin (+ margin chart-height) "#333")
     (svg-line margin (+ margin chart-height) (+ margin chart-width) (+ margin chart-height) "#333")
     
     ;; Data points
     (str/join
      (map (fn [[x y]]
             (let [scaled-x (+ margin (* (/ (- (double x) x-min) x-range) chart-width))
                   scaled-y (+ margin (- chart-height (* (/ (- (double y) y-min) y-range) chart-height)))]
               (svg-circle (int scaled-x) (int scaled-y) 5 "#4285f4" "#333")))
           data))
     
     (svg-footer))))

;; Tool result constructors
(defn text-content [^String s]
  (McpSchema$TextContent. s))

(defn text-result [^String s]
  (McpSchema$CallToolResult. [(text-content s)] false))

;; Network Graph Tool
(def network-graph-schema
  (json/write-str {:type :object
                   :properties {:nodes {:type :array
                                       :items {:type :string}
                                       :description "Array of node names"}
                               :edges {:type :array
                                      :items {:type :object
                                             :properties {:from {:type :string}
                                                         :to {:type :string}}
                                             :required [:from :to]}
                                      :description "Array of edge objects with from/to properties"}}
                   :required [:nodes :edges]}))

(defn network-graph-callback [exchange arguments]
  (let [nodes (get arguments "nodes")
        edges (map #(hash-map :from (get % "from") :to (get % "to")) 
                   (get arguments "edges"))
        svg (generate-network-graph nodes edges)]
    (text-result svg)))

(def network-graph-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "network_graph" 
                   "Generates a network/relationship graph SVG. Takes nodes (array of strings) and edges (array of {from, to} objects)"
                   network-graph-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/just (network-graph-callback exchange arguments))))))

;; Line Chart Tool
(def line-chart-schema
  (json/write-str {:type :object
                   :properties {:data {:type :array
                                      :items {:type :array
                                             :items {:type :number}
                                             :minItems 2
                                             :maxItems 2}
                                      :description "Array of [x, y] coordinate pairs"}}
                   :required [:data]}))

(defn line-chart-callback [exchange arguments]
  (let [data (map vec (get arguments "data"))
        svg (generate-line-chart data)]
    (text-result svg)))

(def line-chart-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "line_chart"
                   "Generates a line chart SVG from data points. Takes an array of [x, y] coordinates"
                   line-chart-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/just (line-chart-callback exchange arguments))))))

;; Bar Chart Tool
(def bar-chart-schema
  (json/write-str {:type :object
                   :properties {:data {:type :array
                                      :items {:type :array
                                             :items {:anyOf [{:type :string} {:type :number}]}
                                             :minItems 2
                                             :maxItems 2}
                                      :description "Array of [label, value] pairs"}}
                   :required [:data]}))

(defn bar-chart-callback [exchange arguments]
  (let [data (get arguments "data")
        svg (generate-bar-chart data)]
    (text-result svg)))

(def bar-chart-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "bar_chart"
                   "Generates a bar chart SVG from labeled data. Takes an array of [label, value] pairs"
                   bar-chart-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/just (bar-chart-callback exchange arguments))))))

;; Scatter Plot Tool
(def scatter-plot-schema
  (json/write-str {:type :object
                   :properties {:data {:type :array
                                      :items {:type :array
                                             :items {:type :number}
                                             :minItems 2
                                             :maxItems 2}
                                      :description "Array of [x, y] coordinate pairs"}}
                   :required [:data]}))

(defn scatter-plot-callback [exchange arguments]
  (let [data (map vec (get arguments "data"))
        svg (generate-scatter-plot data)]
    (text-result svg)))

(def scatter-plot-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "scatter_plot"
                   "Generates a scatter plot SVG from data points. Takes an array of [x, y] coordinates"
                   scatter-plot-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/just (scatter-plot-callback exchange arguments))))))

;; Main server function
(defn mcp-server [& args]
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "data-viz-server" "1.0.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    
    ;; Add all visualization tools
    (-> (.addTool server network-graph-tool) (.subscribe))
    (-> (.addTool server line-chart-tool) (.subscribe))
    (-> (.addTool server bar-chart-tool) (.subscribe))
    (-> (.addTool server scatter-plot-tool) (.subscribe))
    
    (println "Data Visualization MCP Server running on STDIO transport.")
    (println "Available tools:")
    (println "- network_graph: Generate relationship/network graphs")
    (println "- line_chart: Generate line charts from data points")
    (println "- bar_chart: Generate bar charts from labeled data")
    (println "- scatter_plot: Generate scatter plots from coordinate pairs")
    
    server))

(defn -main [& args]
  (let [server (mcp-server args)]
    ;; Keep the process alive
    (while true
      (Thread/sleep 1000))))

(comment
  ;; REPL testing examples:
  
  ;; Test network graph
  (generate-network-graph 
   ["Alice" "Bob" "Charlie" "David"]
   [{:from "Alice" :to "Bob"} 
    {:from "Bob" :to "Charlie"} 
    {:from "Charlie" :to "David"}
    {:from "David" :to "Alice"}])
  
  ;; Test line chart
  (generate-line-chart [[1 2] [2 4] [3 1] [4 8] [5 3]])
  
  ;; Test bar chart
  (generate-bar-chart [["A" 10] ["B" 25] ["C" 15] ["D" 30]])
  
  ;; Test scatter plot
  (generate-scatter-plot [[1 2] [2 3] [3 1] [4 5] [5 4]])
  
  ;; Start server
  (mcp-server)
  )

