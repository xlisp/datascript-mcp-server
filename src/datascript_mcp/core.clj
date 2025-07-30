(ns datascript-mcp.core
  (:require [clojure.data.json :as json]
            [datascript.core :as d]
            [clojure.walk :as walk]
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

;; Global database atom
(def db-atom (atom nil))

;; Default schema for common use cases
(def default-schema
  {:person/name {:db/type :db.type/string
                 :db/unique :db.unique/identity}
   :person/age {:db/type :db.type/long}
   :person/email {:db/type :db.type/string}
   :person/friends {:db/type :db.type/ref
                    :db/cardinality :db.cardinality/many}
   :person/parent {:db/type :db.type/ref}
   :person/children {:db/type :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :company/name {:db/type :db.type/string
                  :db/unique :db.unique/identity}
   :person/works-for {:db/type :db.type/ref}
   :project/name {:db/type :db.type/string
                  :db/unique :db.unique/identity}
   :project/depends-on {:db/type :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :person/works-on {:db/type :db.type/ref
                     :db/cardinality :db.cardinality/many}})

;; Initialize database
(defn init-db! []
  (reset! db-atom (d/create-conn default-schema)))

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

;; 1. Initialize Database Tool
(def init-db-schema
  (json/write-str {:type :object
                   :properties {:schema {:type :string
                                        :description "Optional custom schema as EDN string"}}
                   :required []}))

(defn init-db-callback [exchange arguments continuation]
  (future
    (let [custom-schema-str (get arguments "schema")
          schema (if (and custom-schema-str (not (str/blank? custom-schema-str)))
                   (try
                     (read-string custom-schema-str)
                     (catch Exception e
                       (continuation (text-result (str "Error parsing schema: " (.getMessage e))))
;;                        (return)
 ))
                   default-schema)
          {:keys [result err]} (capture-output
                                #(do
                                   (reset! db-atom (d/create-conn schema))
                                   "Database initialized successfully"))]
      (if (str/blank? err)
        (continuation (text-result result))
        (continuation (text-result (str "Error: " err)))))))

(def init-db-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "init_db" "Initialize a new Datascript database with optional custom schema" init-db-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (init-db-callback exchange arguments #(.success sink %)))))))))

;; 2. Add Data Tool
(def add-data-schema
  (json/write-str {:type :object
                   :properties {:data {:type :string
                                      :description "Data to add as EDN vector of entity maps"}}
                   :required [:data]}))

(defn add-data-callback [exchange arguments continuation]
  (future
    (if-not @db-atom
      (continuation (text-result "Database not initialized. Please run init_db first."))
      (let [data-str (get arguments "data")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [data (read-string data-str)
                                           tx-result (d/transact! @db-atom data)]
                                       (str "Successfully added " (count (:tx-data tx-result)) " datoms"))
                                     (catch Exception e
                                       (str "Error adding data: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def add-data-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "add_data" "Add data to the Datascript database" add-data-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (add-data-callback exchange arguments #(.success sink %)))))))))

;; 3. Query Tool
(def query-schema
  (json/write-str {:type :object
                   :properties {:query {:type :string
                                       :description "Datalog query as EDN string"}
                               :args {:type :string
                                     :description "Optional query arguments as EDN vector"}}
                   :required [:query]}))

(defn query-callback [exchange arguments continuation]
  (future
    (if-not @db-atom
      (continuation (text-result "Database not initialized. Please run init_db first."))
      (let [query-str (get arguments "query")
            args-str (get arguments "args")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [query (read-string query-str)
                                           args (if (and args-str (not (str/blank? args-str)))
                                                  (read-string args-str)
                                                  [])
                                           db @(deref db-atom)
                                           query-result (apply d/q query db args)]
                                       (format-result query-result))
                                     (catch Exception e
                                       (str "Error executing query: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def query-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "query" "Execute a Datalog query against the database" query-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (query-callback exchange arguments #(.success sink %)))))))))

;; 4. Find Path Tool
(def find-path-schema
  (json/write-str {:type :object
                   :properties {:from {:type :string
                                      :description "Starting entity ID or lookup ref as EDN"}
                               :to {:type :string
                                   :description "Target entity ID or lookup ref as EDN"}
                               :max-depth {:type :integer
                                          :description "Maximum path depth (default: 5)"}}
                   :required [:from :to]}))

(defn find-paths-between
  "Find all paths between two entities up to max-depth"
  [db from-id to-id max-depth]
  (let [visited (atom #{})
        paths (atom [])]
    (letfn [(dfs [current-id path depth]
              (when (<= depth max-depth)
                (if (= current-id to-id)
                  (swap! paths conj (conj path current-id))
                  (when-not (@visited current-id)
                    (swap! visited conj current-id)
                    (let [entity (d/entity db current-id)]
                      (doseq [[attr value] entity]
                        (when (and (keyword? attr)
                                   (not= attr :db/id))
                          (cond
                            ;; Reference attribute
                            (and (map? value) (:db/id value))
                            (dfs (:db/id value) (conj path current-id attr) (inc depth))
                            
                            ;; Collection of references
                            (and (coll? value) (every? #(and (map? %) (:db/id %)) value))
                            (doseq [ref-entity value]
                              (dfs (:db/id ref-entity) (conj path current-id attr) (inc depth)))))))
                    (swap! visited disj current-id)))))]
      (dfs from-id [] 0)
      @paths)))

(defn find-path-callback [exchange arguments continuation]
  (future
    (if-not @db-atom
      (continuation (text-result "Database not initialized. Please run init_db first."))
      (let [from-str (get arguments "from")
            to-str (get arguments "to")
            max-depth (or (get arguments "max-depth") 5)
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [db @(deref db-atom)
                                           from-ref (read-string from-str)
                                           to-ref (read-string to-str)
                                           from-id (if (number? from-ref) from-ref (:db/id (d/entity db from-ref)))
                                           to-id (if (number? to-ref) to-ref (:db/id (d/entity db to-ref)))
                                           paths (find-paths-between db from-id to-id max-depth)]
                                       (if (empty? paths)
                                         "No paths found between the entities"
                                         (str "Found " (count paths) " path(s):\n"
                                              ;;(str/join "\n" (map-indexed #(str (inc %1) ". " (pr-str %2)) paths))
 )))
                                     (catch Exception e
                                       (str "Error finding path: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def find-path-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "find_path" "Find relationship paths between two entities" find-path-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (find-path-callback exchange arguments #(.success sink %)))))))))

;; 5. Dependency Query Tool
(def dependency-schema
  (json/write-str {:type :object
                   :properties {:entity {:type :string
                                        :description "Entity ID or lookup ref as EDN"}
                               :dependency-attr {:type :string
                                               :description "Dependency attribute keyword (default: :project/depends-on)"}
                               :direction {:type :string
                                         :description "Direction: 'forward' for dependencies, 'reverse' for dependents (default: 'forward')"}}
                   :required [:entity]}))

(defn find-dependencies
  "Find all dependencies or dependents of an entity"
  [db entity-id dep-attr direction]
  (let [visited (atom #{})
        deps (atom [])]
    (letfn [(collect-deps [eid]
              (when-not (@visited eid)
                (swap! visited conj eid)
                (let [entity (d/entity db eid)]
                  (if (= direction "reverse")
                    ;; Find entities that depend on this one
                    (let [dependents (d/q '[:find [?e ...]
                                            :in $ ?target ?attr
                                            :where [?e ?attr ?target]]
                                          db eid dep-attr)]
                      (doseq [dependent dependents]
                        (swap! deps conj dependent)
                        (collect-deps dependent)))
                    ;; Find what this entity depends on
                    (when-let [dependencies (get entity dep-attr)]
                      (let [dep-list (if (coll? dependencies) dependencies [dependencies])]
                        (doseq [dep dep-list]
                          (let [dep-id (:db/id dep)]
                            (swap! deps conj dep-id)
                            (collect-deps dep-id)))))))))]
      (collect-deps entity-id)
      (remove #{entity-id} @deps))))

(defn dependency-callback [exchange arguments continuation]
  (future
    (if-not @db-atom
      (continuation (text-result "Database not initialized. Please run init_db first."))
      (let [entity-str (get arguments "entity")
            dep-attr-str (get arguments "dependency-attr" ":project/depends-on")
            direction (get arguments "direction" "forward")
            {:keys [result err]} (capture-output
                                  #(try
                                     (let [db @(deref db-atom)
                                           entity-ref (read-string entity-str)
                                           entity-id (if (number? entity-ref) 
                                                       entity-ref 
                                                       (:db/id (d/entity db entity-ref)))
                                           dep-attr (read-string dep-attr-str)
                                           deps (find-dependencies db entity-id dep-attr direction)
                                           direction-label (if (= direction "reverse") "dependents" "dependencies")]
                                       (if (empty? deps)
                                         (str "No " direction-label " found")
                                         (str "Found " (count deps) " " direction-label ":\n"
                                              ;; (str/join "\n" (map #(str "- Entity ID: " %) deps))
  )))
                                     (catch Exception e
                                       (str "Error finding dependencies: " (.getMessage e)))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def dependency-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "find_dependencies" "Find dependencies or dependents of an entity" dependency-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (dependency-callback exchange arguments #(.success sink %)))))))))

;; 6. Show Schema Tool
(def show-schema-schema
  (json/write-str {:type :object}))

(defn show-schema-callback [exchange arguments continuation]
  (future
    (if-not @db-atom
      (continuation (text-result "Database not initialized. Please run init_db first."))
      (let [{:keys [result err]} (capture-output
                                  #(let [schema (d/schema @(deref db-atom))]
                                     (if (empty? schema)
                                       "No schema defined"
                                       (format-result schema))))]
        (continuation (text-result (if (str/blank? err) result (str "Error: " err))))))))

(def show-schema-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "show_schema" "Show the current database schema" show-schema-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (show-schema-callback exchange arguments #(.success sink %)))))))))

;; 7. Example Data Tool
(def load-example-schema
  (json/write-str {:type :object}))

(defn load-example-callback [exchange arguments continuation]
  (future
    (let [{:keys [result err]} (capture-output
                                #(do
                                   (reset! db-atom (d/create-conn default-schema))
                                   ;; Add example data
                                   (d/transact! @db-atom
                                                [{:person/name "Alice"
                                                  :person/age 30
                                                  :person/email "alice@example.com"}
                                                 {:person/name "Bob"
                                                  :person/age 25
                                                  :person/email "bob@example.com"}
                                                 {:person/name "Charlie"
                                                  :person/age 35
                                                  :person/email "charlie@example.com"}
                                                 {:company/name "Tech Corp"}
                                                 {:project/name "Project A"}
                                                 {:project/name "Project B"}])
                                   
                                   ;; Add relationships
                                   (d/transact! @db-atom
                                                [{:person/name "Alice"
                                                  :person/friends [[:person/name "Bob"]]
                                                  :person/works-for [:company/name "Tech Corp"]
                                                  :person/works-on [[:project/name "Project A"]]}
                                                 {:person/name "Bob"
                                                  :person/friends [[:person/name "Alice"] [:person/name "Charlie"]]
                                                  :person/works-for [:company/name "Tech Corp"]
                                                  :person/works-on [[:project/name "Project B"]]}
                                                 {:person/name "Charlie"
                                                  :person/parent [:person/name "Alice"]
                                                  :person/works-for [:company/name "Tech Corp"]}
                                                 {:project/name "Project B"
                                                  :project/depends-on [[:project/name "Project A"]]}])
                                   
                                   "Example database loaded with people, company, and projects"))]
      (continuation (text-result (if (str/blank? err) result (str "Error: " err)))))))

(def load-example-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "load_example" "Load example data with people, companies and projects for testing" load-example-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (load-example-callback exchange arguments #(.success sink %)))))))))

;; Server setup
(defn mcp-server [& args]
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "datascript-server" "0.1.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    
    ;; Add all tools
    (doseq [tool [init-db-tool add-data-tool query-tool find-path-tool 
                  dependency-tool show-schema-tool load-example-tool]]
      (-> (.addTool server tool)
          (.subscribe)))
    
    server))

(defn -main [& args]
  (let [server (mcp-server args)]
    (println "Datascript MCP Server running on STDIO transport.")
    ;; Keep the process alive
    (while true
      (Thread/sleep 1000))))

(comment
  ;; For REPL testing:
  (init-db!)
  
  ;; Add some test data
  (d/transact! @db-atom
               [{:person/name "Alice" :person/age 30}
                {:person/name "Bob" :person/age 25}])
  
  ;; Query test
  (d/q '[:find ?name ?age
         :where
         [?e :person/name ?name]
         [?e :person/age ?age]]
       @@db-atom)
  
  (mcp-server)
  )

