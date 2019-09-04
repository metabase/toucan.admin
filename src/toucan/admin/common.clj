(ns toucan.admin.common
  (:refer-clojure :exclude [derive isa?])
  (:require [clojure
             [pprint :as pprint]
             [string :as str]]
            [clojure.tools.logging :as log]
            [potemkin.types :as p.types]
            [stencil.core :as stencil]))

(def ^:dynamic *request* nil)

(def ^:dynamic *model* nil)

(def hierarchy (make-hierarchy))

(defn derive [tag parent]
  (alter-var-root #'hierarchy clojure.core/derive tag parent))

(defn isa? [tag parent]
  (clojure.core/isa? hierarchy tag parent))

(p.types/defprotocol+ Action
  "Protocol for a page 'quick action'. Page actions are rendered on the page as buttons, search boxes, or other
  widgets, and let the admin do something like click a single button to extend a trial by 30 days. Anything that
  satisfies this protocol can be rendered on the page as an action."
  (render [_]
    "Render a page 'quick action' to HTML. This HTML is spliced directly into the parent page as-is, without
    escaping."))

(defn action?
  "Is `x` a valid page 'quick action'? True if it is an object that satisfies the `Action` protocol."
  [x]
  (satisfies? Action x))

(defn render-template
  "Render a Mustache template named by `template-filename`, e.g.

    (render-template \"templates/page.html.mustache {})

  `data` is a map of Mustache template arguments passed to the template when it is rendered."
  [template-filename data]
  {:pre [(string? template-filename) (or (nil? data) (map? data))]}
  #_(println (list 'render-template template-filename data))
  (try
    (stencil/render-file template-filename data)
    (catch IllegalArgumentException e
      (log/errorf "Cannot find template named %s" template-filename)
      nil)
    (catch Exception e
      (log/errorf e "Error rendering template %s (data = %s...)"
                  template-filename
                  (apply str (take 100 (with-out-str (pprint/pprint data))))))))

(defn crumb?
  "True if `x` follows the expected format for admin page navigation breadcrumbs. Breadcrumbs should be maps with
  `:title` and `:url` keys, both of which should be non-blank strings."
  [x]
  (and (map? x)
       (every? (every-pred string? (complement str/blank?)) (map x [:title :url]))))

(defn handle-checkbox-form-input
  "Helper function for handling form input from a checkbox. Combines all checkboxes with names like
  `checkbox__features__sso` into a single key, with the selected values as a set.

    (handle-checkbox-form-input {:state \"trial\",
                                 :checkbox__features__hosting \"on\",
                                 :checkbox__features__sso \"on\"})
    ;; -> {:state \"trial\", :features #{:sso :hosting}}"
  [input]
  (reduce
   (fn [m [k v]]
     (if-let [[_ k v] (re-matches #"checkbox__(\w+)__(\w+$)" (name k))]
       (update m (keyword k) #(conj (set %) (keyword v)))
       (assoc m k v)))
   {}
   input))
