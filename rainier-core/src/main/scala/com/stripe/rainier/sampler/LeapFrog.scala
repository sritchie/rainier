package com.stripe.rainier.sampler

private[sampler] case class LeapFrog(density: DensityFunction) {
  /*
  Params layout:
  array(0..(n-1)) == ps
  array(n..(n*2-1)) == qs
  array(n*2) == potential
   */
  private val nVars = density.nVars
  private val potentialIndex = nVars * 2
  private val inputOutputSize = potentialIndex + 1
  private val pqBuf = new Array[Double](inputOutputSize)
  private val qBuf = new Array[Double](nVars)

  def newQs(stepSize: Double): Unit = {
    var i = nVars
    val j = nVars * 2
    while (i < j) {
      pqBuf(i) += (stepSize * pqBuf(i - nVars))
      i += 1
    }
  }

  def halfPsNewQs(stepSize: Double): Unit = {
    fullPs(stepSize / 2.0)
    newQs(stepSize)
  }

  def initialHalfThenFullStep(stepSize: Double): Unit = {
    halfPsNewQs(stepSize)
    copyQsAndUpdateDensity()
    pqBuf(potentialIndex) = density.density * -1
  }

  def fullPs(stepSize: Double): Unit = {
    copyQsAndUpdateDensity()
    var i = 0
    val j = nVars
    while (i < j) {
      pqBuf(i) += stepSize * density.gradient(i)
      i += 1
    }
  }

  def fullPsNewQs(stepSize: Double): Unit = {
    fullPs(stepSize)
    newQs(stepSize)
  }

  def twoFullSteps(stepSize: Double): Unit = {
    fullPsNewQs(stepSize: Double)
    copyQsAndUpdateDensity()
    pqBuf(potentialIndex) = density.density * -1
  }

  def finalHalfStep(stepSize: Double): Unit = {
    fullPs(stepSize / 2.0)
    copyQsAndUpdateDensity()
    pqBuf(potentialIndex) = density.density * -1
  }

  /**
    * Perform l leapfrog steps starting at position q and momentum p
    * @param l the total number of leapfrog steps to perform
    * @return the new value of the parameters and momentum
    * array with updated density
    */
  private def steps(l: Int, stepSize: Double): Unit = {
    initialHalfThenFullStep(stepSize)
    var i = 1
    while (i < l) {
      twoFullSteps(stepSize)
      i += 1
    }
    finalHalfStep(stepSize)
  }

  def isUTurn(theta: Array[Double], pqP: Array[Double]): Boolean = {

    var out = 0.0
    var i = 0
    while (i < theta.size) {
      out += (pqP(i + nVars) - theta(i)) * pqP(i)
      i += 1
    }

    if (out.isNaN)
      true
    else
      out < 0
  }

  /**
    * Calculate the longest-step size until a u-turn
    */
  def longestStep(l0: Int, stepSize: Double): Int = {

    val initTheta = variables(pqBuf)
    var out = pqBuf
    var l = 0
    while (!isUTurn(initTheta, pqBuf)) {
      l += 1
      steps(1, stepSize)

      if (l == l0)
        out = pqBuf
    }
    copy(out, pqBuf)
    l
  }

  /**
    * Perform a single step of the longest batch step algorithm
    */
  def longestBatchStep(l0: Int, params: Array[Double], stepSize: Double)(
      implicit rng: RNG): (Array[Double], Int) = {

    initializePs(params)
    val l = longestStep(l0, stepSize)
    if (l < l0)
      steps(l0 - l, stepSize)
    val u = rng.standardUniform
    val a = logAcceptanceProb(params, pqBuf)
    if (math.log(u) < a) {
      (pqBuf, l)
    } else {
      (params, l)
    }
  }

  /**
    * Calculate a vector representing the empirical distribution
    * of the steps taken until a u-turn
    */
  def longestBatch(l0: Int, k: Int, stepSize: Double)(
      implicit rng: RNG): Vector[Int] = {

    Vector
      .iterate((pqBuf, l0), k) {
        case (p, _) =>
          longestBatchStep(l0, p, stepSize)
      }
      .map(_._2)
  }

  private def copy(sourceArray: Array[Double],
                   targetArray: Array[Double]): Unit =
    System.arraycopy(sourceArray, 0, targetArray, 0, inputOutputSize)

  private def copyQsAndUpdateDensity(): Unit = {
    System.arraycopy(pqBuf, nVars, qBuf, 0, nVars)
    density.update(qBuf)
  }
  //Compute the acceptance probability for a single step at this stepSize without
  //re-initializing the ps, or modifying params
  def tryStepping(params: Array[Double], stepSize: Double): Double = {
    copy(params, pqBuf)
    initialHalfThenFullStep(stepSize)
    finalHalfStep(stepSize)
    logAcceptanceProb(params, pqBuf)
  }

  //attempt to take N steps
  //this will always clobber the stepSize and ps in params,
  //but will only update the qs if the move is accepted
  def step(params: Array[Double], n: Int, stepSize: Double)(
      implicit rng: RNG): Double = {
    initializePs(params)
    copy(params, pqBuf)
    steps(n, stepSize)
    val p = logAcceptanceProb(params, pqBuf)
    if (p > Math.log(rng.standardUniform))
      copy(pqBuf, params)
    p
  }

  // extract q
  def variables(array: Array[Double]): Array[Double] = {
    val newArray = new Array[Double](nVars)
    var i = 0
    while (i < nVars) {
      newArray(i) = array(i + nVars)
      i += 1
    }
    newArray
  }

  //we want the invariant that a params array always has the potential which
  //matches the qs. That means when we initialize a new one
  //we need to compute the potential.
  def initialize(implicit rng: RNG): Array[Double] = {
    java.util.Arrays.fill(pqBuf, 0.0)
    var i = nVars
    val j = nVars * 2
    while (i < j) {
      pqBuf(i) = rng.standardNormal
      i += 1
    }
    val array = new Array[Double](inputOutputSize)
    copyQsAndUpdateDensity()
    pqBuf(potentialIndex) = density.density * -1
    copy(pqBuf, array)
    initializePs(array)
    array
  }

  /**
    * This is the dot product (ps^T ps).
    * The fancier variations of HMC involve changing this kinetic term
    * to either take the dot product with respect to a non-identity matrix (ps^T M ps)
    * (a non-standard Euclidean metric) or a matrix that depends on the qs
    * (ps^T M(qs) ps) (a Riemannian metric)
    */
  private def kinetic(array: Array[Double]): Double = {
    var k = 0.0
    var i = 0
    while (i < nVars) {
      val p = array(i)
      k += (p * p)
      i += 1
    }
    k / 2.0
  }

  private def logAcceptanceProb(from: Array[Double],
                                to: Array[Double]): Double = {
    val deltaH = kinetic(to) + to(potentialIndex) - kinetic(from) - from(
      potentialIndex)
    if (deltaH.isNaN) { Math.log(0.0) } else { (-deltaH).min(0.0) }
  }

  private def initializePs(array: Array[Double])(implicit rng: RNG): Unit = {
    var i = 0
    while (i < nVars) {
      array(i) = rng.standardNormal
      i += 1
    }
  }
}
