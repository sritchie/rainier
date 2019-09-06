package com.stripe.rainier
package cats

import com.stripe.rainier.core._
import com.stripe.rainier.scalacheck._
import com.stripe.rainier.sampler.RNG
import com.stripe.rainier.compute.Evaluator

import _root_.cats.tests.CatsSuite
import _root_.cats.laws.discipline._

class CategoricalSuite extends CatsSuite {
  checkAll("Categorical[Int]", MonadTests[Categorical].monad[Int, Int, Int])
}

class GeneratorSuite extends CatsSuite {
  implicit val rng: RNG = RNG.default
  implicit val evaluator: Evaluator = new Evaluator(Map.empty)
  implicit val iso = SemigroupalTests.Isomorphisms
    .invariant[Generator]

  checkAll("Generator[Int]", MonadTests[Generator].monad[Int, Int, Int])
  checkAll("Generator[Int]", ComonadTests[Generator].comonad[Int, Int, Int])
}

class RandomVariableSuite extends CatsSuite {
  implicit val iso = SemigroupalTests.Isomorphisms
    .invariant[RandomVariable]

  checkAll("RandomVariable[Int]",
           MonadTests[RandomVariable].monad[Int, Int, Int])

  checkAll("RandomVariable[Int]",
           ComonadTests[RandomVariable].comonad[Int, Int, Int])
}
