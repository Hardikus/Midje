(ns midje.test-util
  (:use [clojure.test]
        midje.checkers
        [midje.checkers.extended-equality :only [extended-=]]
        [midje.checkers.extended-falsehood :only [extended-false?]]
        midje.error-handling.exceptions
        [clojure.set :only [subset?]]
        [midje.util.form-utils :only [macro-for]])
  (:require 
            [midje.config :as config]
            [clojure.string :as str]
            [midje.emission.api :as emit]
            [midje.emission.state :as state]))

;;; The "silent" versions of fact and formula, which produce no user-visible results
;;; but do stash failures for later examination.

(def silent-fact:failure-count (atom :unset))
(def silent-fact:raw-failures (atom :unset))
(def ^{:dynamic true} silent-fact:last-raw-failure (atom :unset))

(defn record-failures []
  (reset! silent-fact:failure-count (state/output-counters:midje-failures))
  (reset! silent-fact:raw-failures (state/raw-fact-failures))
  (reset! silent-fact:last-raw-failure (last (state/raw-fact-failures))))


(defn silent-body [symbol-to-substitute form]
  (let [silenced (with-meta `(~symbol-to-substitute ~@(rest form)) (meta form))]
    `(emit/producing-only-raw-fact-failures
      (let [result# ~silenced]
        (record-failures)
        result#))))

(defmacro silent-fact [& _]
  (silent-body 'midje.sweet/fact &form))
(defmacro silent-tabular [& _]
  (silent-body 'midje.sweet/tabular &form))
(defmacro silent-formula [& _]
  (silent-body 'midje.sweet/formula &form))
(defmacro silent-against-background [& _]
  (silent-body 'midje.sweet/against-background &form))
(defmacro silent-background [& _]
  (silent-body 'midje.sweet/background &form))
(defmacro silent-expect [& _]
  (silent-body 'midje.semi-sweet/expect &form))
(defmacro silent-fake [& _]
  (silent-body 'midje.semi-sweet/fake &form))
(defmacro silent-data-fake [& _]
  (silent-body 'midje.semi-sweet/data-fake &form))

;;; Checkers, mainly for failures

;; First, utils

(defn multi-reason-failure? [failure-map]
  (= :mock-incorrect-call-count (:type failure-map)))

(defn all-reasons [failure-maps]
  (mapcat (fn [one-failure]
            (if (multi-reason-failure? one-failure)
              (:failures one-failure)
              [one-failure]))
          failure-maps))

(def line-number-from-failure-reason (comp second :position))

;; Checkers

(defn fact-fails-because-of-negation [failure-map]
  (some #{(:type failure-map)} [:mock-actual-inappropriately-matches-checker
                                :mock-expected-result-inappropriately-matched]))

(defn fact-failed-with-note [regex]
  (fn [actual-failure]
    (extended-= (str/join (:notes actual-failure)) regex)))

(defn fact-expected [thing]
  (fn [actual-failure]
    (extended-= (:expected-result-form actual-failure) thing)))

(defn fact-actual [thing]
  (fn [actual-failure]
    (extended-= (:actual actual-failure) thing)))

(defn fact-captured-throwable-with-message [rhs]
  (fn [actual-failure]
    (extended-= (captured-message (:actual actual-failure)) rhs)))

(defn fact-described-as [& things]
  (fn [actual-failure]
    (= things (:description actual-failure))))

;; Applies only to the first failure (which may have multiple prerequisite failures in it)
(defn failure-was-at-line [expected-line]
  (fn [actual-failure]
    (if (multi-reason-failure? actual-failure)
      (some #{expected-line}
            (map line-number-from-failure-reason (all-reasons [actual-failure])))
      (= (line-number-from-failure-reason actual-failure) expected-line))))

;; Chatty checkers.

(defn intermediate-result-left-match [left failure]
  (first (filter #(= (first %) left) (:intermediate-results failure))))

(defmacro fact-gave-intermediate-result [left _arrow_ value & ignore]
  `(fn [actual-failure#]
     (if-let [match# (intermediate-result-left-match '~left actual-failure#)]
       (= (second match#) ~value))))
      
(defmacro fact-omitted-intermediate-result [left]
  `(fn [actual-failure#]
     (not (intermediate-result-left-match '~left actual-failure#))))
  

;; Prerequisites

(def only-incorrect-call-counts (partial filter #(= (:type %) :mock-incorrect-call-count)))
(def only-incorrect-call-count-reasons (comp all-reasons only-incorrect-call-counts))
  

(defn ^{:all-failures true} some-prerequisite-was-called-with-unexpected-arguments [failure-maps]
  (some #{:mock-argument-match-failure} (map :type failure-maps)))


(defn ^{:all-failures true} prerequisite-was-called-the-wrong-number-of-times [expected-call n & notes]
  (fn [failure-maps]
    (let [reasons (only-incorrect-call-count-reasons failure-maps)
          selected-list (filter #(extended-= (:expected-call %) expected-call) reasons)]
      (= n (:actual-count (first selected-list))))))

(defn ^{:all-failures true} prerequisite-was-never-called [expected-call]
  (prerequisite-was-called-the-wrong-number-of-times expected-call 0))

                                 
;; The following two are to be used when there's only one prerequisite

(defn ^{:all-failures true} the-prerequisite-was-incorrectly-called [n & _times_]
  (fn [failure-maps]
    (some #(= n (:actual-count %)) (only-incorrect-call-count-reasons failure-maps))))

(defn ^{:all-failures true} the-prerequisite-was-never-called []
  (the-prerequisite-was-incorrectly-called 0 :times))

(defn ^{:all-failures true} failures-were-at-lines [& lines]
  (fn [failure-maps]
    ;; (prn 'fwal)
    ;; (prn lines)
    ;; (prn (all-reasons failure-maps))
    ;; (prn (map line-number-from-failure-reason (all-reasons failure-maps)))
    (= lines 
       (map line-number-from-failure-reason (all-reasons failure-maps)))))

;; Misc

(defn parser-exploded [failure-map]
  (= (:type failure-map) :validation-error))





;;;; Dispatchers


(defmacro note-that [& claims]
  (letfn [(reducer [so-far claim]
            (letfn [(claim-needs-all-failures? [claim]
                      (:all-failures (meta (ns-resolve *ns* claim))))
                    (tack-on [stuff]
                      (concat so-far stuff))
                    (tack-on-claim [all-failures?]
                      (tack-on `(~(if all-failures? '@silent-fact:raw-failures '@silent-fact:last-raw-failure)
                                 midje.sweet/=> ~claim)))]
              (cond (symbol? claim)
                    (cond (or (= claim 'fact-fails)
                              (= claim 'fact-failed))
                          (tack-on '(@silent-fact:failure-count => pos?))
                                      
                          (or (= claim 'fact-passes)
                              (= claim 'fact-passed))
                          (tack-on '(@silent-fact:failure-count => zero?))

                          :else
                          (tack-on-claim (claim-needs-all-failures? claim)))

                    (sequential? claim)
                    (let [claim-symbol (first claim)]
                      (cond (or (= claim-symbol 'fails)
                                (= claim-symbol 'failed))
                            (tack-on `(@silent-fact:failure-count midje.sweet/=> ~(second claim)))

                            :else
                            (tack-on-claim (claim-needs-all-failures? claim-symbol))))

                    :else
                    (throw (Error. (str "What kind of claim is " (pr-str claim)))))))]

    (let [clauses (reduce reducer [] claims)]
      (with-meta `(midje.sweet/fact ~@clauses) (meta &form)))))

(defmacro for-each-failure [note-command]
  `(doseq [failure# @silent-fact:raw-failures]
     (binding [silent-fact:last-raw-failure (atom failure#)]
       ~note-command)))

(defmacro for-failure [n note-command]
  ;; Note: 1-based, because used ordinally (in linguistic terms)
  `(binding [silent-fact:last-raw-failure (atom (nth @silent-fact:raw-failures ~(dec n)))]
     ~note-command))


;; Some sets of tests generate failures. The following code prevents
;; them from being counted as failures when the final summary is
;; printed. The disadvantage is that legitimate failures won't appear
;; in the final summary. They will, however, produce failure output,
;; so that's an acceptable compromise.

(defmacro without-changing-cumulative-totals [& forms]
  `(state/with-isolated-output-counters
      ~@forms))

;; This notes when a test incorrectly stepped on the running
;; count that you see from `lein midje`.
(defmacro confirming-cumulative-totals-not-stepped-on [& body]
  `(let [stashed-counters# (state/output-counters)]
     ~@body
     (midje.sweet/fact "Checking whether cumulative totals were stepped on"
       (>= (:midje-passes (state/output-counters))   (:midje-passes stashed-counters#)) midje.sweet/=> true
       (>= (:midje-failures (state/output-counters)) (:midje-failures stashed-counters#)) midje.sweet/=> true)))


;;; Capturing output

;; TODO: Going forward, captured-output shouldn't be used in tests.

(defmacro captured-output [& body]
  `(binding [clojure.test/*test-out* (java.io.StringWriter.)]
     (clojure.test/with-test-out ~@body)
     (.toString clojure.test/*test-out*)))

(def fact-output (atom nil))

(defmacro capturing-fact-output [fact1 fact2]
  `(do
     (reset! fact-output
             (captured-output ~fact1))
     ~fact2))

(defmacro capturing-failure-output [fact1 fact2]
  `(do
     (reset! fact-output
             (captured-output (state/with-isolated-output-counters ~fact1)))
     ~fact2))



;;; OLD STUFF. Keep checking which of these are worth salvaging.



(defn at-line [line-no form] 
   (with-meta form {:line line-no}))

(defmacro validation-error-with-notes [& notes]
  `(just (contains {:notes (just ~@notes)
                    :type :validation-error})))

(defmacro defn-call-countable
  "Note: For testing Midje code that couldn't use provided.
  
  Creates a function that records how many times it is called, and records 
  that count in an atom with the same name as the function with \"-count\" appended"
  [name args & body]
  (let [atom-name (symbol (str name "-count"))]
    `(do
       (def ~atom-name (atom 0))
       (defn ~name ~args
         (swap! ~atom-name inc)
         ~@body))))




