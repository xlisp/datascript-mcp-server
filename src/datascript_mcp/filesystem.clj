(ns datascript-mcp.filesystem
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
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

(defn capture-output [k]
  (let [out-atom (atom "")
        err-atom (atom "")
        res (atom nil)]
    (binding [*out* (java.io.StringWriter.)
              *err* (java.io.StringWriter.)]
              (reset! res (k))
              (reset! out-atom (str *out*))
              (reset! err-atom (str *err*)))
    {:result @res :out @out-atom :err @err-atom}))

;; Helper functions
(defn text-content [^String s]
  (McpSchema$TextContent. s))

(defn text-result [^String s]
  (McpSchema$CallToolResult. [(McpSchema$TextContent. s)] false))

(defn error-result [^String error-msg]
  (McpSchema$CallToolResult. [(text-content (str "Error: " error-msg))] true))

;; Original eval tool
(defn eval-tool-helper [form-str]
  (capture-output
   (fn []
     (let [form (read-string (str "(do " form-str ")"))]
       (pr-str (eval form))))))

(def eval-schema
  (json/write-str {:type :object
                   :properties {:expression {:type :string}}
                   :required [:expression]}))

(defn eval-tool-callback [exchange arguments continuation]
  (future
    (try
      (let [{:keys [out err result]} (eval-tool-helper (get arguments "expression"))]
        (continuation (text-result result)))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def eval-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "clojure_eval" 
                    "Takes a Clojure Expression and evaluates it in the user namespace. For example: provide \"(+ 1 2)\" and this will evaluate that and return 3" 
                    eval-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (eval-tool-callback exchange arguments #(.success sink %)))))))))

;; File read tool
(def read-file-schema
  (json/write-str {:type :object
                   :properties {:path {:type :string
                                      :description "Path to the file to read"}}
                   :required [:path]}))

(defn read-file-callback [exchange arguments continuation]
  (future
    (try
      (let [path (get arguments "path")
            file (File. path)]
        (if (.exists file)
          (if (.isFile file)
            (let [content (slurp path)]
              (continuation (text-result content)))
            (continuation (error-result (str "Path is not a file: " path))))
          (continuation (error-result (str "File does not exist: " path)))))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def read-file-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "read_file" 
                    "Reads the contents of a file and returns it as text" 
                    read-file-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (read-file-callback exchange arguments #(.success sink %)))))))))

;; File write tool
(def write-file-schema
  (json/write-str {:type :object
                   :properties {:path {:type :string
                                      :description "Path to the file to write"}
                               :content {:type :string
                                        :description "Content to write to the file"}
                               :append {:type :boolean
                                       :description "Whether to append to file (default: false)"
                                       :default false}}
                   :required [:path :content]}))

(defn write-file-callback [exchange arguments continuation]
  (future
    (try
      (let [path (get arguments "path")
            content (get arguments "content")
            append? (get arguments "append" false)]
        (if append?
          (spit path content :append true)
          (spit path content))
        (continuation (text-result (str "Successfully " 
                                       (if append? "appended to" "wrote") 
                                       " file: " path))))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def write-file-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "write_file" 
                    "Writes content to a file. Can either overwrite or append to existing files" 
                    write-file-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (write-file-callback exchange arguments #(.success sink %)))))))))

;; List directory tool
(def list-dir-schema
  (json/write-str {:type :object
                   :properties {:path {:type :string
                                      :description "Path to the directory to list (default: current directory)"
                                      :default "."}}
                   :required []}))

(defn list-dir-callback [exchange arguments continuation]
  (future
    (try
      (let [path (get arguments "path" ".")
            dir (File. path)]
        (if (.exists dir)
          (if (.isDirectory dir)
            (let [files (.listFiles dir)
                  file-info (map (fn [^File f]
                                  (str (.getName f) 
                                       (if (.isDirectory f) "/" "")
                                       " (" (.length f) " bytes)"))
                                files)
                  result (clojure.string/join "\n" file-info)]
              (continuation (text-result result)))
            (continuation (error-result (str "Path is not a directory: " path))))
          (continuation (error-result (str "Directory does not exist: " path)))))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def list-dir-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "list_directory" 
                    "Lists the contents of a directory" 
                    list-dir-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (list-dir-callback exchange arguments #(.success sink %)))))))))

;; Execute command tool
(def exec-command-schema
  (json/write-str {:type :object
                   :properties {:command {:type :string
                                         :description "Command to execute"}
                               :args {:type :array
                                     :items {:type :string}
                                     :description "Command arguments (optional)"
                                     :default []}
                               :dir {:type :string
                                    :description "Working directory (optional)"}}
                   :required [:command]}))

(defn exec-command-callback [exchange arguments continuation]
  (future
    (try
      (let [command (get arguments "command")
            args (get arguments "args" [])
            dir (get arguments "dir")
            shell-args (if dir
                        (concat [:sh command] args [:dir dir])
                        (concat [:sh command] args))
            result (apply shell/sh shell-args)
            {:keys [exit out err]} result]
        (if (zero? exit)
          (continuation (text-result (str "Command executed successfully:\n" out)))
          (continuation (text-result (str "Command failed with exit code " exit ":\n"
                                         "STDOUT: " out "\n"
                                         "STDERR: " err)))))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def exec-command-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "execute_command" 
                    "Executes a shell command and returns the output" 
                    exec-command-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (exec-command-callback exchange arguments #(.success sink %)))))))))

;; File exists tool
(def file-exists-schema
  (json/write-str {:type :object
                   :properties {:path {:type :string
                                      :description "Path to check"}}
                   :required [:path]}))

(defn file-exists-callback [exchange arguments continuation]
  (future
    (try
      (let [path (get arguments "path")
            file (File. path)
            exists? (.exists file)
            is-file? (.isFile file)
            is-dir? (.isDirectory file)
            readable? (.canRead file)
            writable? (.canWrite file)
            size (if is-file? (.length file) 0)
            result (json/write-str {:exists exists?
                                   :is-file is-file?
                                   :is-directory is-dir?
                                   :readable readable?
                                   :writable writable?
                                   :size size})]
        (continuation (text-result result)))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def file-exists-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "file_info" 
                    "Checks if a file or directory exists and returns detailed information about it" 
                    file-exists-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (file-exists-callback exchange arguments #(.success sink %)))))))))

;; Create directory tool
(def create-dir-schema
  (json/write-str {:type :object
                   :properties {:path {:type :string
                                      :description "Path of directory to create"}
                               :parents {:type :boolean
                                        :description "Create parent directories if they don't exist (default: false)"
                                        :default false}}
                   :required [:path]}))

(defn create-dir-callback [exchange arguments continuation]
  (future
    (try
      (let [path (get arguments "path")
            parents? (get arguments "parents" false)
            dir (File. path)
            success? (if parents?
                      (.mkdirs dir)
                      (.mkdir dir))]
        (if success?
          (continuation (text-result (str "Successfully created directory: " path)))
          (continuation (error-result (str "Failed to create directory: " path)))))
      (catch Exception e
        (continuation (error-result (.getMessage e)))))))

(def create-dir-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "create_directory" 
                    "Creates a new directory" 
                    create-dir-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (create-dir-callback exchange arguments #(.success sink %)))))))))

;; Original hello tool
(def hello-schema (json/write-str {:type :object}))

(defn hello-tool-callback [exchange arguments continuation]
  (future
    (Thread/sleep 1000)
    (continuation (McpSchema$CallToolResult.
                   [(text-content "Hello world!")
                    (text-content "Hello doors!")]
                   false))))

(def hello-tool
  (McpServerFeatures$AsyncToolSpecification.
   (McpSchema$Tool. "hello" "Returns hello" hello-schema)
   (reify java.util.function.BiFunction
     (apply [this exchange arguments]
       (Mono/create
        (reify java.util.function.Consumer
          (accept [this sink]
            (hello-tool-callback exchange arguments #(.success sink %)))))))))

;; Server setup
(defn mcp-server [& args]
  (let [transport-provider (StdioServerTransportProvider. (ObjectMapper.))
        server (-> (McpServer/async transport-provider)
                   (.serverInfo "filesystem-server" "0.2.0")
                   (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                                      (.tools true)
                                      (.build)))
                   (.build))]
    ;; Add all tools
    (doseq [tool [eval-tool hello-tool read-file-tool write-file-tool 
                  list-dir-tool exec-command-tool file-exists-tool create-dir-tool]]
      (-> (.addTool server tool)
          (.subscribe)))
    server))

