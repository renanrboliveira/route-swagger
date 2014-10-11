(ns sample.service
  (:require [pedestal.swagger.core :as swagger]
            [io.pedestal.http :as bootstrap]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.impl.interceptor :refer [terminate]]
            [ring.util.response :refer [response not-found created]]
            [schema.core :as s]))


;;;; Schemas

(def opt s/optional-key)
(def req s/required-key)

(s/defschema Category
  {(req :id) s/Int
   (req :name) s/Str})

(s/defschema Tag
  {(req :id) s/Int
   (req :name) s/Str})

(s/defschema Pet
  {(req :id) s/Int
   (req :name) s/Str
   (opt :category) Category
   (opt :tags) [Tag]
   (opt :status) (s/enum "available" "pending" "sold")})

(s/defschema PetList
  {(req :total) s/Int
   (req :pets) [Pet]})

(def PartialPet
  {(opt :name) s/Str
   (opt :status) (s/enum "available" "pending" "sold")})

;

(s/defschema User
  {(req :username) s/Str
   (req :password) s/Str
   (opt :first-name) s/Str
   (opt :last-name) s/Str
   (opt :status) (s/enum "registered" "active" "closed")})

;

(s/defschema NewOrder
  {(req :pet-id) s/Int
   (req :user-id) s/Int
   (opt :notes) s/Str})

(s/defschema Order
  (assoc NewOrder
    (req :id) s/Int
    (req :status) s/Str
    (req :ship-date) s/Inst))

;

;; Store

(def pet-store (atom {}))

(swagger/defhandler get-all-pets
  {:summary "Get all pets in the store"
   :responses {200 {:schema PetList}}}
  [_]
  (response (let [pets (vals (:pets @pet-store))]
              {:total (count pets)
               :pets (or pets [])})))

(swagger/defhandler add-pet
  {:summary "Add a new pet to the store"
   :parameters {:body Pet}
   :responses {201 {:headers ["Location"]}
               400 {:description "Malformed parameters"}}}
  [{:keys [body-params] :as req}]
  (let [store (swap! pet-store assoc-in [:pets (:id body-params)] body-params)]
      (created (route/url-for ::get-pet-by-id :params {:id (:id body-params)}) "")))

(swagger/defbefore load-pet-from-db
  {:description "Assumes a pet exists with given ID"
   :parameters {:path {:id s/Int}}
   :responses {404 {:description "ID does not correspond to any pet"}}}
  [{:keys [request response] :as context}]
  (if-let [pet (get-in @pet-store [:pets (-> request :path-params :id)])]
    (assoc-in context [:request ::pet] pet)
    (-> context
        terminate
        (assoc-in [:response] (not-found "Pet not found")))))

(swagger/defhandler get-pet-by-id
  {:summary "Find pet by ID"
   :description "Returns a pet based on ID"
   :responses {200 {:schema Pet}}}
  [{:keys [::pet] :as req}]
  (response pet))

(swagger/defhandler update-pet
  {:summary "Update an existing pet"
   :parameters {:body Pet}
   :responses {400 {:description "Malformed parameters"}}}
  [{:keys [path-params body-params] :as req}]
  (let [store (swap! pet-store assoc-in [:pets (:id path-params)] body-params)]
    (response "OK")))

(swagger/defhandler update-pet-with-form
  {:summary "Updates a pet in the store with form data"
   :parameters {:form PartialPet}
   :responses {400 {:description "Malformed parameters"}}}
  [{:keys [path-params form-params] :as req}]
  (let [store (swap! pet-store update-in [:pets (:id path-params)] merge form-params)]
    (response "OK")))

;

(swagger/defhandler add-user
  {:summary "Create user"
   :parameters {:body User}
   :responses {201 {:headers ["Location"]}}}
  [{:keys [body-params] :as req}]
  (let [store (swap! pet-store assoc-in [:users (:username body-params)] body-params)]
    (created (route/url-for ::get-user-by-name :params {:username (:username body-params)}) "")))

(swagger/defhandler get-user-by-name
  {:summary "Get user by name"
   :parameters {:path {:username s/Str}}
   :responses {200 {:schema User}
               404 {:description "User could not be found on the store"}}}
  [{:keys [path-params] :as req}]
  (if-let [user (get-in @pet-store [:users (:username path-params)])]
    (response user)
    (not-found "User not found")))

;

(swagger/defhandler add-order
  {:summary "Create order"
   :parameters {:body NewOrder}
   :responses {201 {:headers ["Location"]}}}
  [{:keys [body-params] :as req}]
  (let [id (rand-int 1000000)
        store (swap! pet-store assoc-in [:orders id] (assoc body-params :id id))]
    (created (route/url-for ::get-order-by-id :params {:id id}) "")))

(swagger/defbefore load-order-from-db
  {:description "Assumes an order exists with given ID"
   :parameters {:path {:id s/Int}}
   :responses {404 {:description "ID does not correspond to any order"}}}
  [{:keys [request response] :as context}]
  (if-let [order (get-in @pet-store [:orders (-> request :path-params :id)])]
    (assoc-in context [:request ::order] order)
    (-> context
        terminate
        (assoc-in [:response] (not-found "Order not found")))))

(swagger/defhandler get-order-by-id
  {:summary "Get user by name"
   :responses {200 {:schema Order}}}
  [{:keys [::order] :as req}]
  (response (assoc order :status "Pending" :ship-date (java.util.Date.))))

;;;; Routes

(def swagger-spec
  {:title "Swagger Sample App"
   :description "This is a sample Petstore server."
   :apiVersion "2.0"})


(swagger/defroutes routes
  [[:http "localhost" 8080
    ["/" ^:interceptors [(body-params/body-params)
                         bootstrap/json-body
                         (swagger/body-params)
                         (swagger/keywordize-params :form-params :headers)
                         (swagger/coerce-params)
                         (swagger/validate-response)]
     ["/pet"
      {:get get-all-pets}
      {:post add-pet}
      ["/:id" ^:interceptors [load-pet-from-db]
       {:get get-pet-by-id}
       {:put update-pet}
       {:patch update-pet-with-form}]]
     ["/user"
      {:post add-user}
      ["/:username"
       {:get get-user-by-name}]]
     ["/order"
      {:post add-order}
      ["/:id" ^:interceptors [load-order-from-db]
       {:get get-order-by-id}]]

     ["/ui/*resource" {:get swagger/swagger-ui}]
     ["/docs" {:get [(swagger/swagger-object swagger-spec)]}]]]])

(def service {:env :prod
              ::bootstrap/routes routes
              ::bootstrap/resource-path "/public"
              ::bootstrap/type :jetty
              ::bootstrap/port 8080})
