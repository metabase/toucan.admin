(ns toucan.admin.page
  (:require [clojure.tools.logging :as log]
            [toucan.admin.common :as common]))

(defn page-style? [page-style]
  (common/isa? page-style :page-style))

(defmacro define-page-style
  ([page-style]
   (common/derive page-style :page-style))

  ([page-style parent-style]
   {:pre [(page-style? parent-style)]}
   (common/derive page-style parent-style)))

(defmulti render-page
  "Render function for generating a generic MetaStore admin page the `render-page` implementation for `page-style`.
  Options vary by page style, but are usually

  *  `page-style` - style to use for rendering this page.
  *  `title` - name of the page.
  *  `contents-template-filename` - Mustache template filename for the main page contents.
  *  `contents-data` - map of parameters to use to render the contents template.
  *  `actions` - sequence of quick actions for the page. Actions are any object satisfying the `Action` protocol, e.g.
      `actions/search-action` or `actions/link-action`.
  *  `crumbs` - sequence of navigation breadcrumbs. Crumbs should be maps with `:url` and `:title` keys."
  {:arglists '([page-style options]), :style/indent 1}
  (fn [page-style _]
    (keyword page-style))
  :hierarchy #'common/hierarchy)

(defn render-page-with-template
  {:style/indent 1}
  [page-style page-template-filename {:keys [title contents-template-filename contents-data actions crumbs]
                                      :as   options}]
  {:pre [(string? page-template-filename)
         (string? title)
         (string? contents-template-filename)
         (or (every? common/action? actions)
             (println "Invalid actions:" actions))
         (or (every? common/crumb? crumbs)
             (println "Invalid crumbs:" crumbs))]}
  (common/render-template
   page-template-filename
   (merge
    {:pageTitle  title
     :contents   (common/render-template contents-template-filename contents-data)
     :hasActions (boolean (seq actions))
     :actions    (vec
                  (for [action actions]
                    (do
                      (try
                        {:action (common/render action)}
                        (catch Throwable e
                          (log/errorf e "Failed to render action %s" action))))))}
    (dissoc options :actions))))

;; don't redefine if it was redefined elsewhere
(when-not (get-method render-page :default)
  (defmethod render-page :default
    [page-style options]
    (render-page-with-template page-style "toucan/admin/templates/page.html.mustache" options)))
