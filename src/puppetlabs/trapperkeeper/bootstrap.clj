(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io FileNotFoundException)
           (java.net URI URISyntaxException))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.common :as common]
            [schema.core :as schema]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.i18n.core :as i18n]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def BootstrapEntry {:entry schema/Str
                     :bootstrap-file schema/Str
                     :line-number schema/Int
                     :namespace schema/Str
                     :service-name schema/Str
                     :action schema/Keyword})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def bootstrap-config-file-name "bootstrap.cfg")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(schema/defn parse-bootstrap-line :- {:namespace schema/Str
                                      :service-name schema/Str
                                      :action schema/Keyword}
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line must be of the the form: namespace/service-name or
  /-<namespace/service-name.  The latter indicates services that the
  service should be dropped from the accumulating list of services.
  Returns {:namespace ... :service-name ... :action ...} Throws an
  ex-info exception like {:kind :invalid-config-line :line ...} when
  the line is not valid."
  [line :- schema/Str]
  (letfn [(invalid-line-ex []
            (ex-info (str
                      (i18n/trs "Invalid bootstrap config line: {0}\n" (pr-str line))
                      (i18n/trs "Required format: (/-)namespace/service-function"))
                     {:kind ::invalid-config-line
                      :line line}))]
    (if-let [[match action namespace service-name]
             (re-matches #"^(/-)?([-a-zA-Z0-9.]+)/([-a-zA-Z0-9.]+)$" line)]
      {:namespace namespace
       :service-name service-name
       :action (case action
                 nil :add
                 "/-" :drop
                 (throw (invalid-line-ex)))}
      (throw (invalid-line-ex)))))

(schema/defn ^:private remove-comments :- schema/Str
  "Given a line of text from the bootstrap config file, remove
  anything that is commented out with either a '#' or ';'. If
  the entire line is commented out, an empty string is returned."
  [line :- schema/Str]
  (-> line
      (string/replace #"(?:#|;).*$" "")
      (string/trim)))

(schema/defn find-bootstraps-from-path :- [schema/Str]
  "Given a path, return a list of .cfg files found there.
   - If the path leads directly to a file, return a list with a single item.
   - If the path leads to a directory, return a list of any .cfg files found there.
   - If the path doesn't lead to a file or directory, attempt to load a file from
   a URI (for files in jars)"
  [config-path :- schema/Str]
  (if (fs/directory? config-path)
    (map str (fs/glob (fs/file config-path "*.cfg")))
    [config-path]))

(schema/defn ^:private config-from-cli :- [schema/Str]
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of one or more
  bootstrap config files.  If so, return an object that can be read via
  `reader` (will normally be a `file`, but in the case of a config file inside
  of a .jar, it will be an `input-stream`).  Throws an IllegalArgumentException
  if a location was specified but the file doesn't actually exist."
  [cli-data :- common/CLIData]
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (let [config-files (flatten (map
                                   find-bootstraps-from-path
                                   (string/split config-path #",")))]
        (log/debug (i18n/trs "Loading bootstrap configs:\n{0}"
                             (string/join "\n" config-files)))
        config-files))))

(schema/defn ^:private config-from-cwd :- [schema/Str]
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  (let [config-file (-> bootstrap-config-file-name
                        (io/file)
                        (.getAbsoluteFile))]
    (when (.exists config-file)
      (let [config-file-path (.getAbsolutePath config-file)]
        (log/debug (i18n/trs "Loading bootstrap config from current working directory: ''{0}''" config-file-path))
        [config-file-path]))))

(schema/defn config-from-classpath :- [(schema/maybe schema/Str)]
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  (when-let [classpath-config (io/resource bootstrap-config-file-name)]
    (log/debug (i18n/trs "Loading bootstrap config from classpath: ''{0}''" classpath-config))
    [(str classpath-config)]))

(schema/defn find-bootstrap-configs :- [schema/Str]
  "Get the bootstrap config files from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data :- common/CLIData]
  (if-let [bootstrap-configs (or (config-from-cli cli-data)
                                 (config-from-cwd)
                                 (config-from-classpath))]
    bootstrap-configs
    (throw (IllegalStateException.
             (i18n/trs "Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath")))))

(schema/defn indexed
  "Returns seq of [index, item] pairs
  [:a :b :c] -> ([0 :a] [1 :b] [2 :c])"
  [coll]
  (map vector (range) coll))

(schema/defn wrap-uri-error
  [config-path :- schema/Str
   cause :- Throwable]
  (IllegalArgumentException.
   (i18n/trs "Specified bootstrap config file does not exist: ''{0}''" config-path)
   cause))

(schema/defn read-config :- [schema/Str]
  "Opens a bootstrap file from either a path or URI, and returns each line from
  the config. Throws an exception if there is a problem reading the file or
  the URI can't be loaded"
  [config-path :- schema/Str]
  (if (fs/file? config-path)
    (line-seq (io/reader (fs/file config-path)))
    (try
      ; If it's not a file, attempt to read it as a URI
      ; TODO: TK-363 - Check to make sure the URI points to a jar file
      (line-seq (io/reader (URI. config-path)))
      ; Thrown by URI constructor
      (catch URISyntaxException e
        (throw (wrap-uri-error config-path e)))
      ; Thrown by reader URI.toURL() when URI is not absolute
      (catch IllegalArgumentException e
        (throw (wrap-uri-error config-path e)))
      ; Thrown if a valid URI points to a file that does not exist
      (catch FileNotFoundException e
        (throw (wrap-uri-error config-path e))))))

(schema/defn get-annotated-bootstrap-entries :- [BootstrapEntry]
  "Reads each bootstrap entry into a map with the line number and file the
  entry is from. Returns a list of maps."
  [configs :- [schema/Str]]
  (for [config configs
        [line-number line-text] (indexed (map remove-comments (read-config config)))
        :when (not (empty? line-text))]
    (merge
     {:bootstrap-file config
      :line-number (inc line-number)
      :entry line-text}
     (parse-bootstrap-line line-text))))

(defn find-duplicates
  "Collects duplicates base on running f on each item in coll.
   Returns a map where the keys will be the result of running f on each item,
   and the values will be lists of items that are duplicates of eachother"
  [coll f]
  (->> coll
       (group-by f)
       ; filter out map values with only 1 item
       (remove #(= 1 (count (val %))))
       (into {})))

(schema/defn duplicate-protocol-error :- IllegalArgumentException
  "Returns an IllegalArgumentException describing what services implement
   the same protocol, including the line number and file the bootstrap entries
   were found"
  [duplicate-services :- {schema/Keyword [(schema/protocol services/ServiceDefinition)]}
   service->entry-map :- {(schema/protocol services/ServiceDefinition) BootstrapEntry}]
  (let [make-error-message (fn [service]
                             (let [entry (get service->entry-map service)]
                               (i18n/trs "{0}:{1}\n{2}" (:bootstrap-file entry) (:line-number entry) (:entry entry))))]
    (let [error-messages (for [[protocol-id services] duplicate-services]
                           (i18n/trs "Duplicate implementations found for service protocol ''{0}'':\n{1}"
                                     protocol-id
                                     (string/join "\n" (map make-error-message services))))]
      (IllegalArgumentException. (string/join "\n" error-messages)))))

(schema/defn check-duplicate-service-implementations!
  "Throws an exception if two services implement the same service protocol"
  [services :- [(schema/protocol services/ServiceDefinition)]
   bootstrap-entries :- [BootstrapEntry]]

  ; Zip up the services and bootstrap entries and construct a map out of them
  ; to use as a lookup table below
  (let [service->entry-map (zipmap services bootstrap-entries)]
    ; Find duplicates base on the service id returned by calling service-def-id
    ; on each service
    (let [duplicates (find-duplicates services services/service-def-id)]
      (when (not (empty? duplicates))
        (throw (duplicate-protocol-error duplicates service->entry-map))))))

(schema/defn ^:private resolve-service! :- (schema/protocol services/ServiceDefinition)
  "Given the namespace and name of a service, loads the namespace,
  calls the function, validates that the result is a valid service definition, and
  returns the service definition.  Throws an `IllegalArgumentException` if the
  service definition cannot be resolved."
  [resolve-ns :- schema/Str
   service-name :- schema/Str]
  (try (require (symbol resolve-ns))
       (catch FileNotFoundException e
         (throw+ {:type ::missing-service
                  :message (i18n/trs "Unable to load service: {0}/{1}" resolve-ns service-name)
                  :cause e})))
  (if-let [service-def (ns-resolve (symbol resolve-ns) (symbol service-name))]
    (internal/validate-service-graph! (var-get service-def))
    (throw+ {:type ::missing-service
             :message (i18n/trs "Unable to load service: {0}/{1}" resolve-ns service-name)})))

(schema/defn bootstrap-error :- IllegalArgumentException
  "Returns an IllegalArgumentException meant to wrap other errors relating to
   bootstrap problems. Includes the file and line number at which each
   problematic service entry was found"
  [entry :- schema/Str
   bootstrap-file :- schema/Str
   line-number :- schema/Int
   original-message :- schema/Str]
  (IllegalArgumentException.
    (i18n/trs "Problem loading service ''{0}'' from {1}:{2}:\n{3}"
              entry bootstrap-file line-number original-message)))

(schema/defn resolve-and-handle-errors! :- (schema/maybe (schema/protocol services/ServiceDefinition))
  "Attempts to resolve a bootstrap entry into a ServiceDefinition.
  If the bootstrap entry can't be resolved, logs a warning and returns nil.

  Throws an IllegalArgumentException if there is a problem parsing the bootstrap
  entry, or if the service is found but it has an invalid service graph."
  [{:keys [bootstrap-file line-number entry namespace service-name]}
   :- BootstrapEntry]
  (try+
   (resolve-service! namespace service-name)
    (catch [:type ::missing-service] {:keys [message]}
      (log/warn (i18n/trs "Unable to load service ''{0}'' from {1}:{2}" entry bootstrap-file line-number)))
    ; Catch and re-throw as java exception
    (catch [:type ::internal/invalid-service-graph] {:keys [message]}
      (throw (bootstrap-error entry bootstrap-file line-number message)))
    (catch [:type ::bootstrap-parse-error] {:keys [message]}
      (throw (bootstrap-error entry bootstrap-file line-number message)))))

(schema/defn resolve-services! :- [(schema/protocol services/ServiceDefinition)]
  "Resolves each bootstrap entry into an instance of a trapperkeeper
  ServiceDefinition.

  Logs a warning if the bootstrap entry can't be resolved.
  Throws an IllegalArgumentException if there is a problem parsing the bootstrap
  entry, or if the service is found but it has an invalid service graph."
  [bootstrap-entries :- [BootstrapEntry]]
  (remove nil? (map resolve-and-handle-errors! bootstrap-entries)))

(schema/defn remove-duplicate-entries :- [BootstrapEntry]
  "Removes any duplicate entries from the list of BootstrapEntry maps.
   A entry is considered a duplicate if the :entry key is the same between two
   entries.
   Logs warnings for each duplicate found."
  [entries :- [BootstrapEntry]]
  (let [into-ordered-list
        ; Accumulates bootstrap entries into :set to test for duplicates, and
        ; accumulates the BootstrapEntry's into :ordered-entries so
        ; that the order of their line numbers is maintained
        (fn [acc annotated-entry]
          (if (contains? (:set acc) (:entry annotated-entry))
            (do (log/warn
                  (i18n/trs "Duplicate bootstrap entry found for service ''{0}'' on line number ''{1}'' in file ''{2}''"
                            (:entry annotated-entry)
                            (:line-number annotated-entry)
                            (:bootstrap-file annotated-entry)))
                acc)
            {:set (conj (:set acc) (:entry annotated-entry))
             :ordered-entries (conj (:ordered-entries acc) annotated-entry)}))]
    ; Reduce the bootstrap entries into an ordered list of unique entries and return it
    (:ordered-entries (reduce
                       into-ordered-list
                       {:set #{} :ordered-entries []}
                       entries))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(schema/defn parse-bootstrap-configs! :- [(schema/protocol services/ServiceDefinition)]
  "Parse multiple trapperkeeper bootstrap configuration files and return the
  service graph that is the result of merging the graphs of all of the
  services specified in the configuration files."
  [configs :- [schema/Str]]
  ; We remove the duplicate entries (the exact same namespace and name) to allow
  ; the user to have duplicate entries in their bootstrap files. If we didn't
  ; remove them, it would look like two services were trying to implement the
  ; same protocol when we check for duplicate service implementations. We want
  ; to allow entries with the same exact name in order to support workflows
  ; where users are preparing to upgrade and might copy an entry to another file.
  (let [bootstrap-entries (->> (get-annotated-bootstrap-entries configs)
                               (remove :drop)
                               remove-duplicate-entries)]
    (when (empty? bootstrap-entries)
      (throw (Exception. (i18n/trs "No entries found in any supplied bootstrap file(s):\n{0}"
                                   (string/join "\n" configs)))))
    (let [resolved-services (resolve-services! bootstrap-entries)]
      (check-duplicate-service-implementations! resolved-services bootstrap-entries)
      resolved-services)))

(schema/defn parse-bootstrap-config! :- [(schema/protocol services/ServiceDefinition)]
  "Parse a single bootstrap configuration file and return the service graph
  that is the result of merging the graphs of all the services specified in the
  configuration file"
  [config :- schema/Str]
  (parse-bootstrap-configs! [config]))
