/*
 * Copyright (C) 2009-2019 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.akka.diagnostics

import akka.actor.ExtendedActorSystem
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{ Matchers, WordSpec }
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes

class ManagementRoutesSpec extends WordSpec with Matchers with ScalatestRouteTest {

  override def testConfigSource: String =
    """
      akka.diagnostics.recorder.startup-report-after = 3 weeks
      akka.diagnostics.management.enabled = true
      """

  val routes = ManagementRoutes.createExtension(system.asInstanceOf[ExtendedActorSystem]).routes(null)

  "The management routes" should {

    "provide diagnostics" in {
      Get("/diagnostics/startup-info") ~> routes ~> check {
        status should ===(StatusCodes.OK)
        // should be valid json
        val json = responseAs[JsValue]
      }
    }

    "provide config checker results" in {
      Get("/diagnostics/config-check") ~> routes ~> check {
        status should ===(StatusCodes.OK)
        // should be valid json
        val json = responseAs[JsValue]
      }
    }

    "provide thread dump" in {
      Get("/diagnostics/thread-dump") ~> routes ~> check {
        status should ===(StatusCodes.OK)
        // should be valid json
        val json = responseAs[JsValue]
      }
    }
  }

}

class DefaultManagementRoutesSpec extends WordSpec with Matchers with ScalatestRouteTest {

  override def testConfigSource: String =
    """
      akka.diagnostics.recorder.startup-report-after = 3 weeks
      """

  val routes = ManagementRoutes.createExtension(system.asInstanceOf[ExtendedActorSystem]).routes(null)

  "The management routes" should {

    "be disabled by default" in {
      Get("/diagnostics/startup-info") ~> routes ~> check {
        status should ===(StatusCodes.Forbidden)
      }
      Get("/diagnostics/config-check") ~> routes ~> check {
        status should ===(StatusCodes.Forbidden)
      }
      Get("/diagnostics/thread-dump") ~> routes ~> check {
        status should ===(StatusCodes.Forbidden)
      }

    }

  }
}
