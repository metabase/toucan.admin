(ns toucan.admin.actions
  (:require [pretty.core :refer [PrettyPrintable]]
            [stencil.core :as stencil]
            [toucan.admin.common :as common]))

(def ^:private search-action-default-options
  {:url         "/"
   :placeholder "..."
   :param       "q"
   :button-text "Search"})

(defn search-action
  "A page action that renders a search bar and submit button that is sent as a request to `url`."
  {:style/indent 1, :arglists '([title & {:keys [url placeholder param button-text value]}])}
  [title & {:as options}]
  (let [options (merge search-action-default-options {:title title} options)]
    (reify
      PrettyPrintable
      (pretty [_]
        `(~'search-action ~title ~@(let [m (dissoc options :title)]
                                     (interleave (keys m) (vals m)))))

      common/Action
      (render [_]
        (stencil/render-file "toucan/admin/templates/actions/search-bar.html.mustache" options)))))

(defn link-action
  "A page action that renders a button to the provided `url`."
  {:style/indent 1, :arglists '([title & {:keys [url]}])}
  [title & {:keys [url], :as options}]
  {:pre [(string? url)]}
  (let [options (merge {:title title} options)]
    (reify
      PrettyPrintable
      (pretty [_]
        (list 'link-action title :url url))

      common/Action
      (render [_]
        (stencil/render-file "toucan/admin/templates/actions/link.html.mustache" options)))))

(defn post-action
  "Similar to `link-action`, but submits the request using `POST` instead."
  {:style/indent 1, :arglists '([title & {:keys [url]}])}
  [title & {:keys [url], :as options}]
  {:pre [(string? url)]}
  (let [options (merge {:title title} options)]
    (reify
      PrettyPrintable
      (pretty [_]
        (list 'post-action title :url url))

      common/Action
      (render [_]
        (stencil/render-file "toucan/admin/templates/actions/post.html.mustache" options)))))
