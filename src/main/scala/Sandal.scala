/*
 *
 *  Copyright (c) 2015, 2016 Los Alamos National Security, LLC
 *                          All rights reserved.
 *
 *  This file is part of the Sandal project. See the LICENSE.txt file at the
 *  top-level directory of this distribution.
 *
 */

import org.apache.spark.{SparkConf, SparkContext}
import squants.motion.MetersPerSecond
import squants.time.{Seconds, Time}

object Sandal {
  // main app loop
  def main(args: Array[String]) = {
    // set up Spark
    val conf: SparkConf = new SparkConf().setAppName("Sandal").setMaster("local[16]")
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    //conf.set("spark.kryo.registrationRequired", "true")
    implicit val sc: SparkContext = new SparkContext(conf)
    sc.setCheckpointDir("/tmp/spark-checkpoint")

    // read the configuration
    val config: SandalConfig = SandalConfigReader("src/main/resources/random_walk.inp")
    val config_b = sc.broadcast(config)

    // TODO I think the wind2d.out is actually generated by the config, and written to disk,
    // so the Wind2DOutReader is probably not necessary
    val wind_config: Wind2DConfig = Wind2DConfigReader("src/main/resources/wind2d.inp")
    // constant wind field
    val wind: Wind2D = Wind2DOutReader("src/main/resources/20141020-0600_wind2d.out", wind_config)

    // initial particles
    var particles: Particles = ParticleGenerator(
      PointSource(config.source_point.position),
      config.sim.nparticles)
    particles.position.persist()
    particles.position.checkpoint()

    // functions for euler step and converting to and from world and grid space
    val dxdy = wind.dxdy
    val fixedStep2 = eulerStep2(Seconds(config_b.value.sim.dt)) _
    val fixedStep3 = eulerStep3(Seconds(config_b.value.sim.dt)) _
    val fixedWeight = weightVelocity(dxdy) _
    val toGridPoint = wind.toGridPoint
    val toWorldPosition = wind.toWorldPosition

    for (t <- 0 to config.sim.duration.toInt by config.sim.dt.toInt) {
      ParticleWriter(s"timestep$t", particles)

      // constant turbulence
      val turb: HomogeneousTurbulence = HomogeneousTurbulence(
        MetersPerSecond(config.turb_constant.stddev_u),
        MetersPerSecond(config.turb_constant.stddev_v),
        MetersPerSecond(config.turb_constant.stddev_w),
        config.sim.nparticles)
      // we need to give the particles a unique index
      val unique = particles.position.zipWithIndex()

      // find out what cell the particle lives in
      val lowerleft = unique
        .keyBy { case (xyz, index) =>
        toGridPoint(Position2(xyz.x, xyz.y))
      } // figure out the lower left

      // get our stencil for that cell
      val stencils = lowerleft
        .join(wind.enclosing) // get the stencil per particle

      // look up the velocities for that stencil
      val stencilVels = stencils
        .map { case (origin, ((xyz, id), (stencil, order))) =>
        (stencil, (xyz, id, order, origin)) } // key by vel cell
        .join(wind.velocity) // look up the velocities

      // weight the velocities by particle in cell position
      val weightedVels = stencilVels
        .map { case (_, ((xyz, id, order, origin), vel)) =>
          (id, (xyz, vel, order, toWorldPosition(origin))) } // key by particle
        .map(fixedWeight) // weight the velocities

      // advect the particles by the weighted velocities
      val advected = weightedVels
        .reduceByKey{ case ((xyz0, uv0), (xyz1, uv1)) => (xyz0, uv0 + uv1) } // sum the velocities
        .map{ case (_, (xyz, uv)) => fixedStep2(xyz, uv)} // do a fixed step

      // advect by turbulence
      particles = Particles(advected
        .zip(turb.velocity) // get a random turbulence
        .map { case (xyz, uvw) => fixedStep3(xyz, uvw) }) // do a fixed step

      // TODO no weighting of the velocities by z
      // TODO no reflection based on z boundary
      // TODO no culling based on xy boundary
      // TODO time varying wind field
      // TODO inject more particles
      // TODO partitioner to accelerate join
      // TODO periodic partitioning
      // TODO sub-time stepping
    }

    // end Spark
    sc.stop()
  }

  def eulerStep3(dt: Time)(xyz: Position3, uvw: Velocity3): Position3 = xyz + (uvw * dt)
  def eulerStep2(dt: Time)(xyz: Position3, uv: Velocity2): Position3 = xyz + (Velocity3(uv.u, uv.v, MetersPerSecond(0.0)) * dt)

  def weightVelocity(dxdy: Distance2)(el : (Long, (Position3, Velocity2, Int, Position2))) :
  (Long, (Position3, Velocity2)) = {
    el match {
      case (id, (part, vel, 0, origin)) =>
        val offset = (origin - Position2(part.x, part.y))
        val newVel = Velocity2(vel.u * (offset.x / dxdy.x), vel.v * (offset.y / dxdy.y))
        (id, (part, newVel))
      case (id, (part, vel, 1, origin)) =>
        val offset = (origin - Position2(part.x, part.y))
        val newVel = Velocity2(vel.u * (offset.x / dxdy.x), vel.v * (1.0 - (offset.y / dxdy.y)))
        (id, (part, newVel))
      case (id, (part, vel, 2, origin)) =>
        val offset = (origin - Position2(part.x, part.y))
        val newVel = Velocity2(vel.u * (1.0 - (offset.x / dxdy.x)), vel.v * (offset.y / dxdy.y))
        (id, (part, newVel))
      case (id, (part, vel, 3, origin)) =>
        val offset = (origin - Position2(part.x, part.y))
        val newVel = Velocity2(vel.u * (1.0 - (offset.x / dxdy.x)), vel.v * (1.0 - (offset.y / dxdy.y)))
        (id, (part, newVel))
    }
  }
}
