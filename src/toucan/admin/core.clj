(ns toucan.admin.core
  (:require [compojure
             [core :as compojure :refer [ANY GET]]
             [route :as route]]
            [toucan.admin
             [page :as page]
             [views :as views]]))

(def ^:private home-page-actions (atom nil))

(defn set-home-page-actions! [actions]
  (reset! home-page-actions actions))

(page/define-page-style :home)

(defn home-page
  "`GET /store-admin` view. Home page for the MetaStore admin."
  [_]
  (page/render-page :home
    {:title                      "Toucan Admin"
     :contents-template-filename "toucan/admin/templates/home.html.mustache"}))

(def routes
  (compojure/routes
   (GET "/" [] home-page)
   views/handle-view-request
   (ANY "*" [] route/not-found)))
