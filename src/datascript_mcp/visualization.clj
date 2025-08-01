(ns datascript-mcp.visualization
  (:require [clojure.data.json :as json]
            [datascript.core :as d]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io])
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
           [com.fasterxml.jackson.databind ObjectMapper]
           [java.io File]))

;; Global database atom - separate from main server
(def viz-db (atom nil))

;; Default schema for visualization examples
(def viz-schema
  {:person/name {:db/type :db.type/string
                 :db/unique :db.unique/identity}
   :person/age {:db/type :db.type/long}
   :person/email {:db/type :db.type/string}
   :person/friends {:db/type :db.type/ref
                    :db/cardinality :db.cardinality/many}
   :person/parent {:db/type :db.type/ref}
   :person/children {:db/type :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :person/spouse {:db/type :db.type/ref}
   :company/name {:db/type :db.type/string
                  :db/unique :db.unique/identity}
   :person/works-for {:db/type :db.type/ref}
   :company/employees {:db/type :db.type/ref
                       :db/cardinality :db.cardinality/many}
   :project/name {:db/type :db.type/string
                  :db/unique :db.unique/identity}
   :project/depends-on {:db/type :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :person/works-on {:db/type :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :module/name {:db/type :db.type/string
                 :db/unique :db.unique/identity}
   :module/imports {:db/type :db.type/ref
                    :db/cardinality :db.cardinality/many}})

;; Utility functions
(defn capture-output [k]
  (let [out-atom (atom "")
        err-atom (atom "")
        res (atom nil)]
    (binding [*out* (java.io.StringWriter.)
              *err* (java.io.StringWriter.)]
      (try
        (reset! res (k))
        (catch Exception e
          (reset! err-atom (str e))))
      (reset! out-atom (str *out*))
      (reset! err-atom (str @err-atom (str *err*))))
    {:result @res :out @out-atom :err @err-atom}))

(defn text-content [^String s]
  (McpSchema$TextContent. s))

(defn text-result [^String s]
  (McpSchema$CallToolResult. [(text-content s)] false))

(defn format-result [result]
  (if (string? result)
    result
    (pr-str result)))

;; Initialize visualization database
(defn init-viz-db! []
  (reset! viz-db (d/create-conn viz-schema)))

;; Helper function to get entity display name
(defn get-entity-display-name [entity]
  (or (:person/name entity)
      (:company/name entity)
      (:project/name entity)
      (:module/name entity)
      (str "Entity-" (:db/id entity))))

;; Helper function to get entity shape and color for Graphviz
(defn get-entity-style [entity]
  (cond
    (:person/name entity) {:shape "ellipse" :color "lightblue" :style "filled"}
    (:company/name entity) {:shape "box" :color "lightgreen" :style "filled"}
    (:project/name entity) {:shape "diamond" :color "lightyellow" :style "filled"}
    (:module/name entity) {:shape "hexagon" :color "lightcoral" :style "filled"}
    :else {:shape "circle" :color "lightgray" :style "filled"}))

;; Generate Graphviz DOT format
(defn generate-dot-graph [entities relationships title]
  (let [dot-header (str "digraph \"" title "\" {\n"
                        "  rankdir=LR;\n"
                        "  node [fontname=\"Arial\", fontsize=10];\n"
                        "  edge [fontname=\"Arial\", fontsize=8];\n\n")]
    (str dot-header
         ;; Entity definitions
         (str/join "\n" 
                   (map (fn [[id entity]]
                          (let [name (get-entity-display-name entity)
                                style (get-entity-style entity)]
                            (format "  \"%s\" [label=\"%s\", shape=%s, color=%s, style=%s];"
                                    id name (:shape style) (:color style) (:style style))))
                        entities))
         "\n\n"
         ;; Relationship definitions
         (str/join "\n"
                   (map (fn [{:keys [from to label]}]
                          (format "  \"%s\" -> \"%s\" [label=\"%s\"];" from to label))
                        relationships))
         "\n}\n")))

;; Extract relationships from database
(defn extract-relationships 
  ([db] (extract-relationships db nil))
  ([db entity-filter]
   (let [all-datoms (d/datoms db :eavt)
         entities (atom {})
         relationships (atom [])]
     (doseq [datom all-datoms]
       (let [e (:e datom)
             a (:a datom)
             v (:v datom)]
         ;; Store entity info
         (when-not (@entities e)
           (swap! entities assoc e (d/entity db e)))
         
         ;; Check if this is a reference attribute
         (when (and (keyword? a)
                    (not= a :db/id)
                    (map? v)
                    (:db/id v))
           (let [target-id (:db/id v)]
             ;; Store target entity info
             (when-not (@entities target-id)
               (swap! entities assoc target-id v))
             
             ;; Add relationship if entities match filter
             (when (or (nil? entity-filter)
                       (entity-filter e)
                       (entity-filter target-id))
               (swap! relationships conj {:from e
                                          :to target-id
                                          :label (name a)}))))))
     
     ;; Filter entities if needed
     (let [filtered-entities (if entity-filter
                               (into {} (filter (fn [[id _]] (entity-filter id)) @entities))
                               @entities)]
       {:entities filtered-entities
        :relationships @relationships}))))

;; 1. Initialize Visualization Database Tool
(def init-viz-db-schema
  (json/write-str {:type :object
                   :properties {:schema {:type :string
                                        :description "Optional custom schema as EDN string"}}
                   :required []}))

(defn init-viz-db-callback [exchange arguments continuation]
  (future
    (let [custom-schema-str (get arguments "schema")
          schema (if (and custom-schema-str (not (str/blank? custom-schema-str)))
                   (try
                     (read-string custom-schema-str)
                     (catch Exception e
                       (continuation (text-result (str "Error parsing schema: " (.getMessage e))))))
                   viz-schema)
          {:keys [result err]} (capture-output
                                #(do
                                   (reset! viz-db (d/create-conn schema))
                                   "Visualization database initialized successfully"))]
      (if (str/blank? err)
        (continuation (text-result result))
        (continuation (text-result (str "Error: " err)))))))

(def init-viz-db-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "init_viz_db" "Initialize visualization database with optional custom schema" init-viz-db-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (init-viz-db-callback exchange arguments #(.success sink %)))))))))

;; 2. Add Visualization Data Tool
(def add-viz-data-schema
  (json/write-str {:type :object
                   :properties {:data {:type :string
                                      :description "Data to add as EDN vector of entity maps"}}
                   :required [:data]}))

(defn add-viz-data-callback [exchange arguments continuation]
  (future
    (if-not @viz-db
      (continuation (text-result "Visualization database not initialized. Please run init_viz_db first."))
      (let [data-str (get arguments "data")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [data (read-string data-str)
                                           tx-result (d/transact! @viz-db data)]
                                       (str "Successfully added " (count (:tx-data tx-result)) " datoms to visualization database"))
                                     (catch Exception e
                                       (str "Error adding data: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def add-viz-data-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "add_viz_data" "Add data to the visualization database" add-viz-data-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (add-viz-data-callback exchange arguments #(.success sink %)))))))))

;; 3. Generate Graph Visualization Tool
(def generate-graph-schema
  (json/write-str {:type :object
                   :properties {:title {:type :string
                                       :description "Graph title (default: 'Entity Relationship Graph')"}
                               :entities {:type :string
                                         :description "Optional filter: entity IDs as EDN vector to include"}
                               :output-format {:type :string
                                             :description "Output format: 'dot', 'svg', 'png' (default: 'dot')"}
                               :output-file {:type :string
                                           :description "Output file path (optional, will generate temp file if not provided)"}}
                   :required []}))

(defn generate-graph-callback [exchange arguments continuation]
  (future
    (if-not @viz-db
      (continuation (text-result "Visualization database not initialized. Please run init_viz_db first."))
      (let [title (get arguments "title" "Entity Relationship Graph")
            entities-str (get arguments "entities")
            output-format (get arguments "output-format" "dot")
            output-file (get arguments "output-file")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [db @(deref viz-db)
                                           entity-filter (if (and entities-str (not (str/blank? entities-str)))
                                                           (let [entity-ids (read-string entities-str)]
                                                             (set entity-ids))
                                                           nil)
                                           {:keys [entities relationships]} (extract-relationships db entity-filter)
                                           dot-content (generate-dot-graph entities relationships title)]
                                       
                                       (cond
                                         (= output-format "dot")
                                         (if output-file
                                           (do
                                             (spit output-file dot-content)
                                             (str "DOT file saved to: " output-file "\n\n" dot-content))
                                           dot-content)
                                         
                                         (or (= output-format "svg") (= output-format "png"))
                                         (let [temp-dot-file (File/createTempFile "graph" ".dot")
                                               dot-path (.getAbsolutePath temp-dot-file)
                                               output-path (or output-file
                                                             (.getAbsolutePath 
                                                              (File/createTempFile "graph" (str "." output-format))))]
                                           (spit dot-path dot-content)
                                           (let [result (shell/sh "dot" (str "-T" output-format) dot-path "-o" output-path)]
                                             (.delete temp-dot-file)
                                             (if (= (:exit result) 0)
                                               (str "Graph saved to: " output-path "\n"
                                                    "Entities: " (count entities) "\n"
                                                    "Relationships: " (count relationships))
                                               (str "Error generating " output-format ": " (:err result)))))
                                         
                                         :else
                                         (str "Unsupported output format: " output-format)))
                                     (catch Exception e
                                       (str "Error generating graph: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def generate-graph-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "generate_graph" "Generate Graphviz visualization of entity relationships" generate-graph-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (generate-graph-callback exchange arguments #(.success sink %)))))))))

;; 4. Show Entity Neighborhood Tool
(def show-neighborhood-schema
  (json/write-str {:type :object
                   :properties {:entity {:type :string
                                        :description "Entity ID or lookup ref as EDN"}
                               :depth {:type :integer
                                      :description "Neighborhood depth (default: 1)"}
                               :title {:type :string
                                      :description "Graph title"}
                               :output-format {:type :string
                                             :description "Output format: 'dot', 'svg', 'png' (default: 'dot')"}
                               :output-file {:type :string
                                           :description "Output file path (optional)"}}
                   :required [:entity]}))

(defn get-entity-neighborhood [db entity-id depth]
  (let [visited (atom #{})
        neighborhood (atom #{entity-id})]
    (letfn [(explore [current-ids current-depth]
              (when (> current-depth 0)
                (let [new-ids (atom #{})]
                  (doseq [id current-ids]
                    (when-not (@visited id)
                      (swap! visited conj id)
                      (let [entity (d/entity db id)]
                        ;; Find outgoing references
                        (doseq [[attr value] entity]
                          (when (and (keyword? attr) (not= attr :db/id))
                            (cond
                              (and (map? value) (:db/id value))
                              (let [target-id (:db/id value)]
                                (swap! neighborhood conj target-id)
                                (swap! new-ids conj target-id))
                              
                              (and (coll? value) (every? #(and (map? %) (:db/id %)) value))
                              (doseq [ref-entity value]
                                (let [target-id (:db/id ref-entity)]
                                  (swap! neighborhood conj target-id)
                                  (swap! new-ids conj target-id))))))
                        
                        ;; Find incoming references
                        (let [incoming (d/q '[:find [?e ...]
                                             :in $ ?target
                                             :where [?e _ ?target]]
                                            db id)]
                          (doseq [source-id incoming]
                            (swap! neighborhood conj source-id)
                            (swap! new-ids conj source-id))))))
                  (when (seq @new-ids)
                    (explore @new-ids (dec current-depth))))))]
      (explore #{entity-id} depth)
      @neighborhood)))

(defn show-neighborhood-callback [exchange arguments continuation]
  (future
    (if-not @viz-db
      (continuation (text-result "Visualization database not initialized. Please run init_viz_db first."))
      (let [entity-str (get arguments "entity")
            depth (get arguments "depth" 1)
            title (get arguments "title")
            output-format (get arguments "output-format" "dot")
            output-file (get arguments "output-file")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [db @(deref viz-db)
                                           entity-ref (read-string entity-str)
                                           entity-id (if (number? entity-ref)
                                                       entity-ref
                                                       (:db/id (d/entity db entity-ref)))
                                           neighborhood-ids (get-entity-neighborhood db entity-id depth)
                                           entity-filter (set neighborhood-ids)
                                           {:keys [entities relationships]} (extract-relationships db entity-filter)
                                           graph-title (or title 
                                                          (str "Neighborhood of " 
                                                               (get-entity-display-name (d/entity db entity-id))
                                                               " (depth " depth ")"))
                                           dot-content (generate-dot-graph entities relationships graph-title)]
                                       
                                       (cond
                                         (= output-format "dot")
                                         (if output-file
                                           (do
                                             (spit output-file dot-content)
                                             (str "DOT file saved to: " output-file "\n\n" dot-content))
                                           dot-content)
                                         
                                         (or (= output-format "svg") (= output-format "png"))
                                         (let [temp-dot-file (File/createTempFile "neighborhood" ".dot")
                                               dot-path (.getAbsolutePath temp-dot-file)
                                               output-path (or output-file
                                                             (.getAbsolutePath 
                                                              (File/createTempFile "neighborhood" (str "." output-format))))]
                                           (spit dot-path dot-content)
                                           (let [shell-result (shell/sh "dot" (str "-T" output-format) dot-path "-o" output-path)]
                                             (.delete temp-dot-file)
                                             (if (= (:exit shell-result) 0)
                                               (str "Neighborhood graph saved to: " output-path "\n"
                                                    "Entities in neighborhood: " (count entities) "\n"
                                                    "Relationships: " (count relationships))
                                               (str "Error generating " output-format ": " (:err shell-result)))))
                                         
                                         :else
                                         (str "Unsupported output format: " output-format)))
                                     (catch Exception e
                                       (str "Error showing neighborhood: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def show-neighborhood-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "show_neighborhood" "Visualize entity neighborhood relationships" show-neighborhood-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (show-neighborhood-callback exchange arguments #(.success sink %)))))))))

;; 5. Load Example Visualization Data Tool
(def load-viz-example-schema
  (json/write-str {:type :object}))

(defn load-viz-example-callback [exchange arguments continuation]
  (future
    (let [{:keys [result err]} (capture-output
                                #(do
                                   (reset! viz-db (d/create-conn viz-schema))
                                   ;; Add comprehensive example data for visualization
                                   (d/transact! @viz-db
                                                [{:person/name "Alice"
                                                  :person/age 30
                                                  :person/email "alice@techcorp.com"}
                                                 {:person/name "Bob"
                                                  :person/age 25
                                                  :person/email "bob@techcorp.com"}
                                                 {:person/name "Charlie"
                                                  :person/age 35
                                                  :person/email "charlie@techcorp.com"}
                                                 {:person/name "Diana"
                                                  :person/age 28
                                                  :person/email "diana@innovate.com"}
                                                 {:person/name "Eve"
                                                  :person/age 32
                                                  :person/email "eve@innovate.com"}
                                                 {:company/name "Tech Corp"}
                                                 {:company/name "Innovate Inc"}
                                                 {:project/name "Project Alpha"}
                                                 {:project/name "Project Beta"}
                                                 {:project/name "Project Gamma"}
                                                 {:module/name "Authentication"}
                                                 {:module/name "Database"}
                                                 {:module/name "UI Components"}])
                                   
                                   ;; Add complex relationships
                                   (d/transact! @viz-db
                                                [{:person/name "Alice"
                                                  :person/friends [[:person/name "Bob"] [:person/name "Charlie"]]
                                                  :person/works-for [:company/name "Tech Corp"]
                                                  :person/works-on [[:project/name "Project Alpha"]]}
                                                 {:person/name "Bob"
                                                  :person/friends [[:person/name "Alice"] [:person/name "Diana"]]
                                                  :person/works-for [:company/name "Tech Corp"]
                                                  :person/works-on [[:project/name "Project Beta"]]}
                                                 {:person/name "Charlie"
                                                  :person/friends [[:person/name "Alice"]]
                                                  :person/spouse [:person/name "Diana"]
                                                  :person/works-for [:company/name "Tech Corp"]
                                                  :person/works-on [[:project/name "Project Alpha"] [:project/name "Project Gamma"]]}
                                                 {:person/name "Diana"
                                                  :person/friends [[:person/name "Bob"] [:person/name "Eve"]]
                                                  :person/spouse [:person/name "Charlie"]
                                                  :person/works-for [:company/name "Innovate Inc"]
                                                  :person/works-on [[:project/name "Project Beta"]]}
                                                 {:person/name "Eve"
                                                  :person/friends [[:person/name "Diana"]]
                                                  :person/works-for [:company/name "Innovate Inc"]
                                                  :person/works-on [[:project/name "Project Gamma"]]}
                                                 {:project/name "Project Beta"
                                                  :project/depends-on [[:project/name "Project Alpha"]]}
                                                 {:project/name "Project Gamma"
                                                  :project/depends-on [[:project/name "Project Alpha"] [:project/name "Project Beta"]]}
                                                 {:module/name "UI Components"
                                                  :module/imports [[:module/name "Authentication"] [:module/name "Database"]]}
                                                 {:module/name "Database"
                                                  :module/imports [[:module/name "Authentication"]]}])
                                   
                                   "Example visualization data loaded with people, companies, projects, and modules"))]
      (continuation (text-result (if (str/blank? err) result (str "Error: " err)))))))

(def load-viz-example-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "load_viz_example" "Load comprehensive example data for visualization testing" load-viz-example-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (load-viz-example-callback exchange arguments #(.success sink %)))))))))

;; 6. Dependency Tree Visualization Tool
(def visualize-dependencies-schema
  (json/write-str {:type :object
                   :properties {:root-entity {:type :string
                                             :description "Root entity ID or lookup ref as EDN"}
                               :dependency-attr {:type :string
                                               :description "Dependency attribute (default: ':project/depends-on')"}
                               :direction {:type :string
                                         :description "Direction: 'dependencies' or 'dependents' (default: 'dependencies')"}
                               :title {:type :string
                                      :description "Graph title"}
                               :output-format {:type :string
                                             :description "Output format: 'dot', 'svg', 'png' (default: 'dot')"}
                               :output-file {:type :string
                                           :description "Output file path (optional)"}}
                   :required [:root-entity]}))

(defn get-dependency-tree [db root-id dep-attr direction]
  (let [visited (atom #{})
        tree-entities (atom #{root-id})]
    (letfn [(collect-tree [entity-id]
              (when-not (@visited entity-id)
                (swap! visited conj entity-id)
                (if (= direction "dependents")
                  ;; Find entities that depend on this one
                  (let [dependents (d/q '[:find [?e ...]
                                          :in $ ?target ?attr
                                          :where [?e ?attr ?target]]
                                        db entity-id dep-attr)]
                    (doseq [dependent dependents]
                      (swap! tree-entities conj dependent)
                      (collect-tree dependent)))
                  ;; Find what this entity depends on
                  (let [entity (d/entity db entity-id)
                        dependencies (get entity dep-attr)]
                    (when dependencies
                      (let [dep-list (if (coll? dependencies) dependencies [dependencies])]
                        (doseq [dep dep-list]
                          (let [dep-id (:db/id dep)]
                            (swap! tree-entities conj dep-id)
                            (collect-tree dep-id)))))))))]
      (collect-tree root-id)
      @tree-entities)))

(defn visualize-dependencies-callback [exchange arguments continuation]
  (future
    (if-not @viz-db
      (continuation (text-result "Visualization database not initialized. Please run init_viz_db first."))
      (let [root-entity-str (get arguments "root-entity")
            dep-attr-str (get arguments "dependency-attr" ":project/depends-on")
            direction (get arguments "direction" "dependencies")
            title (get arguments "title")
            output-format (get arguments "output-format" "dot")
            output-file (get arguments "output-file")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [db @(deref viz-db)
                                           root-ref (read-string root-entity-str)
                                           root-id (if (number? root-ref)
                                                     root-ref
                                                     (:db/id (d/entity db root-ref)))
                                           dep-attr (read-string dep-attr-str)
                                           tree-ids (get-dependency-tree db root-id dep-attr direction)
                                           entity-filter (set tree-ids)
                                           {:keys [entities relationships]} (extract-relationships db entity-filter)
                                           graph-title (or title
                                                          (str (str/capitalize direction) " of "
                                                               (get-entity-display-name (d/entity db root-id))))
                                           dot-content (generate-dot-graph entities relationships graph-title)]
                                       
                                       (cond
                                         (= output-format "dot")
                                         (if output-file
                                           (do
                                             (spit output-file dot-content)
                                             (str "DOT file saved to: " output-file "\n\n" dot-content))
                                           dot-content)
                                         
                                         (or (= output-format "svg") (= output-format "png"))
                                         (let [temp-dot-file (File/createTempFile "dependencies" ".dot")
                                               dot-path (.getAbsolutePath temp-dot-file)
                                               output-path (or output-file
                                                             (.getAbsolutePath 
                                                              (File/createTempFile "dependencies" (str "." output-format))))]
                                           (spit dot-path dot-content)
                                           (let [shell-result (shell/sh "dot" (str "-T" output-format) dot-path "-o" output-path)]
                                             (.delete temp-dot-file)
                                             (if (= (:exit shell-result) 0)
                                               (str "Dependency graph saved to: " output-path "\n"
                                                    "Entities in tree: " (count entities) "\n"
                                                    "Relationships: " (count relationships))
                                               (str "Error generating " output-format ": " (:err shell-result)))))
                                         
                                         :else
                                         (str "Unsupported output format: " output-format)))
                                     (catch Exception e
                                       (str "Error visualizing dependencies: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def visualize-dependencies-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "visualize_dependencies" "Visualize dependency tree from a root entity" visualize-dependencies-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (visualize-dependencies-callback exchange arguments #(.success sink %)))))))))

;; Visualization Server setup
(defn viz-mcp-server [& args]
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "datascript-viz-server" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    
    ;; Add all visualization tools
    (doseq [tool [init-viz-db-tool
                  add-viz-data-tool
                  generate-graph-tool
                  show-neighborhood-tool
                  load-viz-example-tool
                  visualize-dependencies-tool]]
      (-> (.addTool server tool)
        (.subscribe)))
    server))

(defn -main [& args]
  (let [server (viz-mcp-server args)]
    (println "Datascript MCP Server running on STDIO transport.")
    ;; Keep the process alive
    (while true
      (Thread/sleep 1000))))

