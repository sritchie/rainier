package com.stripe.rainier
package cats

import com.stripe.rainier.compute.{Constant, Infinity, NegInfinity, Real}
import com.stripe.rainier.core.{
  updateMap,
  Categorical,
  Generator,
  RandomVariable
}
import com.stripe.rainier.sampler.RNG
import _root_.cats.{Comonad, Eq, Monad}
import _root_.cats.kernel.instances.map._

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object `package` extends LowPriorityInstances with EqInstances {
  implicit val rainierMonadCategorical: Monad[Categorical] =
    MonadCategorical
  implicit val rainierMonadGenerator: Monad[Generator] = new MonadGenerator
  implicit val rainierMonadRandomVariable: Monad[RandomVariable] =
    new MonadRandomVariable
}

private[cats] sealed abstract class LowPriorityInstances {
  implicit def generatorComonad(implicit r: RNG,
                                n: Numeric[Real]): Comonad[Generator] =
    new MonadGenerator with Comonad[Generator] {
      def coflatMap[A, B](ga: Generator[A])(
          f: Generator[A] => B): Generator[B] =
        Generator.constant(f(ga))
      def extract[A](x: Generator[A]): A = x.get
    }

  implicit val randomVariableComonad: Comonad[RandomVariable] =
    new MonadRandomVariable with Comonad[RandomVariable] {
      def coflatMap[A, B](rva: RandomVariable[A])(
          f: RandomVariable[A] => B): RandomVariable[B] = RandomVariable(f(rva))
      def extract[A](rv: RandomVariable[A]): A = rv.value
    }
}

private[cats] object MonadCategorical extends Monad[Categorical] {
  def pure[A](x: A): Categorical[A] = Categorical(Map(x -> Real.one))

  override def map[A, B](fa: Categorical[A])(f: A => B): Categorical[B] =
    fa.map(f)

  override def product[A, B](
      fa: Categorical[A],
      fb: Categorical[B]
  ): Categorical[(A, B)] = fa.zip(fb)

  override def flatMap[A, B](fa: Categorical[A])(
      f: A => Categorical[B]): Categorical[B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(
      f: A => Categorical[Either[A, B]]): Categorical[B] = {
    @tailrec
    def run(acc: Map[B, Real],
            queue: Queue[(Either[A, B], Real)]): Map[B, Real] =
      if (queue.isEmpty) acc
      else {
        queue.head match {
          case (Left(a), r) =>
            run(acc, queue.tail ++ f(a).pmf.mapValues(_ * r))
          case (Right(b), r) =>
            run(updateMap(acc, b, r)(Real.zero)(_ + _), queue.tail)
        }
      }
    val pmf = run(Map.empty, f(a).pmf.to[Queue])
    Categorical[B](pmf)
  }
}

private[cats] class MonadGenerator extends Monad[Generator] {
  def pure[A](x: A): Generator[A] = Generator.constant(x)

  override def map[A, B](fa: Generator[A])(f: A => B): Generator[B] =
    fa.map(f)

  override def product[A, B](fa: Generator[A],
                             fb: Generator[B]): Generator[(A, B)] =
    fa.zip(fb)

  def flatMap[A, B](fa: Generator[A])(f: A => Generator[B]): Generator[B] =
    fa.flatMap(f)

  def tailRecM[A, B](a: A)(f: A => Generator[Either[A, B]]): Generator[B] = {
    @tailrec
    def run(g: Generator[Either[A, B]], r: RNG, n: Numeric[Real]): B =
      g match {
        case Generator.Const(_, Left(a))  => run(f(a), r, n)
        case Generator.Const(_, Right(b)) => b
        case Generator.From(_, fromFn) =>
          fromFn(r, n) match {
            case Left(a)  => run(f(a), r, n)
            case Right(b) => b
          }
      }
    val step = f(a)
    Generator.require(step.requirements)(run(step, _, _))
  }
}

private[cats] class MonadRandomVariable extends Monad[RandomVariable] {
  def pure[A](x: A): RandomVariable[A] = RandomVariable(x)

  override def map[A, B](fa: RandomVariable[A])(f: A => B): RandomVariable[B] =
    fa.map(f)

  def flatMap[A, B](fa: RandomVariable[A])(
      f: A => RandomVariable[B]): RandomVariable[B] =
    fa.flatMap(f)

  override def product[A, B](fa: RandomVariable[A],
                             fb: RandomVariable[B]): RandomVariable[(A, B)] =
    fa.zip(fb)

  @tailrec final def tailRecM[A, B](a: A)(
      f: A => RandomVariable[Either[A, B]]): RandomVariable[B] =
    f(a).value match {
      case Left(aa) => tailRecM(aa)(f)
      case Right(b) => RandomVariable(b)
    }
}

private[cats] trait EqInstances {

  def eqBigDecimal(epsilon: Double): Eq[BigDecimal] =
    Eq.instance { (left, right) =>
      ((left - right) / left) < epsilon && ((left - right) / right) < epsilon
    }

  implicit val eqReal: Eq[Real] = {
    val bde = eqBigDecimal(1e-6)
    Eq.instance { (left, right) =>
      (left, right) match {
        case (Infinity, Infinity) | (NegInfinity, NegInfinity) => true
        case (Constant(a), Constant(b))                        => bde.eqv(a, b)
        case _                                                 => false
      }
    }
  }

  implicit def eqCategorical[A: Eq]: Eq[Categorical[A]] = {
    implicit val mapEq: Eq[Map[A, Real]] = catsKernelStdEqForMap[A, Real]
    Eq.by(_.pmf)
  }

  implicit def eqGenerator[A](
      implicit eqA: Eq[A],
      r: RNG,
      n: Numeric[Real]
  ): Eq[Generator[A]] = Eq.by(_.get)

  implicit def eqRandomVariable[A: Eq]: Eq[RandomVariable[A]] = Eq.by(_.value)
}
