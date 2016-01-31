(ns uncomplicate.bayadera.core-test
  (:require [midje.sweet :refer :all]
            [quil.core :as q]
            [quil.applet :as qa]
            [quil.middlewares.pause-on-error :refer [pause-on-error]]
            [uncomplicate.clojurecl.core :refer :all]
            [uncomplicate.neanderthal
             [math :refer [log exp]]
             [core :refer [dim sum nrm2 fmap! copy transfer! dot]]
             [native :refer [sv]]]
            [uncomplicate.bayadera
             [protocols :as p]
             [core :refer :all]
             [distributions :refer [beta-pdf]]
             [impl :refer :all]
             [math :refer [log-beta]]
             [visual :refer :all]]
            [uncomplicate.bayadera.opencl.amd-gcn :refer
             [gcn-engine-factory posterior]]
            [clojure.java.io :as io]))

(def x-atom (atom nil))
(def y-atom (atom nil))

(with-release [dev (first (sort-by-cl-version (devices (first (platforms)))))
               ctx (context [dev])
               cqueue (command-queue ctx dev)]
  (facts
   "Core functions for gaussian distribution."
   (let [sample-count (* 256 44 94)
         mu 200.0
         sigma 10.0]
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (gaussian engine-factory mu sigma)
                    gaussian-sampler (sampler dist)
                    cl-sample (dataset engine-factory (sample gaussian-sampler sample-count))]

       (mean dist) => mu
       (sd dist) => sigma
       (mean cl-sample) => (roughly mu)
       (sd cl-sample) => (roughly sigma (/ sigma 100))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample)))))

  (facts
   "Core functions for uniform distribution."
   (let [sample-count (* 256 44 94)
         a 100.0
         b 133.4]
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (uniform engine-factory a b)
                    uniform-sampler (sampler dist)
                    cl-sample (dataset engine-factory (sample uniform-sampler sample-count))
                    cl-pdf (pdf dist cl-sample)]

       (mean cl-sample) => (roughly (mean dist))
       (sd cl-sample) => (roughly (sd dist) (/ (sd dist) 100.0))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample))
       (/ (sum cl-pdf) (dim cl-pdf)) => (roughly (/ 1.0 (- b a))))))

  (let [sample-count (* 256 44 94)
        a 2.0
        b 5.0
        beta-pdf (fn ^double [^double x] (beta-pdf a b x))]
    (facts
     "Core functions for beta distribution."
     (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                    dist (beta engine-factory a b)
                    beta-sampler (time (sampler dist))
                    cl-sample (dataset engine-factory (sample beta-sampler sample-count))
                    cl-pdf (pdf dist cl-sample)
                    host (transfer! (p/data cl-sample) (sv sample-count))]
       (reset! x-atom (transfer! (p/data cl-sample) (sv sample-count)))
       (reset! y-atom (transfer! cl-pdf (sv sample-count)))
       (mean cl-sample) => (roughly (mean dist))
       (sd cl-sample) => (roughly (sd dist) (/ (sd dist) 100.0))
       (mean-variance cl-sample) => (sv (mean cl-sample) (variance cl-sample))
       (mean-sd cl-sample) => (sv (mean cl-sample) (sd cl-sample))
       (sum cl-pdf) => (roughly (sum (time (fmap! beta-pdf host))))))))

(with-release [dev (first (sort-by-cl-version (devices (first (platforms)))))
               ctx (context [dev])
               cqueue (command-queue ctx dev)]

  (let [sample-count (* 256 44 94)
        a 2.0
        b 5.0
        z 3.0
        N 5.0
        a1 (+ z a)
        b1 (+ (- N z) b)
        beta-model
        (p/->CLDistributionModel  "beta_logpdf" 3 0 1
                                (slurp (io/resource "uncomplicate/bayadera/distributions/opencl/beta.h"))
                                (slurp (io/resource "uncomplicate/bayadera/distributions/opencl/beta.cl")))
        binomial-model
        (p/->CLLikelihoodModel "binomial_loglik" 2
                             (slurp (io/resource "uncomplicate/bayadera/distributions/opencl/binomial.h")))
        posterior-model (posterior binomial-model beta-model)]
    (with-release [engine-factory (gcn-engine-factory ctx cqueue)
                   post (udist engine-factory posterior-model)
                   post-dist (post (sv N z) (sv a b))
                   post-sampler (time (sampler post-dist))
                   cl-sample (dataset engine-factory (sample post-sampler sample-count))
                   cl-pdf (pdf post-dist cl-sample)
                   real-post (beta engine-factory a1 b1)]
      (reset! x-atom (transfer! (p/data cl-sample) (sv sample-count)))
      (reset! y-atom (transfer! cl-pdf (sv sample-count)))
      (facts
       "Core functions for beta-bernoulli distribution."
       (mean cl-sample) => (roughly (mean real-post) (/ (mean real-post) 100.0))
       (sd cl-sample) => (roughly (sd real-post) (/ (sd real-post) 100.0))
       1 => 1))))

(defn setup []
  (with-release [rand-vect (fmap! (fn ^double [^double x] (rand 10.0)) (sv 100))
                 pdf-vect (fmap! (fn ^double [^double x] (log (inc x))) (copy rand-vect))]
    (q/background 0)
    (q/image (-> (plot2d (qa/current-applet))
                 (render {:x-axis (axis 0 1) :y-axis (axis 0 0.002) :x @x-atom :y @y-atom})
                 show)
             0 0)))

(defn draw [])

(q/defsketch diagrams
  :renderer :opengl
  :size :fullscreen
  :display 1
  :setup setup
  :draw draw
  :middleware [pause-on-error])
